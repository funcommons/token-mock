package fun.commons.tokenmock.core;

import com.google.common.util.concurrent.RateLimiter;
import fun.commons.tokenmock.config.RateLimitConfig;

import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-vendor dual-bucket rate limiter (QPS + tokens/s) using Guava RateLimiter.
 * <p>
 * Guava RateLimiter implements the token-bucket algorithm with bursty support.
 * Any bucket exhaustion results in immediate rejection (no blocking).
 * <p>
 * The config is read live from a {@link Supplier} so updates via the mock admin
 * endpoint (or fresh YAML binding after a context restart) take effect without
 * needing to rebuild the limiter. The Guava buckets themselves are recreated
 * only when the configured rate changes.
 * <p>
 * Not a {@code @Component} — one instance is built per vendor by
 * {@code MockDispatchController}, taking its own config supplier.
 */
public class VendorRateLimiter {

    private final Supplier<RateLimitConfig> configSupplier;
    private final AtomicLong rejectedCount = new AtomicLong(0);

    private volatile RateLimitConfig lastConfig;
    private volatile RateLimiter requestBucket;
    private volatile RateLimiter tokenBucket;
    private volatile double lastQps = Double.NaN;
    private volatile double lastTokensPerSecond = Double.NaN;

    public VendorRateLimiter(Supplier<RateLimitConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    private void refresh() {
        RateLimitConfig cfg = configSupplier.get();
        if (cfg == null) {
            requestBucket = null;
            tokenBucket = null;
            lastConfig = null;
            return;
        }
        if (cfg.getQps() != lastQps) {
            this.requestBucket = cfg.getQps() > 0
                    ? RateLimiter.create(cfg.getQps())
                    : null;
            this.lastQps = cfg.getQps();
        }
        if (cfg.getTokensPerSecond() != lastTokensPerSecond) {
            this.tokenBucket = cfg.getTokensPerSecond() > 0
                    ? RateLimiter.create(cfg.getTokensPerSecond())
                    : null;
            this.lastTokensPerSecond = cfg.getTokensPerSecond();
        }
        this.lastConfig = cfg;
    }

    private static long computeWarmup(double burst, double rate) {
        if (burst <= 0 || rate <= 0) return 0;
        double seconds = burst / rate;
        return Math.max(0, (long) (seconds * 1000));
    }

    public RateLimitResult tryAcquire(int estimatedTokens) {
        refresh();
        RateLimitConfig cfg = lastConfig;
        if (cfg == null || !cfg.isEnabled()) {
            return RateLimitResult.allow();
        }
        if (requestBucket != null && !requestBucket.tryAcquire()) {
            rejectedCount.incrementAndGet();
            return RateLimitResult.rejectByQps(cfg.getQps());
        }
        if (tokenBucket != null && estimatedTokens > 0 && !tokenBucket.tryAcquire(estimatedTokens)) {
            rejectedCount.incrementAndGet();
            return RateLimitResult.rejectByTokens(cfg.getTokensPerSecond());
        }
        return RateLimitResult.allow();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }
}