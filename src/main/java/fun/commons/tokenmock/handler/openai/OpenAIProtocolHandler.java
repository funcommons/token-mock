package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible protocol handler.
 * <p>
 * Endpoints (relative to vendor slug prefix):
 *   POST /v1/chat/completions        (non-stream + stream=true)
 *   POST /v1/embeddings
 *   GET  /v1/models
 */
@Component
public class OpenAIProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final ResponseGenerator generator;
    private final EmbeddingGenerator embeddingGenerator;
    private final AudioImageHandler audioImageHandler;
    private final fun.commons.tokenmock.handler.video.VideoJobHandler videoJobHandler;

    public OpenAIProtocolHandler(VendorRegistry registry, ResponseGenerator generator,
                                  EmbeddingGenerator embeddingGenerator,
                                  AudioImageHandler audioImageHandler,
                                  fun.commons.tokenmock.handler.video.VideoJobHandler videoJobHandler) {
        this.registry = registry;
        this.generator = generator;
        this.embeddingGenerator = embeddingGenerator;
        this.audioImageHandler = audioImageHandler;
        this.videoJobHandler = videoJobHandler;
    }

    @Override
    public String protocol() {
        return "openai";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!registry.authenticate(vendor, request.getAuthHeader())) {
            return ResponseEntity.status(401).body(errorBody("invalid_api_key",
                    "Incorrect API key provided for vendor " + vendor.getSlug()));
        }

        String path = normalizePath(request.getPath());
        if (path.startsWith("/v1/videos")) {
            return handleVideo(vendor, path, request);
        }
        return switch (path) {
            case "/v1/chat/completions" -> handleChat(vendor, request);
            case "/v1/embeddings" -> handleEmbeddings(vendor, request);
            case "/v1/audio/speech" -> audioImageHandler.handleAudioSpeech(request);
            case "/v1/audio/transcriptions", "/v1/audio/translations" ->
                    audioImageHandler.handleAudioTranscription(request);
            case "/v1/images/generations", "/v1/images/edits", "/v1/images/variations" ->
                    audioImageHandler.handleImages(request);
            case "/v1/models" -> handleListModels(vendor);
            default -> throw new IllegalArgumentException("unknown path: " + path);
        };
    }

    private Object handleVideo(VendorConfig vendor, String path, MockRequest request) {
        if (path.equals("/v1/videos")) {
            Map<String, Object> body = asMap(request.getBody());
            String model = stringOr(body.get("model"), "sora-2");
            String prompt = stringOr(body.get("prompt"), "(empty)");
            return videoJobHandler.submit(vendor.getSlug(), model, prompt);
        }
        String[] parts = path.split("/");
        if (parts.length == 4 && parts[3].startsWith("video_mock_")) {
            return videoJobHandler.getStatus(parts[3]);
        }
        if (parts.length == 5 && "content".equals(parts[4])) {
            return videoJobHandler.downloadContent(parts[3]);
        }
        throw new IllegalArgumentException("unknown video path: " + path);
    }

    private Object handleChat(VendorConfig vendor, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), firstChatModel(vendor));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        boolean hasTools = body.containsKey("tools")
                && body.get("tools") instanceof List
                && !((List<?>) body.get("tools")).isEmpty();
        boolean stream = Boolean.TRUE.equals(body.get("stream"));

        if (stream) {
            boolean includeUsage = extractIncludeUsage(body);
            return buildSseStream(model, messages, includeUsage);
        }
        Map<String, Object> response = generator.chatCompletion(model, messages, hasTools);
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private boolean extractIncludeUsage(Map<String, Object> body) {
        Object so = body.get("stream_options");
        if (!(so instanceof Map<?, ?> raw)) {
            return false;
        }
        Object flag = ((Map<String, Object>) raw).get("include_usage");
        return Boolean.TRUE.equals(flag);
    }

    private SseEmitter buildSseStream(String model, List<Map<String, Object>> messages, boolean includeUsage) {
        SseEmitter emitter = new SseEmitter(60_000L);
        List<Map<String, Object>> chunks = generator.chatCompletionStream(model, messages, includeUsage);
        Thread.startVirtualThread(() -> {
            try {
                for (Map<String, Object> chunk : chunks) {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(toJson(chunk), MediaType.APPLICATION_JSON));
                }
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private Object handleEmbeddings(VendorConfig vendor, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), firstEmbedModel(vendor));
        ModelConfig mc = vendor.findModel(model);
        int dims = (mc != null && mc.getDimensions() > 0) ? mc.getDimensions() : 1536;

        Object input = body.get("input");
        List<String> inputs;
        if (input instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) input;
            inputs = raw.stream().map(String::valueOf).toList();
        } else {
            inputs = List.of(String.valueOf(input == null ? "" : input));
        }

        return ResponseEntity.ok(embeddingGenerator.embeddingsResponse(model, inputs, dims));
    }

    private Object handleListModels(VendorConfig vendor) {
        List<String> codes = vendor.getModels() == null ? List.of()
                : vendor.getModels().stream().map(ModelConfig::getCode).toList();
        return ResponseEntity.ok(generator.listModels(vendor.getSlug(), codes));
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        int idx = path.indexOf("/v1/");
        return idx >= 0 ? path.substring(idx) : path;
    }

    private Map<String, Object> asMap(Object body) {
        if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) body;
            return m;
        }
        return new LinkedHashMap<>();
    }

    private String stringOr(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String firstChatModel(VendorConfig vendor) {
        if (vendor.getModels() == null || vendor.getModels().isEmpty()) {
            return "gpt-4o-mini";
        }
        return vendor.getModels().stream()
                .filter(m -> "chat".equals(m.getModality()))
                .map(ModelConfig::getCode)
                .findFirst()
                .orElse(vendor.getModels().get(0).getCode());
    }

    private String firstEmbedModel(VendorConfig vendor) {
        if (vendor.getModels() == null || vendor.getModels().isEmpty()) {
            return "text-embedding-3-small";
        }
        return vendor.getModels().stream()
                .filter(m -> "embed".equals(m.getModality()))
                .map(ModelConfig::getCode)
                .findFirst()
                .orElse(vendor.getModels().get(0).getCode());
    }

    private String toJson(Map<String, Object> data) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
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
