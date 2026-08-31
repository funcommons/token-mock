package fun.commons.tokenmock.handler.gemini;

import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini API.
 * <p>
 * Endpoints (relative to /gemini):
 *   POST /v1/models/{model}:generateContent
 *   POST /v1/models/{model}:streamGenerateContent  (treated same as non-stream here)
 *   POST /v1/models/{model}:countTokens
 *   POST /v1/models/{model}:embedContent
 *   POST /v1/models/{model}:batchEmbedContents
 *   GET  /v1/models
 * <p>
 * Auth: key as query parameter (?key=xxx) OR header x-goog-api-key.
 */
@Component
public class GeminiProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final TokenEstimator estimator;
    private final EmbeddingGenerator embeddings;

    public GeminiProtocolHandler(VendorRegistry registry, TokenEstimator estimator,
                                  EmbeddingGenerator embeddings) {
        this.registry = registry;
        this.estimator = estimator;
        this.embeddings = embeddings;
    }

    @Override
    public String protocol() {
        return "gemini";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!registry.authenticate(vendor, request.getAuthHeader())) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", Map.of("code", 401, "message", "API key not valid", "status", "UNAUTHENTICATED")
            ));
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        if (path.startsWith("/v1/models/") && path.contains(":")) {
            String action = path.substring(path.lastIndexOf(":") + 1);
            return switch (action) {
                case "generateContent", "streamGenerateContent" -> handleGenerateContent(vendor, path, request);
                case "countTokens" -> handleCountTokens(vendor, path, request);
                case "embedContent" -> handleEmbedContent(vendor, path, request);
                case "batchEmbedContents" -> handleBatchEmbedContents(vendor, path, request);
                default -> throw new IllegalArgumentException("unknown gemini action: " + action);
            };
        }
        if (path.equals("/v1/models")) {
            return handleListModels(vendor);
        }
        throw new IllegalArgumentException("unknown path: " + path);
    }

    /**
     * {@code POST /v1/models/{m}:countTokens} — 输入侧 token 预算.
     * <p>Shape 与真实厂商对齐:{@code {totalTokens:N}}.
     */
    private Object handleCountTokens(VendorConfig vendor, String path, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String userText = extractUserText(body);
        int n = estimator.estimate(userText);
        return ResponseEntity.ok(Map.of(
                "totalTokens", n
        ));
    }

    /**
     * {@code POST /v1/models/{m}:embedContent} — 单文本 embedding.
     * <p>返回 shape:{@code {embedding:{values:[...]}}}.
     */
    private Object handleEmbedContent(VendorConfig vendor, String path, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String text = extractTextFromContent(body, "content");
        List<Double> vec = embeddings.vector(text, 768);
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("values", vec);
        return ResponseEntity.ok(Map.of("embedding", embedding));
    }

    /**
     * {@code POST /v1/models/{m}:batchEmbedContents} — 批量 embedding.
     * <p>请求:{@code {requests:[{model,content:{parts:[{text}]}}, ...]}};
     * 响应:{@code {embeddings:[{embedding:{values:[...]}}, ...]}}.
     */
    private Object handleBatchEmbedContents(VendorConfig vendor, String path, MockRequest request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) asMap(request.getBody())
                .getOrDefault("requests", List.of());
        List<Map<String, Object>> outs = new ArrayList<>();
        for (Map<String, Object> r : reqs) {
            String text = extractTextFromContent(r, "content");
            List<Double> vec = embeddings.vector(text, 768);
            Map<String, Object> embedding = new LinkedHashMap<>();
            embedding.put("values", vec);
            outs.add(Map.of("embedding", embedding));
        }
        return ResponseEntity.ok(Map.of("embeddings", outs));
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromContent(Map<String, Object> body, String field) {
        Object node = body.get(field);
        if (node == null) return "(empty)";
        if (node instanceof Map) {
            Object parts = ((Map<String, Object>) node).get("parts");
            if (parts instanceof List) {
                for (Object p : (List<Object>) parts) {
                    if (p instanceof Map && ((Map<?, ?>) p).containsKey("text")) {
                        return String.valueOf(((Map<?, ?>) p).get("text"));
                    }
                }
            }
        }
        return "(empty)";
    }

    private Object handleGenerateContent(VendorConfig vendor, String path, MockRequest request) {
        String model = extractModelFromPath(path);
        Map<String, Object> body = asMap(request.getBody());
        String userText = extractUserText(body);
        String content = "[mock] 你说的内容是: " + userText;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidates", List.of(Map.of(
                "content", Map.of(
                        "parts", List.of(Map.of("text", content)),
                        "role", "model"
                ),
                "finishReason", "STOP",
                "index", 0
        )));
        response.put("usageMetadata", Map.of(
                "promptTokenCount", estimator.estimate(userText),
                "candidatesTokenCount", estimator.estimate(content),
                "totalTokenCount", estimator.total(estimator.estimate(userText), estimator.estimate(content))
        ));
        return ResponseEntity.ok(response);
    }

    private Object handleListModels(VendorConfig vendor) {
        List<Map<String, Object>> models = vendor.getModels() == null ? List.of()
                : vendor.getModels().stream()
                .map(m -> Map.<String, Object>of(
                        "name", "models/" + m.getCode(),
                        "version", "001",
                        "displayName", m.getCode(),
                        "description", "[mock] gemini model"))
                .toList();
        return ResponseEntity.ok(Map.of("models", models));
    }

    private String extractModelFromPath(String path) {
        // /v1/models/{model}:generateContent
        int start = path.indexOf("/v1/models/") + "/v1/models/".length();
        int end = path.indexOf(":");
        if (start < 0 || end < 0 || start >= end) return "gemini-1.5-pro";
        return path.substring(start, end);
    }

    private Map<String, Object> asMap(Object body) {
        if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) body;
            return m;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private String extractUserText(Map<String, Object> body) {
        Object contents = body.get("contents");
        if (!(contents instanceof List)) return "(空)";
        for (int i = ((List<Object>) contents).size() - 1; i >= 0; i--) {
            Object item = ((List<Object>) contents).get(i);
            if (item instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) item;
                if (!"user".equals(m.get("role"))) continue;
                Object parts = m.get("parts");
                if (parts instanceof List) {
                    for (Object p : (List<Object>) parts) {
                        if (p instanceof Map && ((Map<?, ?>) p).containsKey("text")) {
                            return String.valueOf(((Map<?, ?>) p).get("text"));
                        }
                    }
                }
            }
        }
        return "(空)";
    }
}
