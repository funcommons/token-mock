package fun.commons.tokenmock.handler.anthropic;

import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.SseChunker;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Messages API.
 * <p>
 * Endpoints (relative to /anthropic):
 *   POST /v1/messages          (non-stream)
 *   POST /v1/messages?stream=true  (SSE)
 *   POST /v1/messages/count_tokens  (input_tokens 预估算)
 *   GET  /v1/models
 * <p>
 * Auth: x-api-key header (not Bearer).
 */
@Component
public class AnthropicProtocolHandler implements ProtocolHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VendorRegistry registry;
    private final TokenEstimator estimator;
    private final SseChunker sseChunker;

    public AnthropicProtocolHandler(VendorRegistry registry, TokenEstimator estimator, SseChunker sseChunker) {
        this.registry = registry;
        this.estimator = estimator;
        this.sseChunker = sseChunker;
    }

    @Override
    public String protocol() {
        return "anthropic";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!isAuthenticated(vendor, request)) {
            return ResponseEntity.status(401).body(Map.of(
                    "type", "error",
                    "error", Map.of("type", "authentication_error", "message", "invalid x-api-key")
            ));
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        return switch (path) {
            case "/v1/messages" -> handleMessages(request);
            case "/v1/messages/count_tokens" -> handleCountTokens(request);
            case "/v1/models" -> handleListModels(vendor);
            default -> throw new IllegalArgumentException("unknown path: " + path);
        };
    }

    /**
     * {@code POST /v1/messages/count_tokens} — token 预算.
     * <p>Claude SDK 在上下文管理 / 长对话限流校验时会调;返回的 shape 与
     * 真实厂商对齐:{@code {input_tokens:N}}.
     */
    private Object handleCountTokens(MockRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = asMap(request.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        String userText = extractUserText(messages);
        int inputTokens = estimator.estimate(userText);
        return ResponseEntity.ok(Map.of("input_tokens", inputTokens));
    }

    private boolean isAuthenticated(VendorConfig vendor, MockRequest req) {
        String auth = req.getAuthHeader();
        if (auth == null) return false;
        if (auth.startsWith("Bearer ")) {
            return vendor.getKey().equals(auth.substring(7).trim());
        }
        return vendor.getKey().equals(auth.trim());
    }

    @SuppressWarnings("unchecked")
    private Object handleMessages(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), "claude-3-5-sonnet-20241022");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());

        String userText = extractUserText(messages);
        String content = "[mock] 你说的内容是: " + userText;
        int inputTokens = estimator.estimate(userText);
        int outputTokens = estimator.estimate(content);

        if (Boolean.TRUE.equals(body.get("stream"))) {
            return buildStreamEmitter(model, content, inputTokens, outputTokens);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "msg_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.put("type", "message");
        response.put("role", "assistant");
        response.put("model", model);
        response.put("content", List.of(Map.of("type", "text", "text", content)));
        response.put("stop_reason", "end_turn");
        response.put("stop_sequence", null);
        response.put("usage", Map.of(
                "input_tokens", inputTokens,
                "output_tokens", outputTokens
        ));
        return ResponseEntity.ok(response);
    }

    private SseEmitter buildStreamEmitter(String model, String content,
                                           int inputTokens, int outputTokens) {
        SseEmitter emitter = new SseEmitter(60_000L);
        List<Map<String, Object>> frames = buildStreamFrames(model, content, inputTokens, outputTokens);
        Thread.startVirtualThread(() -> {
            try {
                for (Map<String, Object> frame : frames) {
                    String type = String.valueOf(frame.get("type"));
                    sendEvent(emitter, type, frame);
                }
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 构造 Anthropic SSE 帧序列(供测试断言;生产代码用 buildStreamEmitter).
     * <p>6 类帧: message_start / content_block_start / content_block_delta×N /
     * content_block_stop / message_delta / message_stop.
     */
    public List<Map<String, Object>> buildStreamFrames(String model, String content,
                                                        int inputTokens, int outputTokens) {
        String messageId = "msg_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        List<Map<String, Object>> frames = new java.util.ArrayList<>();

        Map<String, Object> startUsage = new LinkedHashMap<>();
        startUsage.put("input_tokens", inputTokens);
        startUsage.put("output_tokens", 0);
        startUsage.put("cache_read_input_tokens", 0);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", List.of());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", startUsage);
        Map<String, Object> messageStart = new LinkedHashMap<>();
        messageStart.put("type", "message_start");
        messageStart.put("message", message);
        frames.add(messageStart);

        Map<String, Object> blockStart = new LinkedHashMap<>();
        blockStart.put("type", "content_block_start");
        blockStart.put("index", 0);
        blockStart.put("content_block", Map.of("type", "text", "text", ""));
        frames.add(blockStart);

        for (String piece : sseChunker.chunk(content)) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("type", "content_block_delta");
            delta.put("index", 0);
            delta.put("delta", Map.of("type", "text_delta", "text", piece));
            frames.add(delta);
        }

        Map<String, Object> blockStop = new LinkedHashMap<>();
        blockStop.put("type", "content_block_stop");
        blockStop.put("index", 0);
        frames.add(blockStop);

        Map<String, Object> stopDelta = new LinkedHashMap<>();
        stopDelta.put("stop_reason", "end_turn");
        stopDelta.put("stop_sequence", null);
        Map<String, Object> messageDelta = new LinkedHashMap<>();
        messageDelta.put("type", "message_delta");
        messageDelta.put("delta", stopDelta);
        messageDelta.put("usage", Map.of("output_tokens", outputTokens));
        frames.add(messageDelta);

        frames.add(Map.of("type", "message_stop"));
        return frames;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) throws IOException {
        emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(eventName)
                .data(toJson(payload), MediaType.APPLICATION_JSON));
    }

    private String toJson(Map<String, Object> data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    private Object handleListModels(VendorConfig vendor) {
        List<Map<String, Object>> data = vendor.getModels() == null ? List.of()
                : vendor.getModels().stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getCode(),
                        "display_name", m.getCode(),
                        "type", "model",
                        "created_at", Instant.now().toString()))
                .toList();
        return ResponseEntity.ok(Map.of("data", data));
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

    private String extractUserText(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return "(空)";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("user".equals(m.get("role"))) {
                Object c = m.get("content");
                if (c == null) return "(空)";
                if (c instanceof List<?>) {
                    return ((List<?>) c).stream()
                            .filter(x -> x instanceof Map && "text".equals(((Map<?, ?>) x).get("type")))
                            .map(x -> String.valueOf(((Map<?, ?>) x).get("text")))
                            .reduce("", (a, b) -> a + " " + b);
                }
                return String.valueOf(c);
            }
        }
        return "(空)";
    }
}
