package com.bazaarvoice.ostrich.pool;

import com.bazaarvoice.ostrich.PartitionContext;
import com.bazaarvoice.ostrich.RetryPolicy;
import com.bazaarvoice.ostrich.ServiceCallback;
import com.bazaarvoice.ostrich.ServiceEndPoint;
import com.bazaarvoice.ostrich.ServiceEndPointPredicate;
import com.bazaarvoice.ostrich.exceptions.MaxRetriesException;
import com.bazaarvoice.ostrich.exceptions.NoAvailableHostsException;
import com.bazaarvoice.ostrich.metrics.Metrics;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

class AsyncServicePool<S> implements com.bazaarvoice.ostrich.AsyncServicePool<S> {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncServicePool.class);

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
    private final Metrics _metrics;
    private final Timer _executionTime;
    private final Meter _numExecuteSuccesses;
    private final Meter _numExecuteFailures;
    private final Histogram _executeBatchSize;

    AsyncServicePool(Ticker ticker, ServicePool<S> pool, boolean shutdownPoolOnClose,
                            ExecutorService executor, boolean shutdownExecutorOnClose) {
        _ticker = checkNotNull(ticker);
        _pool = checkNotNull(pool);
        _shutdownPoolOnClose = shutdownPoolOnClose;
        _executor = checkNotNull(executor);
        _shutdownExecutorOnClose = shutdownExecutorOnClose;

        String serviceName = _pool.getServiceName();
        _metrics = Metrics.forInstance(this, serviceName);
        _executionTime = _metrics.newTimer(serviceName, "execution-time", TimeUnit.MILLISECONDS, TimeUnit.SECONDS);
        _numExecuteSuccesses = _metrics.newMeter(serviceName, "num-execute-successes", "successes", TimeUnit.SECONDS);
        _numExecuteFailures = _metrics.newMeter(serviceName, "num-execute-failures", "failures", TimeUnit.SECONDS);
        _executeBatchSize = _metrics.newHistogram(serviceName, "execute-batch-size", false);
    }

    @Override
    public void close() throws IOException {
        if (_shutdownExecutorOnClose) {
            _executor.shutdown();
        }

        if (_shutdownPoolOnClose) {
            _pool.close();
        }

        _metrics.close();
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
    public <R> Future<R> execute(final PartitionContext partitionContext, final RetryPolicy retryPolicy,
                                 final ServiceCallback<S, R> callback) {
        return _executor.submit(new Callable<R>() {
            @Override
            public R call() throws Exception {
                return _pool.execute(partitionContext, retryPolicy, callback);
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
        Collection<Future<R>> futures = Lists.newArrayList();

        Iterable<ServiceEndPoint> endPoints = _pool.getAllEndPoints();
        if (Iterables.isEmpty(endPoints)) {
            throw new NoAvailableHostsException();
        }

        for (final ServiceEndPoint endPoint : endPoints) {
            if (!predicate.apply(endPoint)) {
                continue;
            }

            Future<R> future = _executor.submit(new Callable<R>() {
                @Override
                public R call() throws Exception {
                    TimerContext timer = _executionTime.time();
                    Stopwatch sw = new Stopwatch(_ticker).start();
                    int numAttempts = 0;

                    try {
                        Exception lastException;

                        do {
                            try {
                                R result = _pool.executeOnEndPoint(endPoint, callback);
                                _numExecuteSuccesses.mark();
                                return result;
                            } catch (Exception e) {
                                _numExecuteFailures.mark();

                                // Don't retry if exception is too severe.
                                if (!_pool.isRetriableException(e)) {
                                    throw e;
                                }

                                lastException = e;
                                LOG.info("Retriable exception from end point id: " + endPoint.getId(), e);
                            }
                        } while (retry.allowRetry(++numAttempts, sw.elapsedMillis()));

                        throw new MaxRetriesException(lastException);
                    } finally {
                        timer.stop();
                    }
                }
            });

            futures.add(future);
        }

        _executeBatchSize.update(futures.size());
        return futures;
    }

    @Override
    public int getNumValidEndPoints() {
        return _pool.getNumValidEndPoints();
    }

    @Override
    public int getNumBadEndPoints() {
        return _pool.getNumBadEndPoints();
    }
}
