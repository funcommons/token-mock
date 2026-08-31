package fun.commons.tokenmock.handler.ollama;

import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama local LLM API.
 * <p>
 * Endpoints (relative to /ollama):
 *   POST /api/chat
 *   POST /api/generate
 *   GET  /api/tags
 * <p>
 * Auth: usually none; Ollama accepts any Bearer token.
 */
@Component
public class OllamaProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final TokenEstimator estimator;

    public OllamaProtocolHandler(VendorRegistry registry, TokenEstimator estimator) {
        this.registry = registry;
        this.estimator = estimator;
    }

    @Override
    public String protocol() {
        return "ollama";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        // Ollama 默认无鉴权,mock 接受任意 key
        if (request.getAuthHeader() != null && !request.getAuthHeader().isBlank()
                && !registry.authenticate(vendor, request.getAuthHeader())) {
            // 不严格校验,继续走
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        return switch (path) {
            case "/api/chat" -> handleChat(vendor, request);
            case "/api/generate" -> handleGenerate(vendor, request);
            case "/api/tags" -> handleTags(vendor);
            default -> throw new IllegalArgumentException("unknown ollama path: " + path);
        };
    }

    private Object handleChat(VendorConfig vendor, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), firstModel(vendor));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        String userText = messages.isEmpty() ? "(空)" : String.valueOf(messages.get(messages.size() - 1).getOrDefault("content", ""));
        String content = "[mock] 你说的内容是: " + userText;

        return ResponseEntity.ok(Map.of(
                "model", model,
                "created_at", Instant.now().toString(),
                "message", Map.of("role", "assistant", "content", content),
                "done", true,
                "eval_count", estimator.estimate(content)
        ));
    }

    private Object handleGenerate(VendorConfig vendor, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), firstModel(vendor));
        String prompt = stringOr(body.get("prompt"), "");
        String content = "[mock] 你说的内容是: " + prompt;

        return ResponseEntity.ok(Map.of(
                "model", model,
                "created_at", Instant.now().toString(),
                "response", content,
                "done", true
        ));
    }

    private Object handleTags(VendorConfig vendor) {
        List<Map<String, Object>> models = vendor.getModels() == null ? List.of()
                : vendor.getModels().stream()
                .map(m -> Map.<String, Object>of("name", m.getCode(), "size", 4_000_000_000L))
                .toList();
        return ResponseEntity.ok(Map.of("models", models));
    }

    private String firstModel(VendorConfig vendor) {
        if (vendor.getModels() == null || vendor.getModels().isEmpty()) {
            return "llama3";
        }
        return vendor.getModels().get(0).getCode();
    }

    private Map<String, Object> asMap(Object body) {
        if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) body;
            return m;
        }
        return new LinkedHashMap<>();
    }

    private String stringOr(Object v, String fb) {
        return v == null || String.valueOf(v).isBlank() ? fb : String.valueOf(v);
    }
}
