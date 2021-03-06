package com.github.kristofa.brave;

import com.github.kristofa.brave.SpanAndEndpoint.LocalSpanAndEndpoint;
import com.google.auto.value.AutoValue;
import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Span;
import com.twitter.zipkin.gen.zipkinCoreConstants;

import java.util.Random;

import static com.twitter.zipkin.gen.zipkinCoreConstants.LOCAL_COMPONENT;

/**
 * Local tracer is designed for in-process activity that explains latency.
 *
 * <p/>For example, a local span could represent bootstrap, codec, file i/o or
 * other activity that notably impacts performance.
 *
 * <p/>Local spans always have a binary annotation "lc" which indicates the
 * component name. Usings zipkin's UI or Api, you can query by for spans that
 * use a component like this: {@code lc=spring-boot}.
 *
 * <p/>Here's an example of allocating precise duration for a local span:
 * <pre>
 * tracer.startNewSpan("codec", "encode");
 * try {
 *   return codec.encode(input);
 * } finally {
 *   tracer.finishSpan();
 * }
 * </pre>
 *
 * @see zipkinCoreConstants#LOCAL_COMPONENT
 */
@AutoValue
public abstract class LocalTracer extends AnnotationSubmitter {

    static Builder builder() {
        return new AutoValue_LocalTracer.Builder();
    }

    // visible for testing
    static Builder builder(LocalTracer source) {
        return new AutoValue_LocalTracer.Builder(source);
    }

    @Override
    abstract LocalSpanAndEndpoint spanAndEndpoint();

    abstract Random randomGenerator();

    abstract SpanCollector spanCollector();

    abstract Sampler traceSampler();

    @AutoValue.Builder
    abstract static class Builder {

        abstract Builder spanAndEndpoint(LocalSpanAndEndpoint spanAndEndpoint);

        abstract Builder randomGenerator(Random randomGenerator);

        abstract Builder spanCollector(SpanCollector spanCollector);

        abstract Builder traceSampler(Sampler sampler);

        abstract LocalTracer build();
    }

    /**
     * Request a new local span, which starts now.
     *
     * @param component {@link zipkinCoreConstants#LOCAL_COMPONENT component} responsible for the operation
     * @param operation name of the operation that's begun
     * @return metadata about the new span or null if one wasn't started due to sampling policy.
     * @see zipkinCoreConstants#LOCAL_COMPONENT
     */
    public SpanId startNewSpan(String component, String operation) {
        SpanId spanId = startNewSpan(component, operation, currentTimeMicroseconds());
        if (spanId == null) return null;
        spanAndEndpoint().span().startTick = System.nanoTime(); // embezzle start tick into an internal field.
        return spanId;
    }

    private SpanId getNewSpanId() {
        Span currentServerSpan = spanAndEndpoint().state().getCurrentServerSpan().getSpan();
        long newSpanId = randomGenerator().nextLong();
        if (currentServerSpan == null) {
            return SpanId.create(newSpanId, newSpanId, null);
        }

        return SpanId.create(currentServerSpan.getTrace_id(), newSpanId, currentServerSpan.getId());
    }

    /**
     * Request a new local span, which started at the given timestamp.
     *
     * @param component {@link zipkinCoreConstants#LOCAL_COMPONENT component} responsible for the operation
     * @param operation name of the operation that's begun
     * @param timestamp time the operation started, in epoch microseconds.
     * @return metadata about the new span or null if one wasn't started due to sampling policy.
     * @see zipkinCoreConstants#LOCAL_COMPONENT
     */
    public SpanId startNewSpan(String component, String operation, long timestamp) {

        Boolean sample = spanAndEndpoint().state().sample();
        if (Boolean.FALSE.equals(sample)) {
            spanAndEndpoint().state().setCurrentLocalSpan(null);
            return null;
        }

        SpanId newSpanId = getNewSpanId();
        if (sample == null) {
            // No sample indication is present.
            if (!traceSampler().isSampled(newSpanId.getTraceId())) {
                spanAndEndpoint().state().setCurrentLocalSpan(null);
                return null;
            }
        }

        Span newSpan = new Span();
        newSpan.setId(newSpanId.getSpanId());
        newSpan.setTrace_id(newSpanId.getTraceId());
        if (newSpanId.getParentSpanId() != null) {
            newSpan.setParent_id(newSpanId.getParentSpanId());
        }
        newSpan.setName(operation);
        newSpan.setTimestamp(timestamp);
        newSpan.addToBinary_annotations(
            BinaryAnnotation.create(LOCAL_COMPONENT, component, spanAndEndpoint().endpoint()));
        spanAndEndpoint().state().setCurrentLocalSpan(newSpan);
        return newSpanId;
    }

    /**
     * Completes the span, assigning the most precise duration possible.
     */
    public void finishSpan() {
        long endTick = System.nanoTime();

        Span span = spanAndEndpoint().span();
        if (span == null) return;

        Long startTick = span.startTick;
        final long duration;
        if (startTick != null) {
            duration = (endTick - startTick) / 1000;
        } else {
            duration = currentTimeMicroseconds() - span.getTimestamp();
        }
        finishSpan(duration);
    }

    /**
     * Completes the span, which took {@code duration} microseconds.
     */
    public void finishSpan(long duration) {
        Span span = spanAndEndpoint().span();
        if (span == null) return;

        synchronized (span) {
            span.setDuration(duration);
            spanCollector().collect(span);
        }

        spanAndEndpoint().state().setCurrentLocalSpan(null);
    }

    LocalTracer() {
    }
}
