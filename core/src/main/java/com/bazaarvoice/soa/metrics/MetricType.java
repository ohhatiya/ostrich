package com.bazaarvoice.soa.metrics;

import java.util.concurrent.TimeUnit;

public enum MetricType {
    GAUGE {
        public <T> OstrichMetricsDescriptor descriptor(String name) {
            return new GaugeDescriptor(name);
        }
    },
    RATIO_GAUGE {
        public <N extends Enum<N> & Described, D extends Enum<D> & Described>
        OstrichMetricsDescriptor descriptor(String name, N numerator, D denominator) {
            return new RatioGaugeDescriptor<N, D>(name, numerator, denominator, false);
        }
    },
    PERCENT_GAUGE {
        public <N extends Enum<N> & Described, D extends Enum<D> & Described>
        OstrichMetricsDescriptor descriptor(String name, N numerator, D denominator) {
            return new RatioGaugeDescriptor<N, D>(name, numerator, denominator, true);
        }
    },
    COUNTER {
        public OstrichMetricsDescriptor descriptor(String name) {
            return new CounterDescriptor(name);
        }
    },
    METER {
        public OstrichMetricsDescriptor descriptor(String name, String eventType, TimeUnit rateUnit) {
            return new MeterDescriptor(name, eventType, rateUnit);
        }
    },
    HISTOGRAM {
        public OstrichMetricsDescriptor descriptor(String name, boolean biased) {
            return new HistogramDescriptor(name, biased);
        }
    },
    TIMER {
        public OstrichMetricsDescriptor descriptor(String name, TimeUnit durationUnit, TimeUnit rateUnit) {
            return new TimerDescriptor(name, durationUnit, rateUnit);
        }
    }


}