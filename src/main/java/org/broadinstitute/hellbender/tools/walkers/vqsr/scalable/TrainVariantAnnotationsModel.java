package org.broadinstitute.hellbender.tools.walkers.vqsr.scalable;

import com.google.common.collect.Streams;
import com.google.common.primitives.Doubles;
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.walkers.vqsr.VariantRecalibrator;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.data.LabeledVariantAnnotationsData;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.data.VariantType;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.BGMMVariantAnnotationsModel;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.BGMMVariantAnnotationsScorer;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.PythonSklearnVariantAnnotationsModel;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.PythonSklearnVariantAnnotationsScorer;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.VariantAnnotationsModel;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.VariantAnnotationsModelBackend;
import org.broadinstitute.hellbender.tools.walkers.vqsr.scalable.modeling.VariantAnnotationsScorer;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.clustering.BayesianGaussianMixtureModeller;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;
import picard.cmdline.programgroups.VariantFilteringProgramGroup;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Trains a model for scoring variant calls based on site-level annotations.
 *
 * <p>
 *     This tool is intended to be used as the second step in a variant-filtering workflow that supersedes the
 *     {@link VariantRecalibrator} workflow. Given training (and optionally, calibration) sets of site-level annotations
 *     produced by {@link ExtractVariantAnnotations}, this tool can be used to train a model for scoring variant
 *     calls. For each variant type (i.e., SNP or INDEL) specified using the {@value MODE_LONG_NAME} argument, the tool
 *     outputs files that are either: 1) serialized scorers, each of which persists to disk a function for computing
 *     scores given subsequent annotations, or 2) HDF5 files containing a set of scores, each corresponding to training,
 *     calibration, and unlabeled sets, as appropriate.
 * </p>
 *
 * <p>
 *     The model files produced by this tool can in turn be provided along with a VCF file to the {@link ScoreVariantAnnotations}
 *     tool, which assigns a score to each call (with a lower score indicating that a call is more likely to be an artifact
 *     and should perhaps be filtered). Each score can also be converted to a corresponding sensitivity with respect to a
 *     calibration set, if the latter is available.
 * </p>
 *
 * <p>
 *     Note that HDF5 files may be viewed using <a href="https://support.hdfgroup.org/products/java/hdfview/">hdfview</a>
 *     or loaded in Python using <a href="http://www.pytables.org/">PyTables</a> or <a href="http://www.h5py.org/">h5py</a>.
 * </p>
 *
 * <h3>Modeling approaches</h3>
 *
 * <p>
 *     This tool can perform modeling using either a positive-only approach or a positive-negative approach.
 *     In a positive-only approach, the annotation-space distribution of training sites is used to learn a
 *     function for converting annotations for subsequent sites into a score; typically, higher scores correspond to
 *     regions of annotation space that are more densely populated by training sites. In contrast, a positive-negative
 *     approach attempts to additionally use unlabeled sites to better identify regions of annotation space that correspond
 *     to low scores against the original, positive-only model (with the assumption being that unlabeled sites are
 *     more likely to populate such regions than are training sites). A second, negative model can then be trained,
 *     and the resulting scores (which are presumably higher in regions of annotation space that are less densely
 *     populated by the original training sites) can be subtracted from the original scores to produce a final score.
 *     (Note that this positive-negative approach could be considered as a single iteration of a more general
 *     approach typically referred to as positive-unlabeled learning.)
 * </p>
 *
 * <p>
 *     A positive-only approach is likely to perform well in cases where a sufficient number of reliable training sites
 *     is available. In contrast, if 1) only a small number of reliable training sites is available, and/or
 *     2) the reliability of the training sites is questionable (e.g., the sites may be contaminated by
 *     a non-negigible number of sequencing artifacts), then a positive-negative approach may be beneficial.
 *     However, note that the positive-negative approach introduces an additional hyperparameter---the threshold
 *     that determines the selection of sites for training the negative model, controlled by the
 *     {@value CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME} argument---which may require tuning.
 *     Further note that although {@link VariantRecalibrator} (which this tool supplants) has typically been used to
 *     implement a positive-negative approach, a positive-only approach likely suffices in many use cases.
 * </p>
 *
 * <p>
 *     If a positive-only approach has been specified, then if training sites of the variant type are available:
 *
 *     <ul>
 *         <li> 1) A positive model is trained using these training sites and is serialized to file,</li>
 *         <li> 2) Scores for these training sites are generated using the positive model and output to a file,</li>
 *         <li> 3) If calibration sites of the variant type are available, scores for these calibration sites are
 *                 generated using the positive model and output to a file.</li>
 *     </ul>
 *
 *     Additionally, if a positive-negative approach has been specified (i.e., the {@value UNLABELED_ANNOTATIONS_HDF5_LONG_NAME}
 *     and {@value CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME} arguments have been provided),
 *     and if both unlabeled and calibration sites of the variant type are available, then:
 *
 *     <ul>
 *         <li> 4) The calibration scores generated from the positive model are used to convert the
 *                 calibration-sensitivity threshold into a score threshold,</li>
 *         <li> 5) Training sites with scores below the score threshold are selected for training a negative model,</li>
 *         <li> 6) Scores for unlabeled sites are generated using the positive model and output to a file,</li>
 *         <li> 7) Unlabeled sites with scores below the score threshold are selected for training a negative model,</li>
 *         <li> 8) A negative model is trained using these selected training and unlabeled sites and is serialized to file,</li>
 *         <li> 9) Scores for calibration sites are generated using the positive-negative model and overwritten in the existing file.</li>
 *     </ul>
 *
 *     Note that the positive-negative approach thus yields 1) scores for training and unlabeled sites generated from
 *     the positive model and 2) scores for calibration sites generated from the positive-negative model. This is opposed
 *     to generating scores from all sites from the positive-negative model, since these can simply be obtained from
 *     a downstream run of {@link ScoreVariantAnnotations}.
 * </p>
 *
 * <h3>Modeling backends</h3>
 *
 * <p>
 *     This tool allows the use of different backends for modeling and scoring. See also below
 *     for instructions for using a custom, user-provided implementation.
 * </p>
 *
 * <h4>Python isolation-forest backend</h4>
 *
 * <p>
 *     This backend uses scikit-learn modules to train models and scoring functions using the
 *     <a href="https://en.wikipedia.org/wiki/Isolation_forest">isolation-forest method for anomaly detection</a>.
 *     Median imputation of missing annotation values is performed before applying the method.
 * </p>
 *
 * <p>
 *     This backend can be selected by specifying {@code PYTHON_IFOREST} to the {@value MODEL_BACKEND_LONG_NAME} argument
 *     and is also currently the the default backend. It is implemented by the script at
 *     src/main/resources/org/broadinstitute/hellbender/tools/walkers/vqsr/scalable/isolation-forest.py, which
 *     requires that the argparse, h5py, numpy, sklearn, and dill packages be present in the Python environment; users
 *     may wish to simply use the provided GATK conda environment to ensure that the correct versions of all packages are available.
 *     See the IsolationForest documentation <a href="https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html">here</a>
 *     as appropriate for the version of scikit-learn used in your Python environment. The hyperparameters documented
 *     there can be specified using the {@value HYPERPARAMETERS_JSON_LONG_NAME} argument; see
 *     src/main/resources/org/broadinstitute/hellbender/tools/walkers/vqsr/scalable/isolation-forest-hyperparameters.json
 *     for an example and the default values.
 * </p>
 *
 * <h4>Java Bayesian Gaussian Mixture Model (BGMM) backend</h4>
 *
 * <p>
 *     This backend uses a pure Java implementation of a Bayesian Gaussian Mixture Model (BGMM) and hence does not require
 *     a Python environment. The implementation is a faithful port of the scikit-learn BayesianGaussianMixture module
 *     <a href="https://scikit-learn.org/stable/modules/generated/sklearn.mixture.BayesianGaussianMixture.html">BayesianGaussianMixture</a>.
 *     Median imputation of missing annotation values and standardization are both performed before applying the method.
 * </p>
 *
 * <p>
 *     This backend can be selected by specifying {@code JAVA_BGMM} to the {@value MODEL_BACKEND_LONG_NAME} argument.
 *     It is implemented by the Java classes {@link BGMMVariantAnnotationsModel} and {@link BayesianGaussianMixtureModeller}.
 *     Hyperparameters can be specified using the {@value HYPERPARAMETERS_JSON_LONG_NAME} argument; see
 *     src/main/resources/org/broadinstitute/hellbender/tools/walkers/vqsr/scalable/bgmm-hyperparameters.json
 *     for an example and the default values. The API for these hyperparameters is very similar to that of the
 *     scikit-learn module; see documentation in the aforementioned classes and default JSON file to understand the
 *     differences.
 * </p>
 *
 * <h3>Calibration sets</h3>
 *
 * <p>
 *     The choice of calibration set will determine the conversion between model scores and calibration-set sensitivities.
 *     Ideally, the calibration set should be comprised of a unbiased sample from the full distribution of true sites
 *     in annotation space; the score-sensitivity conversion can roughly be thought of as a mapping from sensitivities in
 *     [0, 1] to a contour of this annotation-space distribution. In practice, any biases in the calibration set (e.g.,
 *     if it consists of high quality, previously filtered calls, which may be biased towards the high density regions
 *     of the full distribution) will be reflected in the conversion and should be taken into consideration when
 *     interpreting calibration-set sensitivities.
 * </p>
 *
 * <h3>Inputs</h3>
 *
 * <ul>
 *     <li>
 *         Labeled-annotations HDF5 file (.annot.hdf5). Annotation data and metadata for labeled sites are stored in the
 *         HDF5 directory structure given in the documentation for the {@link ExtractVariantAnnotations} tool. In typical
 *         usage, both the {@value LabeledVariantAnnotationsData#TRAINING_LABEL} and
 *         {@value LabeledVariantAnnotationsData#CALIBRATION_LABEL} labels would be available for non-empty sets of
 *         sites of the requested variant type.
 *     </li>
 *     <li>
 *         (Optional) Unlabeled-annotations HDF5 file (.unlabeled.annot.hdf5). Annotation data and metadata for
 *         unlabeled sites are stored in the HDF5 directory structure given in the documentation for the
 *         {@link ExtractVariantAnnotations} tool. If provided, a positive-negative modeling approach (similar to
 *         that used in {@link VariantRecalibrator} will be used.
 *     </li>
 *     <li>
 *         Variant types (i.e., SNP and/or INDEL) for which to train models. Logic for determining variant type was retained from
 *         {@link VariantRecalibrator}; see {@link VariantType}. A separate model will be trained for each variant type
 *         and separate sets of outputs with corresponding tags in the filenames (i.e., "snp" or "indel") will be produced.
 *         Alternatively, the tool can be run twice, once for each variant type; this may be useful if one wishes to use
 *         different argument values or modeling approaches.
 *     </li>
 *     <li>
 *         (Optional) Model backend. The Python isolation-forest backend is currently the default backend.
 *         A custom backend can also be specified in conjunction with the {@value PYTHON_SCRIPT_LONG_NAME} argument.
 *     </li>
 *     <li>
 *         (Optional) Model hyperparameters JSON file. This file can be used to specify backend-specific
 *         hyperparameters in JSON format, which is to be consumed by the modeling script. This is required if a
 *         custom backend is used.
 *     </li>
 *     <li>
 *         (Optional) Calibration-set sensitivity threshold. The same threshold will be used for both SNP and INDEL
 *         variant types. If different thresholds are desired, the tool can be twice, once for each variant type.
 *     </li>
 *     <li>
 *         Output prefix.
 *         This is used as the basename for output files.
 *     </li>
 * </ul>
 *
 * <h3>Outputs</h3>
 *
 * <p>
 *     The following outputs are produced for each variant type specified by the {@value MODE_LONG_NAME} argument
 *     and are delineated by type-specific tags in the filename of each output, which take the form of
 *     {@code {output-prefix}.{variant-type}.{file-suffix}}. For example, scores for the SNP calibration set
 *     will be output to the {@code {output-prefix}.snp.calibrationScores.hdf5} file.
 * </p>
 *
 * <ul>
 *     <li>
 *         Training-set positive-model scores HDF5 file (.trainingScores.hdf5).
 *     </li>
 *     <li>
 *         Positive-model serialized scorer file. (.scorer.pkl for the default {@code PYTHON_IFOREST} model backend).
 *     </li>
 *     <li>
 *         (Optional) Unlabeled-set positive-model scores HDF5 file (.unlabeledScores.hdf5). This is only output
 *         if a positive-negative modeling approach is used.
 *     </li>
 *     <li>
 *         (Optional) Calibration-set scores HDF5 file (.calibrationScores.hdf5). This is only output if a calibration
 *         set is provided. If a positive-only modeling approach is used, scores will be generated from the positive model;
 *         if a positive-negative modeling approach is used, scores will be generated from the positive-negative model.
 *     </li>
 *     <li>
 *         (Optional) Negative-model serialized scorer file. (.negative.scorer.pkl for the default {@code PYTHON_IFOREST} model backend).
 *         This is only output if a positive-negative modeling approach is used.
 *     </li>
 *     <li>
 *         (Optional) TODO BGMM positive/negative fits
 *     </li>
 * </ul>
 *
 * <h3>Usage examples</h3>
 *
 * <p>
 *     Train SNP and INDEL models using the default Python IsolationForest model backend with a positive-only approach,
 *     given an input labeled-annotations HDF5 file generated by {@link ExtractVariantAnnotations} that contains
 *     labels for both training and calibration sets, producing the outputs 1) train.snp.scorer.pkl,
 *     2) train.snp.trainingScores.hdf5, and 3) train.snp.calibrationScores.hdf5, as well as analogous files
 *     for the INDEL model. Note that the {@value MODE_LONG_NAME} arguments are made explicit here, although both
 *     SNP and INDEL modes are selected by default.
 *
 * <pre>
 *     gatk TrainVariantAnnotationsModel \
 *          --annotations-hdf5 extract.annot.hdf5 \
 *          --mode SNP \
 *          --mode INDEL \
 *          -O train
 * </pre>
 * </p>
 *
 * <p>
 *     Train SNP and INDEL models using the default Python IsolationForest model backend with a positive-negative approach
 *     (using a calibration-sensitivity threshold of 0.95 to select sites for training the negative model),
 *     given an input labeled-annotations HDF5 file that contains labels for both training and calibration sets
 *     and an input unlabeled-annotations HDF5 file (with both HDF5 files generated by {@link ExtractVariantAnnotations}),
 *     producing the outputs 1) train.snp.scorer.pkl, 2) train.snp.negative.scorer.pkl, 3) train.snp.trainingScores.hdf5,
 *     4) train.snp.calibrationScores.hdf5, and 5) train.snp.unlabeledScores.hdf5, as well as analogous files
 *     for the INDEL model. Note that the {@value MODE_LONG_NAME} arguments are made explicit here, although both
 *     SNP and INDEL modes are selected by default.
 *
 * <pre>
 *     gatk TrainVariantAnnotationsModel \
 *          --annotations-hdf5 extract.annot.hdf5 \
 *          --unlabeled-annotations-hdf5 extract.unlabeled.annot.hdf5 \
 *          --mode SNP \
 *          --mode INDEL \
 *          --calibration-sensitivity-threshold 0.95 \
 *          -O train
 * </pre>
 * </p>
 *
 * <h3>Custom modeling/scoring backends (ADVANCED)</h3>
 *
 * <p>
 *     The primary modeling functionality performed by this tool is accomplished by a "modeling backend"
 *     whose fundamental contract is to take an input HDF5 file containing an annotation matrix for sites of a
 *     single variant type (i.e., SNP or INDEL) and to output a serialized scorer for that variant type.
 *     Rather than using one of the available, implemented backends, advanced users may provide their own backend
 *     via the {@value PYTHON_SCRIPT_LONG_NAME} argument. See documentation in the modeling and scoring interfaces
 *     ({@link VariantAnnotationsModel} and {@link VariantAnnotationsScorer}, respectively), as well as the default
 *     Python IsolationForest implementation at {@link PythonSklearnVariantAnnotationsModel} and
 *     src/main/resources/org/broadinstitute/hellbender/tools/walkers/vqsr/scalable/isolation-forest.py.
 * </p>
 *
 * <p>
 *     Extremely advanced users could potentially substitute their own implementation for the entire
 *     {@link TrainVariantAnnotationsModel} tool, while still making use of the up/downstream
 *     {@link ExtractVariantAnnotations} and {@link ScoreVariantAnnotations} tools. To do so, one would additionally
 *     have to implement functionality for subsetting training/calibration sets by variant type,
 *     calling modeling backends as appropriate, and scoring calibration sets.
 * </p>
 *
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Trains a model for scoring variant calls based on site-level annotations.",
        oneLineSummary = "Trains a model for scoring variant calls based on site-level annotations",
        programGroup = VariantFilteringProgramGroup.class
)
@DocumentedFeature
@BetaFeature
public final class TrainVariantAnnotationsModel extends CommandLineProgram {

