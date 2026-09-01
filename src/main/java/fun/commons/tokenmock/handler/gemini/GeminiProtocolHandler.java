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
 *   POST /upload/v1beta/files          (resumable, but we collapse to single POST)
 *   GET  /v1beta/files
 *   GET  /v1beta/files/{name}
 *   DELETE /v1beta/files/{name}
 * <p>
 * Auth: key as query parameter (?key=xxx) OR header x-goog-api-key.
 */
@Component
public class GeminiProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final TokenEstimator estimator;
    private final EmbeddingGenerator embeddings;
    private final GeminiFileStore fileStore;

    public GeminiProtocolHandler(VendorRegistry registry, TokenEstimator estimator,
                                  EmbeddingGenerator embeddings, GeminiFileStore fileStore) {
        this.registry = registry;
        this.estimator = estimator;
        this.embeddings = embeddings;
        this.fileStore = fileStore;
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
        if (path.startsWith("/upload/v1beta/files")) {
            // resumable collapsed: single POST records the file
            if (!"POST".equals(request.getMethod())) {
                throw new IllegalArgumentException("unsupported method on /upload: " + request.getMethod());
            }
            Map<String, Object> body = asMap(request.getBody());
            String displayName = stringOr(body.get("__file_name__"), "upload.bin");
            String mimeType = stringOr(body.get("__file_content_type__"), "application/octet-stream");
            Long size = body.get("__file_size__") instanceof Number n ? n.longValue() : 0L;
            var entry = fileStore.save(displayName, mimeType, size);
            return ResponseEntity.ok(fileStore.toApiResponse(entry));
        }
        if (path.startsWith("/v1beta/files")) {
            int q = path.indexOf('?');
            String cleanPath = q > 0 ? path.substring(0, q) : path;
            if (cleanPath.equals("/v1beta/files")) {
                List<Map<String, Object>> data = fileStore.list().stream()
                        .map(fileStore::toApiResponse).toList();
                return ResponseEntity.ok(Map.of("files", data, "nextPageToken", ""));
            }
            String name = cleanPath.substring("/v1beta/files/".length());
            if ("GET".equals(request.getMethod())) {
                return fileStore.get(name)
                        .<Object>map(f -> ResponseEntity.ok(fileStore.toApiResponse(f)))
                        .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                                "error", Map.of("code", 404, "message", "file not found",
                                        "status", "NOT_FOUND"))));
            }
            if ("DELETE".equals(request.getMethod())) {
                boolean ok = fileStore.delete(name);
                if (!ok) {
                    return ResponseEntity.status(404).body(Map.of(
                            "error", Map.of("code", 404, "message", "file not found",
                                    "status", "NOT_FOUND")));
                }
                return ResponseEntity.ok(Map.of());
            }
            throw new IllegalArgumentException("unsupported method on file: " + request.getMethod());
        }
        throw new IllegalArgumentException("unknown path: " + path);
    }

    private String stringOr(Object v, String fb) {
        return v == null || String.valueOf(v).isBlank() ? fb : String.valueOf(v);
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
        String fileUri = extractFirstFileDataUri(body);
        String content = "[mock] 你说的内容是: " + userText
                + (fileUri != null ? " (含 file_data " + fileUri + ")" : "");
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

    @SuppressWarnings("unchecked")
    private String extractFirstFileDataUri(Map<String, Object> body) {
        Object contents = body.get("contents");
        if (!(contents instanceof List)) return null;
        for (Object c : (List<?>) contents) {
            if (!(c instanceof Map)) continue;
            Object parts = ((Map<String, Object>) c).get("parts");
            if (!(parts instanceof List)) continue;
            for (Object p : (List<?>) parts) {
                if (p instanceof Map) {
                    Object fd = ((Map<String, Object>) p).get("file_data");
                    if (fd instanceof Map) {
                        Object uri = ((Map<String, Object>) fd).get("file_uri");
                        if (uri instanceof String s) return s;
                    }
                }
            }
        }
        return null;
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
