package fun.commons.tokenmock.handler.bedrock;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.SseChunker;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockConverseTest {

    private BedrockProtocolHandler handler;

    @BeforeEach
    void setup() {
        MockProperties props = new MockProperties();
        VendorConfig bedrock = new VendorConfig();
        bedrock.setSlug("bedrock");
        bedrock.setProtocol("bedrock");
        bedrock.setKey("aws4-key");
        ModelConfig m = new ModelConfig();
        m.setCode("anthropic.claude-3-5-sonnet");
        m.setModality("chat");
        bedrock.setModels(List.of(m));
        props.setVendors(List.of(bedrock));
        props.setAdminToken("admin-secret");
        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        handler = new BedrockProtocolHandler(registry, new TokenEstimator(), new SseChunker());
    }

    @Test
    void converse_returns_message_with_text_block() {
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("text", "hi"))
                )));
        MockRequest req = new MockRequest("bedrock",
                "AWS4-HMAC-SHA256 Credential=aws4-key/...",
                "/bedrock/model/anthropic.claude-3-5-sonnet/converse", body);
        Object resp = handler.handle(req);

        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        Map<String, Object> output = (Map<String, Object>) rb.get("output");
        Map<String, Object> message = (Map<String, Object>) output.get("message");
        assertThat(message.get("role")).isEqualTo("assistant");
        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        assertThat((String) content.get(0).get("text")).startsWith("[mock]");
        assertThat(rb.get("stopReason")).isEqualTo("end_turn");
        Map<String, Object> usage = (Map<String, Object>) rb.get("usage");
        assertThat(usage).containsKeys("inputTokens", "outputTokens", "totalTokens");
    }

    @Test
    void converse_stream_returns_sse_emitter() {
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("text", "hi")))));
        MockRequest req = new MockRequest("bedrock",
                "AWS4-HMAC-SHA256 Credential=aws4-key/...",
                "/bedrock/model/anthropic.claude-3-5-sonnet/converse-stream", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(SseEmitter.class);
    }

    @Test
    void converse_stream_frames_cover_lifecycle() {
        // exercise the frame builder directly so we don't depend on SseEmitter threading
        var frames = invokeBuildFrames(handler, "hello world", 3, 5);
        List<String> events = frames.stream().map(f -> (String) f.get("__event")).toList();
        // start -> blockStart -> >=1 delta (depends on chunker granularity) -> blockStop -> messageStop -> metadata
        assertThat(events.get(0)).isEqualTo("messageStart");
        assertThat(events.get(1)).isEqualTo("contentBlockStart");
        assertThat(events.get(events.size() - 2)).isEqualTo("messageStop");
        assertThat(events.get(events.size() - 1)).isEqualTo("metadata");
        long deltaCount = events.stream().filter("contentBlockDelta"::equals).count();
        assertThat(deltaCount).isGreaterThanOrEqualTo(1);
        // metadata has usage totals
        Map<String, Object> metadata = frames.get(frames.size() - 1);
        Map<String, Object> usage = (Map<String, Object>) metadata.get("usage");
        assertThat((int) usage.get("inputTokens")).isEqualTo(3);
        assertThat((int) usage.get("outputTokens")).isEqualTo(5);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeBuildFrames(BedrockProtocolHandler h, String content, int in, int out) {
        try {
            var m = BedrockProtocolHandler.class.getDeclaredMethod(
                    "buildConverseStreamFrames", String.class, int.class, int.class);
            m.setAccessible(true);
            return (List<Map<String, Object>>) m.invoke(h, content, in, out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}