package fun.commons.tokenmock.handler.azure;

import fun.commons.tokenmock.config.DeploymentConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.handler.openai.AudioImageHandler;
import fun.commons.tokenmock.handler.openai.BatchJobHandler;
import fun.commons.tokenmock.handler.openai.ResponseJobHandler;
import fun.commons.tokenmock.handler.video.VideoJobHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Azure OpenAI API.
 * <p>
 * Supports both Azure deployment-style URL (legacy) and the v1 OpenAI-shape
 * API surface (newer):
 *   POST /openai/deployments/{dep}/chat/completions?api-version=...
 *   POST /openai/deployments/{dep}/embeddings?api-version=...
 *   POST /openai/v1/responses?api-version=...
 *   POST /openai/v1/audio/speech?api-version=...
 *   POST /openai/v1/audio/transcriptions?api-version=...
 *   POST /openai/v1/images/generations?api-version=...
 *   POST /openai/v1/images/edits?api-version=...
 *   POST /openai/v1/images/variations?api-version=...
 *   POST /openai/v1/batches?api-version=...
 * <p>
 * Schema is identical to OpenAI's same-named endpoint, so we re-use
 * {@link ResponseGenerator}, {@link EmbeddingGenerator}, {@link AudioImageHandler},
 * {@link ResponseJobHandler} and {@link BatchJobHandler} from the OpenAI handler.
 * <p>
 * Auth: api-key header (treated like Bearer in mock).
 */
