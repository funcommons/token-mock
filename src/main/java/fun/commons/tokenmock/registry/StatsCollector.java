package fun.commons.tokenmock.registry;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stats collector: counts requests, failures, rejections per vendor.
 */
@Component
public class StatsCollector {

    private final Map<String, VendorStats> byVendor = new ConcurrentHashMap<>();

    public void recordRequest(String vendorSlug, String model, boolean success, boolean rateLimited) {
        VendorStats stats = byVendor.computeIfAbsent(vendorSlug, k -> new VendorStats());
        stats.totalRequests.incrementAndGet();
        if (success) {
            stats.successCount.incrementAndGet();
        } else {
            stats.failureCount.incrementAndGet();
        }
        if (rateLimited) {
            stats.rateLimitedCount.incrementAndGet();
        }
        if (model != null) {
            stats.byModel.computeIfAbsent(model, m -> new AtomicLong()).incrementAndGet();
        }
    }

    public VendorStats get(String vendorSlug) {
        return byVendor.getOrDefault(vendorSlug, new VendorStats());
    }

    public Map<String, VendorStats> snapshot() {
        return Map.copyOf(byVendor);
    }

    public void reset() {
        byVendor.clear();
    }

    public static class VendorStats {
        public final AtomicLong totalRequests = new AtomicLong(0);
        public final AtomicLong successCount = new AtomicLong(0);
        public final AtomicLong failureCount = new AtomicLong(0);
        public final AtomicLong rateLimitedCount = new AtomicLong(0);
        public final Map<String, AtomicLong> byModel = new ConcurrentHashMap<>();

        public Map<String, Object> toMap() {
            return Map.of(
                    "totalRequests", totalRequests.get(),
                    "successCount", successCount.get(),
                    "failureCount", failureCount.get(),
                    "rateLimitedCount", rateLimitedCount.get(),
                    "byModel", Map.copyOf(byModel.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue().get()))));
        }
    }
}
