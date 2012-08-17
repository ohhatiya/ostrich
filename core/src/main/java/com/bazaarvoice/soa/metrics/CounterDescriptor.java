package com.bazaarvoice.soa.metrics;

public class CounterDescriptor extends OstrichMetricsDescriptor {
    public CounterDescriptor(String name) {
        super(name, MetricType.COUNTER);
    }
}