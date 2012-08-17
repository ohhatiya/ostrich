package com.bazaarvoice.soa.metrics;

public class GaugeDescriptor extends OstrichMetricsDescriptor {
    public GaugeDescriptor(String name) {
        super(name, MetricType.GAUGE);
    }
}