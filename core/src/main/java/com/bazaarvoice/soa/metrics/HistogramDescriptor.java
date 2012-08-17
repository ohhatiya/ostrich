package com.bazaarvoice.soa.metrics;

public class HistogramDescriptor extends OstrichMetricsDescriptor {
    private final boolean _biased;

    public HistogramDescriptor(String name, boolean biased) {
        super(name, MetricType.HISTOGRAM);
        _biased = biased;
    }

    public boolean isBiased() {
        return _biased;
    }
}