    public static final String MODE_LONG_NAME = "mode";
    public static final String ANNOTATIONS_HDF5_LONG_NAME = "annotations-hdf5";
    public static final String UNLABELED_ANNOTATIONS_HDF5_LONG_NAME = "unlabeled-annotations-hdf5";
    public static final String MODEL_BACKEND_LONG_NAME = "model-backend";
    public static final String PYTHON_SCRIPT_LONG_NAME = "python-script";
    public static final String HYPERPARAMETERS_JSON_LONG_NAME = "hyperparameters-json";
    public static final String CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME = "calibration-sensitivity-threshold";

    public static final String ISOLATION_FOREST_PYTHON_SCRIPT = "isolation-forest.py";
    public static final String ISOLATION_FOREST_HYPERPARAMETERS_JSON = "isolation-forest-hyperparameters.json";

    public static final String BGMM_HYPERPARAMETERS_JSON = "bgmm-hyperparameters.json";

    enum AvailableLabelsMode {
        POSITIVE_ONLY, POSITIVE_UNLABELED
    }

    public static final String TRAINING_SCORES_HDF5_SUFFIX = ".trainingScores.hdf5";
    public static final String CALIBRATION_SCORES_HDF5_SUFFIX = ".calibrationScores.hdf5";
    public static final String UNLABELED_SCORES_HDF5_SUFFIX = ".unlabeledScores.hdf5";
    public static final String NEGATIVE_TAG = ".negative";

