package com.bazaarvoice.soa.metrics;

import java.util.concurrent.TimeUnit;

public class MeterDescriptor extends OstrichMetricsDescriptor {
    private final String _eventType;
    private final TimeUnit _rateUnit;

    public MeterDescriptor(String name, String eventType, TimeUnit rateUnit) {
        super(name, MetricType.METER);
        _eventType = eventType;
        _rateUnit = rateUnit;
    }

    public String getEventType() {
        return _eventType;
    }

    public TimeUnit getRateUnit() {
        return _rateUnit;
    }
}