package com.bazaarvoice.soa.metrics;

public abstract class OstrichMetricsDescriptor {
    private final String _name;
    private final MetricType _metricType;

    OstrichMetricsDescriptor(String name, MetricType metricType) {
        _name = name;
        _metricType = metricType;
    }

    public String getName() {
        return _name;
    }

    public MetricType getType() {
        return _metricType;
    }
}