    @Argument(
            fullName = ANNOTATIONS_HDF5_LONG_NAME,
            doc = "HDF5 file containing annotations extracted with ExtractVariantAnnotations.")
    private File inputAnnotationsFile;

    @Argument(
            fullName = UNLABELED_ANNOTATIONS_HDF5_LONG_NAME,
            doc = "HDF5 file containing annotations extracted with ExtractVariantAnnotations. " +
                    "If specified with " + CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME + ", " +
                    "a positive-negative modeling approach will be used; otherwise, a positive-only modeling " +
                    "approach will be used.",
            optional = true)
    private File inputUnlabeledAnnotationsFile;

    @Argument(
            fullName = MODEL_BACKEND_LONG_NAME,
            doc = "Backend to use for training models. " +
                    "PYTHON_IFOREST will use the Python scikit-learn implementation of the IsolationForest method and " +
                    "will require that the corresponding Python dependencies are present in the environment. " +
                    "PYTHON_SCRIPT will use the script specified by the " + PYTHON_SCRIPT_LONG_NAME + " argument. " +
                    "JAVA_BGMM will use a pure Java implementation (ported from Python scikit-learn) of the Bayesian Gaussian Mixture Model. " +
                    "See the tool documentation for more details.")
    private VariantAnnotationsModelBackend modelBackend = VariantAnnotationsModelBackend.PYTHON_IFOREST;

