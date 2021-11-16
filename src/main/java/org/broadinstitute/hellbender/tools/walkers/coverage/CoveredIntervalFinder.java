package org.broadinstitute.hellbender.tools.walkers.coverage;

import htsjdk.samtools.util.Locatable;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.tsv.SimpleXSVWriter;
import picard.cmdline.programgroups.ReferenceProgramGroup;

import java.io.IOException;
import java.util.Arrays;


@DocumentedFeature
@CommandLineProgramProperties(
        oneLineSummary = "Walk through loci and count coverage.",
        summary = "Walk bam and look at coverage by position.",
        programGroup = ReferenceProgramGroup.class
)

public class CoveredIntervalFinder extends LocusWalker {

    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME, doc = "Output bed file", common = false)
    private GATKPath outputFile = null;

    @Argument(fullName = "minimum-depth", doc = "Depth of non-filtered reads to consider sufficient to include in the interval list", optional = true)
    public int minimumCoverage = 1;

    SimpleXSVWriter outputWriter = null;


    //traversal records
    Locatable previousLoc = null;
    int startPosition = -1;



    @Override
    public void onTraversalStart() {
        try {
            outputWriter = new SimpleXSVWriter(outputFile.toPath(), '\t');
            outputWriter.setHeaderLine(Arrays.asList(new String[]{"chrom","chromStart","chromEnd"}), false);
        } catch (IOException e) {
            throw new UserException.BadInput("Could not write to path: " + outputFile);
        }
    }


    @Override
    public void apply(AlignmentContext alignmentContext, ReferenceContext referenceContext, FeatureContext featureContext) {

        Locatable currentLoc = alignmentContext.getLocation();

        boolean sufficientDepth = alignmentContext.getBasePileup().size() >= minimumCoverage;

        // if this locus is not contiguous with the previous sufficiently covered locus.
        if (previousLoc == null
                // Checking for contiguity
                || !previousLoc.getContig().equals(currentLoc.getContig())
                || previousLoc.getEnd() != currentLoc.getStart() - 1
                || !sufficientDepth) {
            if (startPosition != -1) {
                outputWriter.getNewLineBuilder().setRow(new String[]{previousLoc.getContig(), Integer.toString(startPosition - 1), Integer.toString(previousLoc.getEnd())}).write();
                startPosition = -1;
            }
        }

        if (sufficientDepth && startPosition == -1) {
            startPosition = currentLoc.getStart();
        }

        previousLoc = currentLoc;
    }

    @Override
    public Object onTraversalSuccess() {
        outputWriter.getNewLineBuilder().setRow(new String[]{previousLoc.getContig(), Integer.toString(startPosition - 1), Integer.toString(previousLoc.getEnd())}).write();
        return super.onTraversalSuccess();
    }

    @Override
    public void closeTool() {
        try {
            outputWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
