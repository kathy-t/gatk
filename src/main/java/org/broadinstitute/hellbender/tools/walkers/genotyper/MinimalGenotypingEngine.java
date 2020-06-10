package org.broadinstitute.hellbender.tools.walkers.genotyper;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.haplotypecaller.GenotypePriorCalculator;
import org.broadinstitute.hellbender.tools.haplotypecaller.SimpleGenotypePriorCalculator;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.genotyper.SampleList;
import org.broadinstitute.hellbender.utils.pairhmm.DragstrParams;
import org.broadinstitute.hellbender.utils.pairhmm.DragstrReadSTRAnalizer;
import org.broadinstitute.hellbender.utils.pairhmm.DragstrReferenceSTRs;
import org.broadinstitute.hellbender.utils.pairhmm.DragstrUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVariantContextUtils;

import java.util.Collections;


/**
 * A stripped-down version of the former UnifiedGenotyper's genotyping strategy implementation,
 * used only by the HaplotypeCaller for its isActive() determination. Should not be used for
 * any other purpose!
 */
public final class MinimalGenotypingEngine extends GenotypingEngine<StandardCallerArgumentCollection> {

    private final DragstrParams dragstrParams;

    private ReferenceContext referenceContext;

    /**
     * Creates a new genotyping engine given the configuration parameters and the targeted set of samples
     *
     * @param configuration the configuration.
     * @param samples list of samples
     */
    public MinimalGenotypingEngine(final StandardCallerArgumentCollection configuration, final SampleList samples) {
        this(configuration, samples, false);
    }

    /**
     * Creates a new genotyping engine given the configuration parameters and the targeted set of samples
     *
     * @param configuration the configuration.
     * @param samples list of samples
     * @param doAlleleSpecificCalcs Whether to calculate genotyping annotations needed for allele specific annotations
     */
    public MinimalGenotypingEngine(final StandardCallerArgumentCollection configuration, final SampleList samples, boolean doAlleleSpecificCalcs ) {
        super(configuration, samples, doAlleleSpecificCalcs);
        dragstrParams = null;
    }

    /**
     * Creates a new genotyping engine given the configuration parameters and the targeted set of samples
     *
     * @param configuration the configuration.
     * @param samples list of samples
     * @param doAlleleSpecificCalcs Whether to calculate genotyping annotations needed for allele specific annotations
     */
    public MinimalGenotypingEngine(final StandardCallerArgumentCollection configuration, final SampleList samples, boolean doAlleleSpecificCalcs, final DragstrParams dragstrParams) {
        super(configuration, samples, doAlleleSpecificCalcs);
        this.dragstrParams = dragstrParams;
    }

    @Override
    protected boolean forceKeepAllele(final Allele allele) {
        return configuration.annotateAllSitesWithPLs;
    }

    @Override
    protected String callSourceString() {
        return "UG_call";
    }

    @Override
    public VariantContext calculateGenotypes(final VariantContext vc) {
        if (dragstrParams == null || getConfiguration().genotypeArgs.dontUseDragstrPriors || !GATKVariantContextUtils.containsInlineIndel(vc) || referenceContext == null) {
            final SimpleGenotypePriorCalculator gpc = SimpleGenotypePriorCalculator.assumingHW(configuration.genotypeArgs);
            return calculateGenotypes(vc, gpc, Collections.emptyList());
        } else {
            final SimpleInterval interval = new SimpleInterval(vc.getContig(), Math.max(1, vc.getStart() - dragstrParams.maximumPeriod() * dragstrParams.maximumRepeats()), vc.getStart() - dragstrParams.maximumPeriod() * dragstrParams.maximumRepeats());

            final byte[] bases = referenceContext.getBases(interval);
            final int startOffset = vc.getStart() - interval.getStart();
            final DragstrReferenceSTRs analyzer = DragstrReferenceSTRs.of(bases, startOffset, startOffset + 1, dragstrParams.maximumPeriod());
            final int period = analyzer.period(startOffset);
            final int repeats = analyzer.repeatLength(startOffset);

            final GenotypePriorCalculator gpc = SimpleGenotypePriorCalculator.givenDragstrParams(dragstrParams, period, repeats, Math.log10(getConfiguration().genotypeArgs.snpHeterozygosity), 2.0);
            return  calculateGenotypes(vc, gpc, Collections.emptyList());
        }
    }

    public void setReferenceContext(final ReferenceContext ref) {
        referenceContext = ref;
    }

}
