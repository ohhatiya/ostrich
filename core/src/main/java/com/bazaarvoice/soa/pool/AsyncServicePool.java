package com.bazaarvoice.soa.pool;

import com.bazaarvoice.soa.RetryPolicy;
import com.bazaarvoice.soa.ServiceCallback;
import com.bazaarvoice.soa.ServiceEndPoint;
import com.bazaarvoice.soa.ServiceEndPointPredicate;
import com.bazaarvoice.soa.exceptions.MaxRetriesException;
import com.bazaarvoice.soa.metrics.Described;
import com.bazaarvoice.soa.metrics.HistogramDescriptor;
import com.bazaarvoice.soa.metrics.MeterDescriptor;
import com.bazaarvoice.soa.metrics.OstrichMetrics;
import com.bazaarvoice.soa.metrics.OstrichMetricsDescriptor;
import com.bazaarvoice.soa.metrics.RatioGaugeDescriptor;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

class AsyncServicePool<S> implements com.bazaarvoice.soa.AsyncServicePool<S> {
    private static final ServiceEndPointPredicate ALL_END_POINTS = new ServiceEndPointPredicate() {
        @Override
        public boolean apply(ServiceEndPoint endPoint) {
            return true;
        }
    };

    private final Ticker _ticker;
    private final ServicePool<S> _pool;
    private final boolean _shutdownPoolOnClose;
    private final ExecutorService _executor;
    private final boolean _shutdownExecutorOnClose;
    private final OstrichMetrics<AsyncServicePoolMetrics> _metrics;

    private enum AsyncServicePoolMetrics implements Described {
        BATCH_EXECUTES {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new MeterDescriptor("async-service-pool-batch-executes", "batches", TimeUnit.SECONDS);
            }
        },
        BATCH_SIZE {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new HistogramDescriptor("async-service-pool-batch-size", false);
            }
        },
        FAILURES {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new MeterDescriptor("async-service-pool-failures", "failures", TimeUnit.SECONDS);
            }
        },
        BATCH_SIZE_PER_EXECUTE {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new RatioGaugeDescriptor<AsyncServicePoolMetrics, AsyncServicePoolMetrics>("async-service-pool-batch-size-per-execute", BATCH_SIZE, BATCH_EXECUTES, false);
            }
        },
        FAILURES_PER_EXECUTE {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new RatioGaugeDescriptor<AsyncServicePoolMetrics, AsyncServicePoolMetrics>("async-service-pool-failures-per-execute", FAILURES, BATCH_EXECUTES, false);
            }
        },
        FAILURE_PERCENTAGE {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new RatioGaugeDescriptor<AsyncServicePoolMetrics, AsyncServicePoolMetrics>("async-service-pool-failure-percentage", FAILURES, BATCH_SIZE, true);
            }
        }
    }

    AsyncServicePool(Ticker ticker, ServicePool<S> pool, boolean shutdownPoolOnClose,
                            ExecutorService executor, boolean shutdownExecutorOnClose) {
        _ticker = checkNotNull(ticker);
        _pool = checkNotNull(pool);
        _shutdownPoolOnClose = shutdownPoolOnClose;
        _executor = checkNotNull(executor);
        _shutdownExecutorOnClose = shutdownExecutorOnClose;
        _metrics = OstrichMetrics.Builder.forMetrics(AsyncServicePoolMetrics.class).withDomain(getClass()).withScope("async-service-pool").build();
    }

    @Override
    public void close() throws IOException {
        if (_shutdownExecutorOnClose) {
            _executor.shutdown();
        }

        if (_shutdownPoolOnClose) {
            _pool.close();
        }
    }

    @Override
    public <R> Future<R> execute(final RetryPolicy retryPolicy, final ServiceCallback<S, R> callback) {
        return _executor.submit(new Callable<R>() {
            @Override
            public R call() throws Exception {
                return _pool.execute(retryPolicy, callback);
            }
        });
    }

    @Override
    public <R> Collection<Future<R>> executeOnAll(RetryPolicy retry, ServiceCallback<S, R> callback) {
        return executeOn(ALL_END_POINTS, retry, callback);
    }

    @Override
    public <R> Collection<Future<R>> executeOn(ServiceEndPointPredicate predicate, final RetryPolicy retry,
                                               final ServiceCallback<S, R> callback) {
        _metrics.getMetric(AsyncServicePoolMetrics.BATCH_EXECUTES).update();

        int batchSize = 0;

        Collection<Future<R>> futures = Lists.newArrayList();

        for (final ServiceEndPoint endPoint : _pool.getAllEndPoints()) {
            if (!predicate.apply(endPoint)) {
                continue;
            }

            ++batchSize;

            Future<R> future = _executor.submit(new Callable<R>() {
                @Override
                public R call() throws Exception {
                    Stopwatch sw = new Stopwatch(_ticker).start();
                    int numAttempts = 0;
                    do {
                        try {
                            return _pool.executeOnEndPoint(endPoint, callback);
                        } catch (Exception e) {
                            _metrics.getMetric(AsyncServicePoolMetrics.FAILURES).update();

                            // Don't retry if exception is too severe.
                            if (!_pool.isRetriableException(e)) {
                                throw e;
                            }
                        }
                    } while (retry.allowRetry(++numAttempts, sw.elapsedMillis()));

                    throw new MaxRetriesException();
                }
            });

            futures.add(future);
        }

        _metrics.getMetric(AsyncServicePoolMetrics.BATCH_SIZE).update(batchSize);

        return futures;
    }
}