@Component
public class AzureProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final ResponseGenerator generator;
    private final EmbeddingGenerator embeddings;
    private final AudioImageHandler audioImageHandler;
    private final VideoJobHandler videoJobHandler;
    private final ResponseJobHandler responseJobHandler;
    private final BatchJobHandler batchJobHandler;

    public AzureProtocolHandler(VendorRegistry registry, ResponseGenerator generator,
                                  EmbeddingGenerator embeddings,
                                  AudioImageHandler audioImageHandler,
                                  VideoJobHandler videoJobHandler,
                                  ResponseJobHandler responseJobHandler,
                                  BatchJobHandler batchJobHandler) {
        this.registry = registry;
        this.generator = generator;
        this.embeddings = embeddings;
        this.audioImageHandler = audioImageHandler;
        this.videoJobHandler = videoJobHandler;
        this.responseJobHandler = responseJobHandler;
        this.batchJobHandler = batchJobHandler;
    }

    @Override
    public String protocol() {
        return "azure";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!registry.authenticate(vendor, request.getAuthHeader())) {
            return ResponseEntity.status(401).body(errorBody("invalid_api_key",
                    "Incorrect API key for azure"));
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);

        // Deployment-style legacy URL
        if (path.contains("/deployments/") && path.endsWith("/chat/completions")) {
            return handleChat(vendor, path, request);
        }
        if (path.contains("/deployments/") && path.endsWith("/embeddings")) {
            return handleEmbeddings(vendor, path, request);
        }

        // v1 API surface (OpenAI-shape)
        if (path.equals("/openai/v1/responses")) {
            return handleAzureResponses(vendor, request);
        }
        if (path.startsWith("/openai/v1/responses/")) {
            return handleAzureResponsesSubpath(path, request);
        }
        if (path.equals("/openai/v1/audio/speech")) {
            return audioImageHandler.handleAudioSpeech(request);
        }
        if (path.equals("/openai/v1/audio/transcriptions")
                || path.equals("/openai/v1/audio/translations")) {
            return audioImageHandler.handleAudioTranscription(request);
        }
        if (path.equals("/openai/v1/images/generations")
                || path.equals("/openai/v1/images/edits")
                || path.equals("/openai/v1/images/variations")) {
            return audioImageHandler.handleImages(request);
        }
        if (path.equals("/openai/v1/batches")) {
            return handleAzureBatches(request);
        }
        if (path.startsWith("/openai/v1/batches/")) {
            return handleAzureBatchesSubpath(path, request);
        }
        throw new IllegalArgumentException("unknown azure path: " + path);
    }

    /** Azure Responses: same OpenAI shape, no {@code previous_response_id} sentinels needed. */
    private Object handleAzureResponses(VendorConfig vendor, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), firstChatModel(vendor));
        String userText = extractAzureInputUserText(body);
        String previousId = body.get("previous_response_id") instanceof String s ? s : null;
        boolean background = Boolean.TRUE.equals(body.get("background"));
        var job = responseJobHandler.create(request.getVendorSlug(), model, userText, previousId, background);
        return ResponseEntity.ok(responseJobHandler.toApiResponse(job, background));
    }

    private Object handleAzureResponsesSubpath(String path, MockRequest request) {
        String[] parts = path.split("/");
        // /openai/v1/responses/{id}/cancel  or  /openai/v1/responses/{id}
        String id = parts.length >= 4 ? parts[3] : "";
        var maybe = responseJobHandler.get(id);
        if (parts.length == 4) {
            if (maybe.isEmpty()) {
                return ResponseEntity.status(404).body(errorBody("not_found", "response not found: " + id));
            }
            return ResponseEntity.ok(responseJobHandler.toApiResponse(maybe.get(), false));
        }
        if (parts.length == 5 && "cancel".equals(parts[4])) {
            if (!responseJobHandler.cancel(id)) {
                return ResponseEntity.status(404).body(errorBody("not_found",
                        "no cancellable response with id: " + id));
            }
            return ResponseEntity.ok(Map.of("id", id, "object", "response", "status", "cancelled"));
        }
        throw new IllegalArgumentException("unknown azure responses path: " + path);
    }

    private Object handleAzureBatches(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String inputFileId = stringOr(body.get("input_file_id"), "file-missing");
        String endpoint = stringOr(body.get("endpoint"), "/v1/chat/completions");
        String completionWindow = stringOr(body.get("completion_window"), "24h");
        var job = batchJobHandler.create(request.getVendorSlug(), inputFileId, endpoint, completionWindow);
        return ResponseEntity.ok(batchJobHandler.toApiResponse(job));
    }

    private Object handleAzureBatchesSubpath(String path, MockRequest request) {
        String[] parts = path.split("/");
        String id = parts.length >= 4 ? parts[3] : "";
        if (parts.length == 4) {
            var maybe = batchJobHandler.get(id);
            if (maybe.isEmpty()) {
                return ResponseEntity.status(404).body(errorBody("not_found", "batch not found: " + id));
            }
            return ResponseEntity.ok(batchJobHandler.toApiResponse(maybe.get()));
        }
        if (parts.length == 5 && "cancel".equals(parts[4])) {
            if (!batchJobHandler.cancel(id)) {
                return ResponseEntity.status(404).body(errorBody("not_found",
                        "no cancellable batch with id: " + id));
            }
            return ResponseEntity.ok(Map.of("id", id, "object", "batch", "status", "cancelling"));
        }
        throw new IllegalArgumentException("unknown azure batches path: " + path);
    }

    @SuppressWarnings("unchecked")
    private String extractAzureInputUserText(Map<String, Object> body) {
        Object input = body.get("input");
        if (input instanceof String s) return s;
        if (input instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object item = list.get(i);
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    if (!"user".equals(m.get("role"))) continue;
                    Object content = m.get("content");
                    if (content instanceof List<?> parts) {
                        for (Object p : parts) {
                            if (p instanceof Map && ((Map<?, ?>) p).containsKey("text")) {
                                return String.valueOf(((Map<?, ?>) p).get("text"));
                            }
                        }
                    } else if (content != null) {
                        return String.valueOf(content);
                    }
                }
            }
        }
        return "(empty)";
    }

    private String firstChatModel(VendorConfig vendor) {
        if (vendor.getDeployments() != null && !vendor.getDeployments().isEmpty()) {
            return vendor.getDeployments().get(0).getModel();
        }
        return "gpt-4o";
    }

    private Object handleChat(VendorConfig vendor, String path, MockRequest request) {
        String deployment = extractDeployment(path);
        String model = mapDeployment(vendor, deployment);
        Map<String, Object> body = asMap(request.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        boolean hasTools = body.containsKey("tools")
                && body.get("tools") instanceof List
                && !((List<?>) body.get("tools")).isEmpty();
        return ResponseEntity.ok(generator.chatCompletion(model, messages, hasTools));
    }

    private String extractDeployment(String path) {
        // /openai/deployments/{dep}/chat/completions
        int start = path.indexOf("/deployments/") + "/deployments/".length();
        int end = path.indexOf("/", start);
        return start >= 0 && end > start ? path.substring(start, end) : "default";
    }

    /**
     * {@code POST /openai/deployments/{dep}/embeddings} — Azure 版 OpenAI 形态 embedding.
     */
    private Object handleEmbeddings(VendorConfig vendor, String path, MockRequest request) {
        String model = mapDeployment(vendor, extractDeployment(path));
        Map<String, Object> body = asMap(request.getBody());
        Object input = body.get("input");
        List<String> inputs = new java.util.ArrayList<>();
        if (input instanceof String s) {
            inputs.add(s);
        } else if (input instanceof List<?> list) {
            for (Object x : list) inputs.add(String.valueOf(x));
        }
        return ResponseEntity.ok(embeddings.embeddingsResponse(model, inputs, 1536));
    }

    private String mapDeployment(VendorConfig vendor, String deployment) {
        if (vendor.getDeployments() == null) return deployment;
        return vendor.getDeployments().stream()
                .filter(d -> d.getDeployment().equals(deployment))
                .map(DeploymentConfig::getModel)
                .findFirst()
                .orElse(deployment);
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

    private Map<String, Object> errorBody(String type, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        return body;
    }
}