    @Argument(
            fullName = PYTHON_SCRIPT_LONG_NAME,
            doc = "Python script used for specifying a custom scoring backend. If provided, " + MODEL_BACKEND_LONG_NAME + " must also be set to PYTHON_SCRIPT.",
            optional = true)
    private File pythonScriptFile;

    @Argument(
            fullName = HYPERPARAMETERS_JSON_LONG_NAME,
            doc = "JSON file containing hyperparameters. Optional if the PYTHON_IFOREST backend is used " +
                    "(if not specified, a default set of hyperparameters will be used); otherwise required.",
            optional = true)
    private File hyperparametersJSONFile;

    @Argument(
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc = "Output prefix.")
    private String outputPrefix;

    @Argument(
            fullName = CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME,
            doc = "Calibration-sensitivity threshold that determines which sites will be used for training the negative model " +
                    "in the positive-negative modeling approach. " +
                    "Increasing this will decrease the corresponding positive-model score threshold; sites with scores below this score " +
                    "threshold will be used for training the negative model. Thus, this parameter should typically be chosen to " +
                    "be close to 1, so that sites that score highly according to the positive model will not be used to train the negative model. " +
                    "The " + UNLABELED_ANNOTATIONS_HDF5_LONG_NAME + " argument must be specified in conjunction with this argument. " +
                    "If separate thresholds for SNP and INDEL models are desired, run the tool separately for each mode with its respective threshold.",
            optional = true,
            minValue = 0.,
            maxValue = 1.)
    private Double calibrationSensitivityThreshold;

    @Argument(
            fullName = MODE_LONG_NAME,
            doc = "Variant types for which to train models. Duplicate values will be ignored.",
            minElements = 1)
    public List<VariantType> variantTypes = new ArrayList<>(Arrays.asList(VariantType.SNP, VariantType.INDEL));

    private AvailableLabelsMode availableLabelsMode;

    @Override
    protected Object doWork() {

        validateArgumentsAndSetModes();

        logger.info("Starting training...");

        for (final VariantType variantType : VariantType.values()) { // enforces order in which models are trained
            if (variantTypes.contains(variantType)) {
                doModelingWorkForVariantType(variantType);
            }
        }

        logger.info(String.format("%s complete.", getClass().getSimpleName()));

        return null;
    }

