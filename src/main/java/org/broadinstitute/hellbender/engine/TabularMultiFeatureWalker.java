package org.broadinstitute.hellbender.engine;

import htsjdk.tribble.Feature;
import org.broadinstitute.hellbender.engine.filters.CountingReadFilter;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.SimpleInterval;

import java.util.*;

/**
 * A TabularMultiFeatureWalker is a base class for a tool that processes one {@link Feature} at a
 * time from each of multiple sources of Features.  I.e., the sources represent columns in a matrix
 * of Features.
 * It is an error if the sources contain different numbers of Features.
 *
 * To use this walker you need only implement the abstract
 * apply(List<F>, List<Object>, ReadsContext, ReferenceContext) method in a class that declares
 * a collection of FeatureInputs as an argument.
 * You may optionally implement {@link #onTraversalStart()}, {@link #onTraversalSuccess()},
 * and/or {@link #closeTool()}.
 */
public abstract class TabularMultiFeatureWalker<F extends Feature> extends MultiFeatureWalker {
    /**
     * {@inheritDoc}
     *
     * Implementation of Feature-based traversal where the Features from the various sources are
     * presented together, one from each source.
     *
     * NOTE: You should only override {@link #traverse()} if you are writing a new walker base class
     * in the engine package that extends this class. It is not meant to be overridden by tools
     * outside the engine package.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void traverse() {
        final Set<FeatureInput<? extends Feature>> inputs = features.getAllInputs();
        final int nSources = inputs.size();
        final List<Object> headers = new ArrayList<>(nSources);
        final List<Iterator<F>> iterators = new ArrayList<>(nSources);
        for ( final FeatureInput<? extends Feature> input : inputs ) {
            iterators.add((Iterator<F>)features.getFeatureIterator(input, userIntervals));
            headers.add(features.getHeader(input));
        }
        final CountingReadFilter readFilter = makeReadFilter();
        while ( iterators.get(0).hasNext() ) {
            final List<F> features = new ArrayList<>(nSources);
            for ( final Iterator<F> iterator : iterators ) {
                if ( !iterator.hasNext() ) {
                    throw new UserException("Feature sources do not have the same number of items.");
                }
                features.add(iterator.next());
            }
            final SimpleInterval featureInterval = new SimpleInterval(features.get(0));
            apply(features,
                    headers,
                    new ReadsContext(reads, featureInterval, readFilter),
                    new ReferenceContext(reference, featureInterval));
            progressMeter.update(features.get(0));
        }
        for ( final Iterator<F> iterator : iterators ) {
            if ( iterator.hasNext() ) {
                throw new UserException("Feature sources do not have the same number of items.");
            }
        }
    }

    public abstract void apply( final List<F> features,
                                final List<Object> headers,
                                final ReadsContext readsContext,
                                final ReferenceContext referenceContext );
}
