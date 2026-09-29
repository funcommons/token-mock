package fun.commons.tokenmock.handler.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.SseChunker;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Bedrock (simplified — skips SigV4 signature verification).
 * <p>
 * Supported endpoints:
 * <ul>
 *   <li>{@code POST /model/{vendor}.{model}/invoke} — legacy model-native payload
 *   <li>{@code POST /model/{modelId}/converse} — Converse API, unified schema
 *   <li>{@code POST /model/{modelId}/converse-stream} — Converse + SSE stream
 * </ul>
 * Auth: any {@code Authorization} header containing {@code AWS4-HMAC-SHA256}
 * prefix OR matching {@code vendor.key} is accepted.
 * <p>
 * Converse uses content blocks ({@code text|image|toolUse|toolResult}) and
 * 4 top-level config objects; we honor text blocks and skip the others, just
 * like every other mock layer.
 */
@Component
public class BedrockProtocolHandler implements ProtocolHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VendorRegistry registry;
    private final TokenEstimator estimator;
    private final SseChunker sseChunker;

    public BedrockProtocolHandler(VendorRegistry registry, TokenEstimator estimator, SseChunker sseChunker) {
        this.registry = registry;
        this.estimator = estimator;
        this.sseChunker = sseChunker;
    }

    @Override
    public String protocol() {
        return "bedrock";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!isAuthenticated(vendor, request)) {
            return ResponseEntity.status(401).body(Map.of(
                    "message", "AWS signature verification failed"
            ));
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        // /model/{v}.{m}/invoke (legacy)
        if (path.startsWith("/model/") && path.endsWith("/invoke") && !path.contains("/converse")) {
            return handleInvoke(request);
        }
        // /model/{modelId}/converse
        if (path.endsWith("/converse") && !path.endsWith("/converse-stream")) {
            return handleConverse(path, request);
        }
        if (path.endsWith("/converse-stream")) {
            return handleConverseStream(path, request);
        }
        throw new IllegalArgumentException("unknown bedrock path: " + path);
    }

    private boolean isAuthenticated(VendorConfig vendor, MockRequest req) {
        String auth = req.getAuthHeader();
        if (auth == null) return false;
        if (auth.startsWith("AWS4-HMAC-SHA256")) return true;
        return registry.authenticate(vendor, auth);
    }

    private Object handleInvoke(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String prompt = stringOr(body.get("prompt"), "(empty)");
        String content = "[mock] 你说的内容是: " + prompt;

        return ResponseEntity.ok(Map.of(
                "completion", content,
                "stop_reason", "end_turn",
                "usage", Map.of(
                        "input_tokens", estimator.estimate(prompt),
                        "output_tokens", estimator.estimate(content)
                )
        ));
    }

    /**
     * Converse synchronous: extracts the last user message text from
     * {@code messages[].content[]}, echoes it back as a {@code message} with one
     * {@code text} block, and reports token usage.
     */
    private Object handleConverse(String path, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String userText = extractUserText(body);
        String content = "[mock] 你说的内容是: " + userText;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("message", Map.of(
                "role", "assistant",
                "content", List.of(Map.of("text", content))
        ));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("output", output);
        response.put("stopReason", "end_turn");
        response.put("usage", Map.of(
                "inputTokens", estimator.estimate(userText),
                "outputTokens", estimator.estimate(content),
                "totalTokens", estimator.total(estimator.estimate(userText), estimator.estimate(content))
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Converse streaming: emit a small SSE event stream mirroring real Bedrock
     * shape:
     * <pre>
     * messageStart    {role:assistant}
     * contentBlockDelta×N {delta:{text:"..."}}
     * contentBlockStop  {}
     * messageStop       {stopReason:end_turn}
     * metadata          {usage:{inputTokens,outputTokens,totalTokens}}
     * </pre>
     * Each event is named per AWS's documented {@code :} prefix.
     */
    private Object handleConverseStream(String path, MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String userText = extractUserText(body);
        String content = "[mock] 你说的内容是: " + userText;
        int inputTokens = estimator.estimate(userText);
        int outputTokens = estimator.estimate(content);

        SseEmitter emitter = new SseEmitter(60_000L);
        List<Map<String, Object>> frames = buildConverseStreamFrames(content, inputTokens, outputTokens);
        // 2026-09-22 挂账池 D8：钉 17 基线（mmagix-token 测试 JVM=17，release 21 class(major 65)
        // 会被测试 JVM 拒载 UnsupportedClassVersionError）——startVirtualThread 为 21 API，
        // mock 并发量级下平台线程语义等价（fire-and-forget 帧泵）。
        new Thread(() -> {
            try {
                for (Map<String, Object> frame : frames) {
                    String name = String.valueOf(frame.get("__event"));
                    Map<String, Object> payload = new LinkedHashMap<>(frame);
                    payload.remove("__event");
                    emitter.send(SseEmitter.event().name(name)
                            .data(toJson(payload), MediaType.APPLICATION_JSON));
                }
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    private List<Map<String, Object>> buildConverseStreamFrames(String content, int inputTokens, int outputTokens) {
        List<Map<String, Object>> frames = new ArrayList<>();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("__event", "messageStart");
        start.put("role", "assistant");
        frames.add(start);

        Map<String, Object> blockStart = new LinkedHashMap<>();
        blockStart.put("__event", "contentBlockStart");
        blockStart.put("start", Map.of());
        blockStart.put("contentBlockIndex", 0);
        frames.add(blockStart);

        for (String piece : sseChunker.chunk(content)) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("__event", "contentBlockDelta");
            delta.put("delta", Map.of("text", piece));
            delta.put("contentBlockIndex", 0);
            frames.add(delta);
        }

        Map<String, Object> blockStop = new LinkedHashMap<>();
        blockStop.put("__event", "contentBlockStop");
        blockStop.put("contentBlockIndex", 0);
        frames.add(blockStop);

        Map<String, Object> messageStop = new LinkedHashMap<>();
        messageStop.put("__event", "messageStop");
        messageStop.put("stopReason", "end_turn");
        frames.add(messageStop);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("__event", "metadata");
        metadata.put("usage", Map.of(
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "totalTokens", inputTokens + outputTokens
        ));
        metadata.put("metrics", Map.of("latencyMs", 0));
        frames.add(metadata);
        return frames;
    }

    @SuppressWarnings("unchecked")
    private String extractUserText(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List)) return "(empty)";
        for (int i = ((List<?>) messages).size() - 1; i >= 0; i--) {
            Object m = ((List<?>) messages).get(i);
            if (m instanceof Map && "user".equals(((Map<?, ?>) m).get("role"))) {
                Object content = ((Map<String, Object>) m).get("content");
                if (content instanceof List<?> parts) {
                    for (Object p : parts) {
                        if (p instanceof Map && ((Map<?, ?>) p).containsKey("text")) {
                            return String.valueOf(((Map<?, ?>) p).get("text"));
                        }
                    }
                }
            }
        }
        return "(empty)";
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
        return v == null ? fb : String.valueOf(v);
    }

    private String toJson(Map<String, Object> data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }
}