    private void validateArgumentsAndSetModes() {
        IOUtils.canReadFile(inputAnnotationsFile);

        Utils.validateArg((inputUnlabeledAnnotationsFile == null) == (calibrationSensitivityThreshold == null),
                "Unlabeled annotations and calibration-sensitivity threshold must both be unspecified (for positive-only model training) " +
                        "or specified (for positive-negative model training).");

        availableLabelsMode = inputUnlabeledAnnotationsFile != null && calibrationSensitivityThreshold != null
                ? AvailableLabelsMode.POSITIVE_UNLABELED
                : AvailableLabelsMode.POSITIVE_ONLY;

        if (inputUnlabeledAnnotationsFile != null) {
            IOUtils.canReadFile(inputUnlabeledAnnotationsFile);
            final List<String> annotationNames = LabeledVariantAnnotationsData.readAnnotationNames(inputAnnotationsFile);
            final List<String> unlabeledAnnotationNames = LabeledVariantAnnotationsData.readAnnotationNames(inputUnlabeledAnnotationsFile);
            Utils.validateArg(annotationNames.equals(unlabeledAnnotationNames), "Annotation names must be identical for positive and unlabeled annotations.");
        }

        logger.info(String.format("Running in %s mode...", modelBackend));
        switch (modelBackend) {
            case JAVA_BGMM:
                Utils.validateArg(pythonScriptFile == null,
                        "Python script should not be provided when using JAVA_BGMM backend.");
                if (hyperparametersJSONFile == null) {
                    hyperparametersJSONFile = IOUtils.writeTempResource(new Resource(BGMM_HYPERPARAMETERS_JSON, TrainVariantAnnotationsModel.class));
                }
                IOUtils.canReadFile(hyperparametersJSONFile);
                break;
            case PYTHON_IFOREST:
                Utils.validateArg(pythonScriptFile == null,
                        "Python script should not be provided when using PYTHON_IFOREST backend.");

                pythonScriptFile = IOUtils.writeTempResource(new Resource(ISOLATION_FOREST_PYTHON_SCRIPT, TrainVariantAnnotationsModel.class));
                if (hyperparametersJSONFile == null) {
                    hyperparametersJSONFile = IOUtils.writeTempResource(new Resource(ISOLATION_FOREST_HYPERPARAMETERS_JSON, TrainVariantAnnotationsModel.class));
                }
                IOUtils.canReadFile(hyperparametersJSONFile);
                PythonScriptExecutor.checkPythonEnvironmentForPackage("argparse");
                PythonScriptExecutor.checkPythonEnvironmentForPackage("h5py");
                PythonScriptExecutor.checkPythonEnvironmentForPackage("numpy");
                PythonScriptExecutor.checkPythonEnvironmentForPackage("sklearn");
                PythonScriptExecutor.checkPythonEnvironmentForPackage("dill");
                break;
            case PYTHON_SCRIPT:
                IOUtils.canReadFile(pythonScriptFile);
                IOUtils.canReadFile(hyperparametersJSONFile);
                break;
            default:
                throw new GATKException.ShouldNeverReachHereException("Unknown model-backend mode.");
        }
    }

