package com.bazaarvoice.soa.metrics;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.util.PercentGauge;
import com.yammer.metrics.util.RatioGauge;

import java.util.EnumMap;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class OstrichMetrics<E extends Enum & Described> {
    private final Class<?> _class;
    private final String _uniqueScope;
    private final EnumMap<E, OstrichMetric> _metrics;

    public static abstract class OstrichMetric<T extends Number> {
        abstract public <N extends Number> void update(N value);

        public void update() {
            update(1);
        }

        abstract public T getCount();
    }

    public OstrichMetric getMetric(E metric) {
        checkNotNull(metric);
        return _metrics.get(metric);
    }

    private OstrichMetrics(Class type, String uniqueScope, Class<E> enumType) {
        _class = checkNotNull(type);
        _uniqueScope = checkNotNull(uniqueScope);
        _metrics = Maps.newEnumMap(enumType);
        for (E metric : enumType.getEnumConstants()) {
            _metrics.put(metric, newMetric(metric.getDescription()));
        }
    }

    private OstrichMetric newMetric(OstrichMetricsDescriptor description) {
        switch (description.getType()) {
            case GAUGE:
                return newGauge((GaugeDescriptor) description);
            case RATIO_GAUGE:
                return newRatioGauge((RatioGaugeDescriptor<E, E>) description);
            case PERCENT_GAUGE:
                return newRatioGauge((RatioGaugeDescriptor<E, E>) description);
            case COUNTER:
                return newCounter((CounterDescriptor) description);
            case METER:
                return newMeter((MeterDescriptor) description);
            case HISTOGRAM:
                return newHistogram((HistogramDescriptor) description);
            case TIMER:
                return newTimer((TimerDescriptor) description);
            default:
                return newAtomicGauge(description);
        }
    }

    private OstrichMetric<Long> newAtomicGauge(OstrichMetricsDescriptor descriptor) {
        final OstrichMetric<Long> metric = new OstrichMetric<Long>() {
            private volatile long _value;

            @Override
            public <N extends Number> void update(N value) {
                _value = value.longValue();
            }

            @Override
            public void update() {
                // Do nothing
            }

            @Override
            public Long getCount() {
                return _value;
            }
        };

        Metrics.newGauge(_class, descriptor.getName(), _uniqueScope, new Gauge<Long>() {
            @Override
            public Long value() {
                return metric.getCount();
            }
        });

        return metric;
    }

    private OstrichMetric<Long> newGauge(GaugeDescriptor descriptor) {
        return newAtomicGauge(descriptor);
    }

    private OstrichMetric<Double> newRatioGauge(RatioGaugeDescriptor<E, E> descriptor) {
        final E numerator = descriptor.getNumeratorMetric();
        final E denominator = descriptor.getDenominatorMetric();

        final Gauge<Double> gauge = Metrics.newGauge(_class, descriptor.getName(), _uniqueScope, descriptor.isPercentage()
                ? new PercentGauge() {
                    @Override
                    protected double getNumerator() {
                        return doubleValue(numerator);
                    }

                    @Override
                    protected double getDenominator() {
                        return doubleValue(denominator);
                    }
                }
                : new RatioGauge() {
                    @Override
                    protected double getNumerator() {
                        return doubleValue(numerator);
                    }

                    @Override
                    protected double getDenominator() {
                        return doubleValue(denominator);
                    }
                });

        return new OstrichMetric<Double>() {
            @Override
            public <N extends Number> void update(N value) {

            }

            @Override
            public Double getCount() {
                return gauge.value();
            }
        };
    }

    private double doubleValue(E metric) {
        return _metrics.get(metric).getCount().doubleValue();
    }

    private OstrichMetric<Long> newCounter(CounterDescriptor descriptor) {
        final Counter counter = Metrics.newCounter(_class, descriptor.getName(), _uniqueScope);

        return new OstrichMetric<Long>() {
            @Override
            public <N extends Number> void update(N value) {
                counter.inc(value.longValue());
            }

            @Override
            public Long getCount() {
                return counter.count();
            }
        };
    }

    private OstrichMetric<Long> newHistogram(HistogramDescriptor descriptor) {
        final Histogram histogram = Metrics.newHistogram(_class, descriptor.getName(), _uniqueScope, descriptor.isBiased());

        return new OstrichMetric<Long>() {
            @Override
            public <N extends Number> void update(N value) {
                histogram.update(value.longValue());
            }

            @Override
            public Long getCount() {
                return histogram.count();
            }
        };
    }

    private OstrichMetric<Long> newMeter(MeterDescriptor descriptor) {
        final Meter meter = Metrics.newMeter(_class, descriptor.getName(), _uniqueScope, descriptor.getEventType(),
                descriptor.getRateUnit());

        return new OstrichMetric<Long>() {
            @Override
            public <N extends Number> void update(N value) {
                meter.mark(value.longValue());
            }

            @Override
            public Long getCount() {
                return meter.count();
            }
        };
    }

    private OstrichMetric<Long> newTimer(TimerDescriptor descriptor) {
        final Timer timer = Metrics.newTimer(_class, descriptor.getName(), _uniqueScope,
                descriptor.getDurationUnit(), descriptor.getRateUnit());

        return new OstrichMetric<Long>() {
            @Override
            public <N extends Number> void update(N value) {
                timer.update(value.longValue(), timer.durationUnit());
            }

            @Override
            public void update() {
                update(0L);
            }

            @Override
            public Long getCount() {
                return timer.count();
            }
        };
    }

    public static class Builder<E extends Enum & Described> {
        private final Class<E> _enum;
        private Class _class;
        private String _uniqueScope = null;

        private Builder(Class<E> type) {
            _enum = checkNotNull(type);
        }

        public static <E extends Enum & Described> Builder<E> forMetrics(Class<E> type) {
            return new Builder<E>(type);
        }

        public Builder<E> withScope(String scope) {
            checkArgument(!Strings.isNullOrEmpty(scope));
            _uniqueScope = scope + "-" + UUID.randomUUID();
            return this;
        }

        public Builder<E> withScopeFrom(OstrichMetrics metrics) {
            checkNotNull(metrics);
            _uniqueScope = metrics._uniqueScope;
            return this;
        }

        public Builder<E> withDomain(Class domain) {
            checkNotNull(domain);
            _class = domain;
            return this;
        }

        public OstrichMetrics<E> build() {
            checkState(!Strings.isNullOrEmpty(_uniqueScope));
            checkNotNull(_class);
            return new OstrichMetrics<E>(_class, _uniqueScope, _enum);
        }
    }
}
