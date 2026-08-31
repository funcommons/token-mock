package fun.commons.tokenmock.web;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.FaultInjector;
import fun.commons.tokenmock.core.RateLimitResult;
import fun.commons.tokenmock.core.VendorRateLimiter;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.StatsCollector;
import fun.commons.tokenmock.registry.VendorRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;

/**
 * Mock upstream dispatch. Only active under the {@code mock} profile — the
 * monolith enables that profile in dev/IT; production must NOT activate it,
 * otherwise these endpoints are public, unauthenticated vendor sandboxes.
 */
@Profile("mock")
@RestController
public class MockDispatchController {

    private final Map<String, ProtocolHandler> handlersByProtocol;
    private final MockProperties properties;
    private final VendorRegistry registry;
    private final StatsCollector stats;
    private final FaultInjector faultInjector;
    private final Map<String, VendorRateLimiter> rateLimitersBySlug = new ConcurrentHashMap<>();

    public MockDispatchController(List<ProtocolHandler> handlers, MockProperties properties,
                                   VendorRegistry registry, StatsCollector stats,
                                   FaultInjector faultInjector) {
        this.handlersByProtocol = handlers.stream()
                .collect(Collectors.toMap(ProtocolHandler::protocol, Function.identity()));
        this.properties = properties;
        this.registry = registry;
        this.stats = stats;
        this.faultInjector = faultInjector;
    }

    /**
     * Lazy / per-vendor rate-limiter lookup. We can't build the limiter map
     * once at construction time because Spring's @ConfigurationProperties
     * binder may swap the {@code RateLimitConfig} instance on the
     * {@code VendorConfig} after constructor time. Reading
     * {@code vendor.getRateLimit()} fresh on each dispatch keeps the limiter
     * in sync with the bound config.
     */
    private VendorRateLimiter rateLimiterFor(String slug, VendorConfig vendor) {
        return rateLimitersBySlug.computeIfAbsent(slug, s -> new VendorRateLimiter(vendor::getRateLimit));
    }

    @RequestMapping(value = "/{slug}/**", consumes = {"application/json", "application/x-www-form-urlencoded"})
    public Object dispatch(@PathVariable String slug,
                            @RequestHeader(value = "Authorization", required = false) String auth,
                            @RequestHeader(value = "x-api-key", required = false) String apiKey,
                            @RequestHeader(value = "x-goog-api-key", required = false) String googKey,
                            @RequestHeader(value = "api-key", required = false) String azureKey,
                            @RequestHeader(value = "X-Mock-Admin-Token", required = false) String adminToken,
                            @Valid @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request) {
        return doDispatch(slug, "POST", auth, apiKey, googKey, azureKey, adminToken, body, request);
    }

    /**
     * GET/DELETE on /{slug}/** — no request body, Spring won't bind a body Map
     * so we forward {@ null} and let the handler drive the response (typical for
     * {@code GET /v1/models} and {@code GET /v1/files}, {@code DELETE /v1/files/{id}}).
     */
    @RequestMapping(value = "/{slug}/**", method = {RequestMethod.GET, RequestMethod.DELETE})
    public Object dispatchRead(@PathVariable String slug,
                                @RequestHeader(value = "Authorization", required = false) String auth,
                                @RequestHeader(value = "x-api-key", required = false) String apiKey,
                                @RequestHeader(value = "x-goog-api-key", required = false) String googKey,
                                @RequestHeader(value = "api-key", required = false) String azureKey,
                                @RequestHeader(value = "X-Mock-Admin-Token", required = false) String adminToken,
                                HttpServletRequest request) {
        return doDispatch(slug, request.getMethod(), auth, apiKey, googKey, azureKey, adminToken,
                new LinkedHashMap<>(), request);
    }

    /**
     * Multipart alias of {@link #dispatch} for STT/Files endpoints. Spring
     * would otherwise reject the request with 415 because the typed {@code Map}
     * body can't bind multipart payloads — we read parts directly and feed the
     * handler the same synthetic body the JSON path produces.
     */
    @RequestMapping(value = "/{slug}/**", consumes = "multipart/form-data")
    public Object dispatchMultipart(@PathVariable String slug,
                                     @RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestHeader(value = "x-api-key", required = false) String apiKey,
                                     @RequestHeader(value = "x-goog-api-key", required = false) String googKey,
                                     @RequestHeader(value = "api-key", required = false) String azureKey,
                                     @RequestHeader(value = "X-Mock-Admin-Token", required = false) String adminToken,
                                     HttpServletRequest request) {
        Map<String, Object> body = extractMultipartBody(request);
        return doDispatch(slug, "POST", auth, apiKey, googKey, azureKey, adminToken, body, request);
    }

