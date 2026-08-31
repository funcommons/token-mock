package fun.commons.tokenmock.web;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.RateLimitConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.registry.StatsCollector;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for vendor management and stats.
 * <p>
 * All endpoints require X-Mock-Admin-Token header.
 * <p>
 * Only active under the {@code mock} profile (dev/IT). Production must NOT
 * activate it — the admin token is a static dev secret, not a prod credential.
 */
@Profile("mock")
@RestController
@RequestMapping("/admin")
public class AdminVendorController {

    private final MockProperties properties;
    private final VendorRegistry registry;
    private final StatsCollector stats;

    public AdminVendorController(MockProperties properties, VendorRegistry registry, StatsCollector stats) {
        this.properties = properties;
        this.registry = registry;
        this.stats = stats;
    }

    @GetMapping("/vendors")
    public ResponseEntity<List<Map<String, Object>>> listVendors(
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        List<Map<String, Object>> out = registry.all().stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("slug", v.getSlug());
                    m.put("protocol", v.getProtocol());
                    m.put("latencyMs", v.getLatencyMs());
                    m.put("failureRate", v.getFault().getFailureRate());
                    m.put("rateLimitEnabled", v.getRateLimit().isEnabled());
                    m.put("rateLimitQps", v.getRateLimit().getQps());
                    m.put("modelCount", v.getModels() == null ? 0 : v.getModels().size());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/vendors/{slug}")
    public ResponseEntity<Map<String, Object>> getVendor(
            @PathVariable String slug,
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        VendorConfig v = registry.findBySlug(slug);
        if (v == null) return ResponseEntity.status(404).build();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", v.getSlug());
        m.put("protocol", v.getProtocol());
        m.put("latencyMs", v.getLatencyMs());
        m.put("models", v.getModels());
        m.put("rateLimit", Map.of(
                "enabled", v.getRateLimit().isEnabled(),
                "qps", v.getRateLimit().getQps(),
                "tokensPerSecond", v.getRateLimit().getTokensPerSecond(),
                "burstRequests", v.getRateLimit().getBurstRequests(),
                "burstTokens", v.getRateLimit().getBurstTokens()));
        m.put("fault", Map.of(
                "failureRate", v.getFault().getFailureRate(),
                "statusCode", v.getFault().getStatusCode(),
                "forceNextNFailures", v.getFault().getForceNextNFailures(),
                "extraLatencyMs", v.getFault().getExtraLatencyMs()));
        return ResponseEntity.ok(m);
    }

    @PostMapping("/vendors/{slug}/faults")
    public ResponseEntity<Map<String, Object>> setFaults(
            @PathVariable String slug,
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        VendorConfig v = registry.findBySlug(slug);
        if (v == null) return ResponseEntity.status(404).build();
        if (body.containsKey("failureRate")) {
            v.getFault().setFailureRate(toDouble(body.get("failureRate")));
        }
        if (body.containsKey("statusCode")) {
            v.getFault().setStatusCode(toInt(body.get("statusCode")));
        }
        if (body.containsKey("forceNextNFailures")) {
            v.getFault().setForceNextNFailures(toInt(body.get("forceNextNFailures")));
        }
        if (body.containsKey("extraLatencyMs")) {
            v.getFault().setExtraLatencyMs(toInt(body.get("extraLatencyMs")));
        }
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    @PostMapping("/vendors/{slug}/rate-limit")
    public ResponseEntity<Map<String, Object>> setRateLimit(
            @PathVariable String slug,
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        VendorConfig v = registry.findBySlug(slug);
        if (v == null) return ResponseEntity.status(404).build();
        RateLimitConfig cfg = v.getRateLimit();
        if (body.containsKey("enabled")) {
            cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }
        if (body.containsKey("qps")) {
            cfg.setQps(toDouble(body.get("qps")));
        }
        if (body.containsKey("burstRequests")) {
            cfg.setBurstRequests(toDouble(body.get("burstRequests")));
        }
        if (body.containsKey("tokensPerSecond")) {
            cfg.setTokensPerSecond(toDouble(body.get("tokensPerSecond")));
        }
        if (body.containsKey("burstTokens")) {
            cfg.setBurstTokens(toDouble(body.get("burstTokens")));
        }
        return ResponseEntity.ok(Map.of("status", "updated (effective on next vendor reload)"));
    }

    @GetMapping("/vendors/{slug}/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @PathVariable String slug,
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        if (registry.findBySlug(slug) == null) return ResponseEntity.status(404).build();
        return ResponseEntity.ok(stats.get(slug).toMap());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> globalStats(
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        Map<String, Object> out = new LinkedHashMap<>();
        stats.snapshot().forEach((k, v) -> out.put(k, v.toMap()));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(
            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String token) {
        if (!isAdmin(token)) return ResponseEntity.status(401).build();
        stats.reset();
        return ResponseEntity.ok(Map.of("status", "reset"));
    }

    private boolean isAdmin(String token) {
        return properties.getAdminToken().equals(token);
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    private int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v));
    }
}