    /**
     * This method does all modeling and scoring work for a given {@code variantType}. See the tool-level documentation
     * for the steps expected to be performed.
     */
    private void doModelingWorkForVariantType(final VariantType variantType) {
        // positive model
        final List<String> annotationNames = LabeledVariantAnnotationsData.readAnnotationNames(inputAnnotationsFile);
        final double[][] annotations = LabeledVariantAnnotationsData.readAnnotations(inputAnnotationsFile);

        final List<Boolean> isTraining = LabeledVariantAnnotationsData.readLabel(inputAnnotationsFile, LabeledVariantAnnotationsData.TRAINING_LABEL);
        final List<Boolean> isCalibration = LabeledVariantAnnotationsData.readLabel(inputAnnotationsFile, LabeledVariantAnnotationsData.CALIBRATION_LABEL);
        final List<Boolean> isSNP = LabeledVariantAnnotationsData.readLabel(inputAnnotationsFile, LabeledVariantAnnotationsData.SNP_LABEL);
        final List<Boolean> isVariantType = variantType == VariantType.SNP ? isSNP : isSNP.stream().map(x -> !x).collect(Collectors.toList());

        final List<Boolean> isTrainingAndVariantType = Streams.zip(isTraining.stream(), isVariantType.stream(), (a, b) -> a && b).collect(Collectors.toList());
        final int numTrainingAndVariantType = numPassingFilter(isTrainingAndVariantType);

        final String variantTypeString = variantType.toString();
        final String outputPrefixTag = '.' + variantType.toString().toLowerCase();

        if (numTrainingAndVariantType > 0) {
            logger.info(String.format("Training %s model with %d training sites x %d annotations %s...",
                    variantTypeString, numTrainingAndVariantType, annotationNames.size(), annotationNames));
            final File labeledTrainingAndVariantTypeAnnotationsFile = LabeledVariantAnnotationsData.subsetAnnotationsToTemporaryFile(annotationNames, annotations, isTrainingAndVariantType);
            trainAndSerializeModel(labeledTrainingAndVariantTypeAnnotationsFile, outputPrefixTag);
            logger.info(String.format("%s model trained and serialized with output prefix \"%s\".", variantTypeString, outputPrefix + outputPrefixTag));

            logger.info(String.format("Scoring %d %s training sites...", numTrainingAndVariantType, variantTypeString));
            final File labeledTrainingAndVariantTypeScoresFile = score(labeledTrainingAndVariantTypeAnnotationsFile, outputPrefixTag, TRAINING_SCORES_HDF5_SUFFIX);
            logger.info(String.format("%s training scores written to %s.", variantTypeString, labeledTrainingAndVariantTypeScoresFile.getAbsolutePath()));

            final List<Boolean> isLabeledCalibrationAndVariantType = Streams.zip(isCalibration.stream(), isVariantType.stream(), (a, b) -> a && b).collect(Collectors.toList());
            final int numLabeledCalibrationAndVariantType = numPassingFilter(isLabeledCalibrationAndVariantType);
            if (numLabeledCalibrationAndVariantType > 0) {
                logger.info(String.format("Scoring %d %s calibration sites...", numLabeledCalibrationAndVariantType, variantTypeString));
                final File labeledCalibrationAndVariantTypeAnnotationsFile = LabeledVariantAnnotationsData.subsetAnnotationsToTemporaryFile(annotationNames, annotations, isLabeledCalibrationAndVariantType);
                final File labeledCalibrationAndVariantTypeScoresFile = score(labeledCalibrationAndVariantTypeAnnotationsFile, outputPrefixTag, CALIBRATION_SCORES_HDF5_SUFFIX);
                logger.info(String.format("%s calibration scores written to %s.", variantTypeString, labeledCalibrationAndVariantTypeScoresFile.getAbsolutePath()));
            } else {
                logger.warn(String.format("No %s calibration sites were available.", variantTypeString));
            }

            // negative model
            if (availableLabelsMode == AvailableLabelsMode.POSITIVE_UNLABELED) {
                if (numLabeledCalibrationAndVariantType == 0) {
                    throw new UserException.BadInput(String.format("Attempted to train %s negative model, " +
                            "but no suitable calibration sites were found in the provided annotations.", variantTypeString));
                }
                final double[][] unlabeledAnnotations = LabeledVariantAnnotationsData.readAnnotations(inputUnlabeledAnnotationsFile);
                final List<Boolean> unlabeledIsSNP = LabeledVariantAnnotationsData.readLabel(inputUnlabeledAnnotationsFile, "snp");
                final List<Boolean> isUnlabeledVariantType = variantType == VariantType.SNP ? unlabeledIsSNP : unlabeledIsSNP.stream().map(x -> !x).collect(Collectors.toList());

                final int numUnlabeledVariantType = numPassingFilter(isUnlabeledVariantType);

                if (numUnlabeledVariantType > 0) {
                    final File labeledCalibrationAndVariantTypeScoresFile = new File(outputPrefix + outputPrefixTag + CALIBRATION_SCORES_HDF5_SUFFIX);
                    final double[] labeledCalibrationAndVariantTypeScores = VariantAnnotationsScorer.readScores(labeledCalibrationAndVariantTypeScoresFile);
                    final double scoreThreshold = calibrationSensitivityThreshold == 1. // Percentile requires quantile > 0, so we treat this as a special case
                            ? Doubles.min(labeledCalibrationAndVariantTypeScores)
                            : new Percentile(100. * (1. - calibrationSensitivityThreshold)).evaluate(labeledCalibrationAndVariantTypeScores);
                    logger.info(String.format("Using %s score threshold of %.4f corresponding to specified calibration-sensitivity threshold of %.4f ...",
                            variantTypeString, scoreThreshold, calibrationSensitivityThreshold));

                    final double[] labeledTrainingAndVariantTypeScores = VariantAnnotationsScorer.readScores(labeledTrainingAndVariantTypeScoresFile);
                    final List<Boolean> isNegativeTrainingFromLabeledTrainingAndVariantType = Arrays.stream(labeledTrainingAndVariantTypeScores).boxed().map(s -> s < scoreThreshold).collect(Collectors.toList());
                    final int numNegativeTrainingFromLabeledTrainingAndVariantType = numPassingFilter(isNegativeTrainingFromLabeledTrainingAndVariantType);
                    logger.info(String.format("Selected %d labeled %s sites below score threshold of %.4f for negative-model training...",
                            numNegativeTrainingFromLabeledTrainingAndVariantType, variantTypeString, scoreThreshold));

                    logger.info(String.format("Scoring %d unlabeled %s sites...", numUnlabeledVariantType, variantTypeString));
                    final File unlabeledVariantTypeAnnotationsFile = LabeledVariantAnnotationsData.subsetAnnotationsToTemporaryFile(annotationNames, unlabeledAnnotations, isUnlabeledVariantType);
                    final File unlabeledVariantTypeScoresFile = score(unlabeledVariantTypeAnnotationsFile, outputPrefixTag, UNLABELED_SCORES_HDF5_SUFFIX);
                    final double[] unlabeledVariantTypeScores = VariantAnnotationsScorer.readScores(unlabeledVariantTypeScoresFile);
                    final List<Boolean> isNegativeTrainingFromUnlabeledVariantType = Arrays.stream(unlabeledVariantTypeScores).boxed().map(s -> s < scoreThreshold).collect(Collectors.toList()); // length matches unlabeledAnnotationsFile
                    final int numNegativeTrainingFromUnlabeledVariantType = numPassingFilter(isNegativeTrainingFromUnlabeledVariantType);
                    logger.info(String.format("Selected %d unlabeled %s sites below score threshold of %.4f for negative-model training...",
                            numNegativeTrainingFromUnlabeledVariantType, variantTypeString, scoreThreshold));

                    final double[][] negativeTrainingAndVariantTypeAnnotations = concatenateLabeledAndUnlabeledNegativeTrainingData(
                            annotationNames, annotations, unlabeledAnnotations, isNegativeTrainingFromLabeledTrainingAndVariantType, isNegativeTrainingFromUnlabeledVariantType);
                    final int numNegativeTrainingAndVariantType = negativeTrainingAndVariantTypeAnnotations.length;
                    final List<Boolean> isNegativeTrainingAndVariantType = Collections.nCopies(numNegativeTrainingAndVariantType, true);

                    logger.info(String.format("Training %s negative model with %d negative-training sites x %d annotations %s...",
                            variantTypeString, numNegativeTrainingAndVariantType, annotationNames.size(), annotationNames));
                    final File negativeTrainingAnnotationsFile = LabeledVariantAnnotationsData.subsetAnnotationsToTemporaryFile(
                            annotationNames, negativeTrainingAndVariantTypeAnnotations, isNegativeTrainingAndVariantType);
                    trainAndSerializeModel(negativeTrainingAnnotationsFile, outputPrefixTag + NEGATIVE_TAG);
                    logger.info(String.format("%s negative model trained and serialized with output prefix \"%s\".", variantTypeString, outputPrefix + outputPrefixTag + NEGATIVE_TAG));

                    logger.info(String.format("Re-scoring %d %s calibration sites...", numLabeledCalibrationAndVariantType, variantTypeString));
                    final File labeledCalibrationAnnotationsFile = LabeledVariantAnnotationsData.subsetAnnotationsToTemporaryFile(annotationNames, annotations, isLabeledCalibrationAndVariantType);
                    final File labeledCalibrationScoresFile = positiveNegativeScore(labeledCalibrationAnnotationsFile, outputPrefixTag, CALIBRATION_SCORES_HDF5_SUFFIX);
                    logger.info(String.format("Calibration scores written to %s.", labeledCalibrationScoresFile.getAbsolutePath()));
                } else {
                    throw new UserException.BadInput(String.format("Attempted to train %s negative model, " +
                            "but no suitable unlabeled sites were found in the provided annotations.", variantTypeString));
                }
            }
        } else {
            throw new UserException.BadInput(String.format("Attempted to train %s model, " +
                    "but no suitable training sites were found in the provided annotations.", variantTypeString));
        }
    }

