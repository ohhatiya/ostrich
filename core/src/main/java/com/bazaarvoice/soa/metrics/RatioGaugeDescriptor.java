package com.bazaarvoice.soa.metrics;

public class RatioGaugeDescriptor<N extends Enum<N> & Described, D extends Enum<D> & Described>
        extends OstrichMetricsDescriptor {
    private final N _numerator;
    private final D _denominator;
    private final boolean _percentage;

    public RatioGaugeDescriptor(String name, N numerator, D denominator,
                                boolean asPercentage) {
        super(name, asPercentage ? MetricType.PERCENT_GAUGE : MetricType.RATIO_GAUGE);
        _numerator = numerator;
        _denominator = denominator;
        _percentage = asPercentage;
    }

    public N getNumeratorMetric() {
        return _numerator;
    }

    public D getDenominatorMetric() {
        return _denominator;
    }

    public boolean isPercentage() {
        return _percentage;
    }
}