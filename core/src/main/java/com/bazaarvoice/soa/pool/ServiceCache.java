package com.bazaarvoice.soa.pool;

import com.bazaarvoice.soa.ServiceEndPoint;
import com.bazaarvoice.soa.ServiceFactory;
import com.bazaarvoice.soa.exceptions.NoCachedInstancesAvailableException;
import com.bazaarvoice.soa.metrics.Described;
import com.bazaarvoice.soa.metrics.MeterDescriptor;
import com.bazaarvoice.soa.metrics.OstrichMetrics;
import com.bazaarvoice.soa.metrics.OstrichMetricsDescriptor;
import com.bazaarvoice.soa.metrics.RatioGaugeDescriptor;
import com.bazaarvoice.soa.metrics.TimerDescriptor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.pool.BaseKeyedPoolableObjectFactory;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A cache for service instances. Useful if there's more than insignificant overhead in creating service connections
 * from a {@link ServiceEndPoint}.  Will spawn one thread (shared by all {@code ServiceCache}s) to handle evictions of
 */
class ServiceCache<S> implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceCache.class);
    private static final ScheduledExecutorService EVICTION_EXECUTOR = Executors.newScheduledThreadPool(1,
            new ThreadFactoryBuilder()
                    .setNameFormat("ServiceCache-EvictionThread-%d")
                    .setDaemon(true)
                    .build());

    /** How often to try to evict old service instances. */
    @VisibleForTesting
    static final long EVICTION_DURATION_IN_SECONDS = 300;
    private final GenericKeyedObjectPool<ServiceEndPoint, S> _pool;
    private final AtomicLong _revisionNumber = new AtomicLong();
    private final Map<ServiceEndPoint, Long> _invalidRevisions = new MapMaker().weakKeys().makeMap();
    private final Map<S, Long> _checkOutRevisions = Maps.newConcurrentMap();
    private final Future<?> _evictionFuture;
    private volatile boolean _isClosed = false;
    private final OstrichMetrics<ServiceCacheMetrics> _metrics;

    enum ServiceCacheMetrics implements Described {
        EVICTION {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new TimerDescriptor("service-cache-eviction-run", TimeUnit.MICROSECONDS, TimeUnit.SECONDS);
            }
        },
        EVICTION_RATE {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new MeterDescriptor("service-cache-eviction-rate", "evictions", TimeUnit.SECONDS);
            }
        },
        CACHE_MISSES {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new TimerDescriptor("service-cache-misses", TimeUnit.MICROSECONDS, TimeUnit.SECONDS);
            }
        },
        CACHE_CHECKOUTS {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new MeterDescriptor("service-cache-checkouts", "checkouts", TimeUnit.SECONDS);
            }
        },
        CACHE_MISS_PERCENTAGE {
            @Override
            public OstrichMetricsDescriptor getDescription() {
                return new RatioGaugeDescriptor<ServiceCacheMetrics, ServiceCacheMetrics>("service-cache-hit-percentage", CACHE_MISSES, CACHE_CHECKOUTS, true);
            }
        }
    }

    /**
     * Builds a basic service cache.
     *
     * @param policy         The configuration for this cache.
     * @param serviceFactory The factory to fall back to on cache misses.
     */
    ServiceCache(ServiceCachingPolicy policy, ServiceFactory<S> serviceFactory) {
        this(policy, serviceFactory, EVICTION_EXECUTOR);
    }

    /**
     * Builds a basic service cache.
     *
     * @param policy         The configuration for this cache.
     * @param serviceFactory The factory to fall back to on cache misses.
     * @param executor       The executor to use for checking for idle instances to evict.
     */
    @VisibleForTesting
    ServiceCache(ServiceCachingPolicy policy, ServiceFactory<S> serviceFactory, ScheduledExecutorService executor) {
        checkNotNull(policy);
        checkNotNull(serviceFactory);
        checkNotNull(executor);

        GenericKeyedObjectPool.Config poolConfig = new GenericKeyedObjectPool.Config();

        // Global configuration
        poolConfig.maxTotal = policy.getMaxNumServiceInstances();
        poolConfig.numTestsPerEvictionRun = policy.getMaxNumServiceInstances();
        poolConfig.minEvictableIdleTimeMillis = policy.getMaxServiceInstanceIdleTime(TimeUnit.MILLISECONDS);

        switch (policy.getCacheExhaustionAction()) {
            case FAIL:
                poolConfig.whenExhaustedAction = GenericKeyedObjectPool.WHEN_EXHAUSTED_FAIL;
                break;
            case GROW:
                poolConfig.whenExhaustedAction = GenericKeyedObjectPool.WHEN_EXHAUSTED_GROW;
                break;
            case WAIT:
                poolConfig.whenExhaustedAction = GenericKeyedObjectPool.WHEN_EXHAUSTED_BLOCK;
                break;
        }

        // Per end point configuration
        poolConfig.maxActive = policy.getMaxNumServiceInstancesPerEndPoint();
        poolConfig.maxIdle = policy.getMaxNumServiceInstancesPerEndPoint();

        // Make sure all instances in the pool are checked for staleness during eviction runs.
        poolConfig.numTestsPerEvictionRun = policy.getMaxNumServiceInstances();

        _pool = new GenericKeyedObjectPool<ServiceEndPoint, S>(new PoolServiceFactory<S>(serviceFactory), poolConfig);

        // Don't schedule eviction if not caching or not expiring stale instances.
        _evictionFuture = (policy.getMaxNumServiceInstances() != 0)
                || (policy.getMaxNumServiceInstancesPerEndPoint() != 0)
                || (policy.getMaxServiceInstanceIdleTime(TimeUnit.MILLISECONDS) > 0)
                ? executor.scheduleAtFixedRate(new Runnable() {
                      @Override
                      public void run() {
                          Stopwatch watch = new Stopwatch();
                          watch.start();
                          try {
                              _pool.evict();
                          } catch (Exception e) {
                              // Should never happen, but log just in case. Swallow exception so thread doesn't die.
                              LOG.error("ServiceCache eviction run failed.", e);
                          } finally {
                              watch.stop();
                              _metrics.getMetric(ServiceCacheMetrics.EVICTION).update(watch.elapsedTime(TimeUnit.MICROSECONDS));
                          }
                      }
                  }, EVICTION_DURATION_IN_SECONDS, EVICTION_DURATION_IN_SECONDS, TimeUnit.SECONDS)
                : null;

        _metrics = OstrichMetrics.Builder.forMetrics(ServiceCacheMetrics.class).withDomain(getClass()).withScope(serviceFactory.getServiceName()).build();
    }

    @VisibleForTesting
    GenericKeyedObjectPool<ServiceEndPoint, S> getPool() {
        return _pool;
    }

    /**
     * Retrieves a cached service instance for an end point that is not currently checked out.  If no idle cached
     * instance is available and the cache is not full, a new one will be created, added to the cache, and then checked
     * out.  Once the checked out instance is no longer in use, it should be returned by calling {@link #checkIn}.
     *
     * @param endPoint The end point to retrieve a cached service instance for.
     * @return A cached service instance for the requested end point.
     * @throws NoCachedInstancesAvailableException If the cache has reached total maximum capacity, or maximum capacity
     *         for the requested end point, and no connections that aren't already checked out are available.
     */
    public S checkOut(ServiceEndPoint endPoint) throws Exception {
        checkNotNull(endPoint);

        try {
            _metrics.getMetric(ServiceCacheMetrics.CACHE_CHECKOUTS).update();
            S service = _pool.borrowObject(endPoint);

            // Remember the revision that we've checked this service out on in case we need to invalidate it later
            _checkOutRevisions.put(service, _revisionNumber.incrementAndGet());

            return service;
        } catch (NoSuchElementException e) {
            _metrics.getMetric(ServiceCacheMetrics.CACHE_MISSES).update();
            // This will happen if there are no available instances and there is no room for a new one,
            // or if a newly created instance is not valid.
            throw new NoCachedInstancesAvailableException();
        }
    }

    /**
     * Returns a service instance for an end point to the cache so that it may be used by other users.
     *
     * @param endPoint The end point that the service instance belongs to.
     * @param service  The service instance to return to the pool.
     * @throws Exception Never.
     */
    public void checkIn(ServiceEndPoint endPoint, S service) throws Exception {
        checkNotNull(endPoint);
        checkNotNull(service);

        // Figure out if we should check this revision in.  If it was created before the last known invalid revision
        // for this particular end point, or the cache is closed, then we shouldn't check it in.
        Long invalidRevision = _invalidRevisions.get(endPoint);
        Long serviceRevision = _checkOutRevisions.remove(service);

        if (_isClosed || (invalidRevision != null && serviceRevision < invalidRevision)) {
            _pool.invalidateObject(endPoint, service);
        } else {
            _pool.returnObject(endPoint, service);
        }
    }

    public int getNumIdleInstances(ServiceEndPoint endPoint) {
        checkNotNull(endPoint);
        return _pool.getNumIdle(endPoint);
    }

    public int getNumActiveInstances(ServiceEndPoint endPoint) {
        checkNotNull(endPoint);
        return _pool.getNumActive(endPoint);
    }

    @Override
    public void close() {
        _isClosed = true;

        if (_evictionFuture != null) {
            _evictionFuture.cancel(false);
        }

        _pool.clear();
        _metrics.close();
    }

    public void evict(ServiceEndPoint endPoint) {
        checkNotNull(endPoint);

        // Mark all service instances created prior to now as invalid so that we don't inadvertently check them back in
        _invalidRevisions.put(endPoint, _revisionNumber.incrementAndGet());
        _pool.clear(endPoint);
    }

    private class PoolServiceFactory<S> extends BaseKeyedPoolableObjectFactory<ServiceEndPoint, S> {
        private final ServiceFactory<S> _serviceFactory;

        private PoolServiceFactory(ServiceFactory<S> serviceFactory) {
            _serviceFactory = serviceFactory;
        }

        @Override
        public S makeObject(ServiceEndPoint endPoint) throws Exception {
            _metrics.getMetric(ServiceCacheMetrics.CACHE_MISSES).update();
            return _serviceFactory.create(endPoint);
        }

        @Override
        public void destroyObject(ServiceEndPoint key, S obj) throws Exception {
            _metrics.getMetric(ServiceCacheMetrics.EVICTION_RATE).update();
        }
    }
}