    private static int numPassingFilter(final List<Boolean> isPassing) {
        return (int) isPassing.stream().filter(x -> x).count();
    }

    private void trainAndSerializeModel(final File trainingAnnotationsFile,
                                        final String outputPrefixTag) {
        readAndValidateTrainingAnnotations(trainingAnnotationsFile, outputPrefixTag);
        final VariantAnnotationsModel model;
        switch (modelBackend) {
            case JAVA_BGMM:
                model = new BGMMVariantAnnotationsModel(hyperparametersJSONFile);
                break;
            case PYTHON_IFOREST:
                model = new PythonSklearnVariantAnnotationsModel(pythonScriptFile, hyperparametersJSONFile);
                break;
            case PYTHON_SCRIPT:
                model = new PythonSklearnVariantAnnotationsModel(pythonScriptFile, hyperparametersJSONFile);
                break;
            default:
                throw new GATKException.ShouldNeverReachHereException("Unknown model mode.");
        }
        model.trainAndSerialize(trainingAnnotationsFile, outputPrefix + outputPrefixTag);
    }

    /**
     * When training models on data that has been subset to a given variant type,
     * we FAIL if any annotation is completely missing and WARN if any annotation has zero variance.
     */
    private void readAndValidateTrainingAnnotations(final File trainingAnnotationsFile,
                                                    final String outputPrefixTag) {
        final List<String> annotationNames = LabeledVariantAnnotationsData.readAnnotationNames(trainingAnnotationsFile);
        final double[][] annotations = LabeledVariantAnnotationsData.readAnnotations(trainingAnnotationsFile);

        // these checks are redundant, but we err on the side of robustness
        final int numAnnotationNames = annotationNames.size();
        final int numData = annotations.length;
        Utils.validateArg(numAnnotationNames > 0, "Number of annotation names must be positive.");
        Utils.validateArg(numData > 0, "Number of data points must be positive.");
        final int numFeatures = annotations[0].length;
        Utils.validateArg(numAnnotationNames == numFeatures,
                "Number of annotation names must match the number of features in the annotation data.");

        final List<String> completelyMissingAnnotationNames = new ArrayList<>(numFeatures);
        IntStream.range(0, numFeatures).forEach(
                i -> {
                    if (new Variance().evaluate(IntStream.range(0, numData).mapToDouble(n -> annotations[n][i]).toArray()) == 0.) {
                        logger.warn(String.format("All values of the annotation %s are identical in the training data for the %s model.",
                                annotationNames.get(i), outputPrefix + outputPrefixTag));
                    }
                    if (IntStream.range(0, numData).boxed().map(n -> annotations[n][i]).allMatch(x -> Double.isNaN(x))) {
                        completelyMissingAnnotationNames.add(annotationNames.get(i));
                    }
                }
        );

        if (!completelyMissingAnnotationNames.isEmpty()) {
            throw new UserException.BadInput(
                    String.format("All values of the following annotations are missing in the training data for the %s model: %s. " +
                                    "Consider repeating the extraction step with this annotation dropped. " +
                                    "If this is a negative model and the amount of negative training data is small, " +
                                    "perhaps also consider lowering the value of the %s argument so that more " +
                                    "training data is considered, which may ultimately admit data with non-missing values for the annotation " +
                                    "(although note that this will also have implications for the resulting model fit); " +
                                    "alternatively, consider excluding the %s and %s arguments and running positive-only modeling.",
                            outputPrefix + outputPrefixTag, completelyMissingAnnotationNames,
                            CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME, UNLABELED_ANNOTATIONS_HDF5_LONG_NAME, CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME));
        }
    }