    private Object doDispatch(String slug, String method, String auth, String apiKey, String googKey,
                              String azureKey, String adminToken, Map<String, Object> body,
                              HttpServletRequest request) {
        if ("admin".equals(slug)) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", Map.of("message", "admin endpoints require X-Mock-Admin-Token", "type", "auth_required")
            ));
        }

        VendorConfig vendor = registry.findBySlug(slug);
        if (vendor == null) {
            return ResponseEntity.status(404).body(errorBody("vendor_not_found",
                    "no vendor with slug: " + slug));
        }

        VendorRateLimiter rateLimiter = rateLimiterFor(slug, vendor);
        if (rateLimiter != null) {
            RateLimitResult result = rateLimiter.tryAcquire(estimateTokens(body));
            if (!result.accepted()) {
                stats.recordRequest(slug, null, false, true);
                long retry = Math.max(1, result.retryAfterSeconds());
                HttpHeaders headers = new HttpHeaders();
                headers.add("Retry-After", String.valueOf(retry));
                headers.add("X-RateLimit-Remaining", "0");
                return ResponseEntity.status(429).headers(headers).body(Map.of(
                        "error", Map.of(
                                "type", "rate_limit_exceeded",
                                "message", "[mock] rate limit (" + result.limitType() + ") exceeded for vendor " + slug,
                                "vendor", slug,
                                "limit_type", result.limitType(),
                                "retry_after_seconds", retry)
                ));
            }
        }

        FaultInjector.FaultDecision fault = faultInjector.check(vendor.getFault());
        if (fault.shouldFail()) {
            stats.recordRequest(slug, null, false, false);
            return ResponseEntity.status(fault.statusCode()).body(errorBody(
                    "injected_fault",
                    "[mock] injected " + fault.statusCode() + " for vendor " + slug));
        }

        ProtocolHandler handler = handlersByProtocol.get(vendor.getProtocol());
        if (handler == null) {
            return ResponseEntity.status(500).body(errorBody("protocol_unsupported",
                    "no handler registered for protocol: " + vendor.getProtocol()));
        }

        String effectiveAuth = resolveAuth(auth, apiKey, googKey, azureKey);
        String fullPath = request.getRequestURI();
        MockRequest req = MockRequest.builder()
                .vendorSlug(slug)
                .authHeader(effectiveAuth)
                .path(fullPath)
                .method(method)
                .body(body)
                .build();
        Object result;
        try {
            result = handler.handle(req);
            stats.recordRequest(slug, extractModel(body), true, false);
        } catch (RuntimeException ex) {
            stats.recordRequest(slug, extractModel(body), false, false);
            throw ex;
        }
        return result;
    }

    private int estimateTokens(Object body) {
        if (!(body instanceof Map)) return 1;
        Object messages = ((Map<?, ?>) body).get("messages");
        if (messages instanceof List<?> list) {
            int sum = 0;
            for (Object m : list) {
                if (m instanceof Map<?, ?>) {
                    Object c = ((Map<?, ?>) m).get("content");
                    if (c != null) sum += String.valueOf(c).length() / 4;
                }
            }
            return Math.max(1, sum);
        }
        return 1;
    }

    private String extractModel(Object body) {
        if (body instanceof Map<?, ?> map) {
            Object m = map.get("model");
            return m == null ? null : String.valueOf(m);
        }
        return null;
    }

    private String resolveAuth(String auth, String apiKey, String googKey, String azureKey) {
        if (auth != null && !auth.isBlank()) return auth;
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        if (googKey != null && !googKey.isBlank()) return "Bearer " + googKey;
        if (azureKey != null && !azureKey.isBlank()) return "Bearer " + azureKey;
        return null;
    }

    private Map<String, Object> errorBody(String type, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        return body;
    }

    /**
     * Build the synthetic body that {@code AudioImageHandler} reads when the
     * real request was multipart/form-data. We pull the {@code file} part's
     * size and the {@code model}/{@code language} fields out of the parts and
     * leave the rest of the request body alone (no real transcription).
     */
    private Map<String, Object> extractMultipartBody(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        long fileSize = 0;
        try {
            for (jakarta.servlet.http.Part part : request.getParts()) {
                String name = part.getName();
                if ("file".equals(name)) {
                    fileSize = part.getSize();
                } else if (part.getSubmittedFileName() == null) {
                    // Simple form field
                    byte[] raw = part.getInputStream().readAllBytes();
                    body.put(name, new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ex) {
            // partial read is fine — the handler only needs size + language
        }
        body.put("__file_size__", fileSize);
        return body;
    }
}
