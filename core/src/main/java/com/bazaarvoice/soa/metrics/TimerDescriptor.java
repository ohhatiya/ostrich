package com.bazaarvoice.soa.metrics;

import java.util.concurrent.TimeUnit;

public class TimerDescriptor extends OstrichMetricsDescriptor {
    private final TimeUnit _durationUnit;
    private final TimeUnit _rateUnit;

    public TimerDescriptor(String name, TimeUnit durationUnit, TimeUnit rateUnit) {
        super(name, MetricType.TIMER);
        _durationUnit = durationUnit;
        _rateUnit = rateUnit;
    }

    public TimeUnit getDurationUnit() {
        return _durationUnit;
    }

    public TimeUnit getRateUnit() {
        return _rateUnit;
    }
}