    private File score(final File annotationsFile,
                       final String outputPrefixTag,
                       final String outputSuffix) {
        final VariantAnnotationsScorer scorer;
        switch (modelBackend) {
            case JAVA_BGMM:
                scorer = BGMMVariantAnnotationsScorer.deserialize(new File(outputPrefix + outputPrefixTag + BGMMVariantAnnotationsScorer.BGMM_SCORER_SER_SUFFIX));
                break;
            case PYTHON_IFOREST:
            case PYTHON_SCRIPT:
                scorer = new PythonSklearnVariantAnnotationsScorer(pythonScriptFile, new File(outputPrefix + outputPrefixTag + PythonSklearnVariantAnnotationsScorer.PYTHON_SCORER_PKL_SUFFIX));
                break;

            default:
                throw new GATKException.ShouldNeverReachHereException("Unknown model mode.");
        }
        final File outputScoresFile = new File(outputPrefix + outputPrefixTag + outputSuffix);
        scorer.score(annotationsFile, outputScoresFile);
        return outputScoresFile;
    }

    private File positiveNegativeScore(final File annotationsFile,
                                       final String outputPrefixTag,
                                       final String outputSuffix) {
        final VariantAnnotationsScorer scorer;
        switch (modelBackend) {
            case JAVA_BGMM:
                scorer = VariantAnnotationsScorer.combinePositiveAndNegativeScorer(
                        BGMMVariantAnnotationsScorer.deserialize(new File(outputPrefix + outputPrefixTag + BGMMVariantAnnotationsScorer.BGMM_SCORER_SER_SUFFIX)),
                        BGMMVariantAnnotationsScorer.deserialize(new File(outputPrefix + outputPrefixTag + NEGATIVE_TAG + BGMMVariantAnnotationsScorer.BGMM_SCORER_SER_SUFFIX)));
                break;
            case PYTHON_IFOREST:
            case PYTHON_SCRIPT:
                scorer = VariantAnnotationsScorer.combinePositiveAndNegativeScorer(
                        new PythonSklearnVariantAnnotationsScorer(pythonScriptFile, new File(outputPrefix + outputPrefixTag + PythonSklearnVariantAnnotationsScorer.PYTHON_SCORER_PKL_SUFFIX)),
                        new PythonSklearnVariantAnnotationsScorer(pythonScriptFile, new File(outputPrefix + outputPrefixTag + NEGATIVE_TAG + PythonSklearnVariantAnnotationsScorer.PYTHON_SCORER_PKL_SUFFIX)));
                break;
            default:
                throw new GATKException.ShouldNeverReachHereException("Unknown model mode.");
        }
        final File outputScoresFile = new File(outputPrefix + outputPrefixTag + outputSuffix);
        scorer.score(annotationsFile, outputScoresFile);
        return outputScoresFile;
    }

    /**
     * This should be robust to cases in which there are no sites with scores below the negative-training threshold
     * in either the labeled or unlabeled annotations.
     */
    private static double[][] concatenateLabeledAndUnlabeledNegativeTrainingData(final List<String> annotationNames,
                                                                                 final double[][] annotations,
                                                                                 final double[][] unlabeledAnnotations,
                                                                                 final List<Boolean> isNegativeTrainingFromLabeledTrainingAndVariantType,
                                                                                 final List<Boolean> isNegativeTrainingFromUnlabeledVariantType) {
        final int numNegativeTrainingFromLabeledTrainingAndVariantType = numPassingFilter(isNegativeTrainingFromLabeledTrainingAndVariantType);
        final int numNegativeTrainingFromUnlabeledVariantType = numPassingFilter(isNegativeTrainingFromUnlabeledVariantType);
        Utils.validateArg(numNegativeTrainingFromLabeledTrainingAndVariantType + numNegativeTrainingFromUnlabeledVariantType > 0,
                String.format("No sites below the specified score threshold were available for negative-model training. " +
                        "Consider using a positive-only modeling approach or adjusting the value of the %s argument.", CALIBRATION_SENSITIVITY_THRESHOLD_LONG_NAME));
        final double[][] negativeTrainingFromLabeledTrainingAndVariantTypeAnnotations = subsetAnnotationsViaTemporaryFile(annotationNames, annotations, isNegativeTrainingFromLabeledTrainingAndVariantType);
        final double[][] negativeTrainingFromUnlabeledVariantTypeAnnotations = subsetAnnotationsViaTemporaryFile(annotationNames, unlabeledAnnotations, isNegativeTrainingFromUnlabeledVariantType);
        return Streams.concat(
                Arrays.stream(negativeTrainingFromLabeledTrainingAndVariantTypeAnnotations),
                Arrays.stream(negativeTrainingFromUnlabeledVariantTypeAnnotations)).toArray(double[][]::new);
    }

    private static double[][] subsetAnnotationsViaTemporaryFile(final List<String> annotationNames,
                                                                final double[][] annotations,
                                                                final List<Boolean> isSubset) {
        if (isSubset.stream().noneMatch(x -> x)) {
            return new double[0][annotationNames.size()];
        }
        final File subsetAnnotationsFile = LabeledVariantAnnotationsData.subsetAnnotationsToTemporaryFile(annotationNames, annotations, isSubset);
        return LabeledVariantAnnotationsData.readAnnotations(subsetAnnotationsFile);
    }
}