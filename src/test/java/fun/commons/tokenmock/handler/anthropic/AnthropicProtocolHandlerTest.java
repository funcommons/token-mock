package fun.commons.tokenmock.handler.anthropic;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.SseChunker;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.registry.InMemoryFileStore;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProtocolHandlerTest {

    private AnthropicProtocolHandler handler;

    @BeforeEach
    void setup() {
        MockProperties props = new MockProperties();
        VendorConfig anthropic = new VendorConfig();
        anthropic.setSlug("anthropic");
        anthropic.setProtocol("anthropic");
        anthropic.setKey("sk-ant-xxx");
        ModelConfig m = new ModelConfig();
        m.setCode("claude-3-5-sonnet-20241022");
        m.setModality("chat");
        anthropic.setModels(List.of(m));
        props.setVendors(List.of(anthropic));
        props.setAdminToken("admin-secret");
        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        handler = new AnthropicProtocolHandler(registry, new TokenEstimator(), new SseChunker(),
                new InMemoryFileStore());
    }

    @Test
    void protocol_name_is_anthropic() {
        assertThat(handler.protocol()).isEqualTo("anthropic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void non_stream_messages_returns_response_with_usage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-3-5-sonnet-20241022");
        body.put("messages", List.of(Map.of("role", "user", "content", "你好")));

        MockRequest req = new MockRequest("anthropic", "Bearer sk-ant-xxx", "/anthropic/v1/messages", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        Map<String, Object> usage = (Map<String, Object>) rb.get("usage");
        assertThat((int) usage.get("input_tokens")).isGreaterThan(0);
        assertThat((int) usage.get("output_tokens")).isGreaterThan(0);
    }

    @Test
    void stream_messages_returns_sse_emitter() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-3-5-sonnet-20241022");
        body.put("messages", List.of(Map.of("role", "user", "content", "你好")));
        body.put("stream", true);

        MockRequest req = new MockRequest("anthropic", "Bearer sk-ant-xxx", "/anthropic/v1/messages", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(SseEmitter.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_frames_contain_message_start_with_input_tokens_and_message_delta_with_output_tokens() {
        List<Map<String, Object>> frames = handler.buildStreamFrames(
                "claude-3-5-sonnet-20241022", "响应内容", 5, 10
        );

        Map<String, Object> messageStart = frames.stream()
                .filter(f -> "message_start".equals(f.get("type"))).findFirst().orElseThrow();
        Map<String, Object> message = (Map<String, Object>) messageStart.get("message");
        Map<String, Object> startUsage = (Map<String, Object>) message.get("usage");
        assertThat((int) startUsage.get("input_tokens")).isEqualTo(5);
        assertThat(startUsage).containsKey("cache_read_input_tokens");

        Map<String, Object> messageDelta = frames.stream()
                .filter(f -> "message_delta".equals(f.get("type"))).findFirst().orElseThrow();
        Map<String, Object> deltaUsage = (Map<String, Object>) messageDelta.get("usage");
        assertThat((int) deltaUsage.get("output_tokens")).isEqualTo(10);

        assertThat(frames.stream().map(f -> f.get("type")))
                .contains("content_block_start", "content_block_delta", "content_block_stop", "message_stop");
    }

    @Test
    @SuppressWarnings("unchecked")
    void count_tokens_returns_input_token_estimate() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-3-5-sonnet-20241022");
        body.put("messages", List.of(Map.of("role", "user", "content", "你好世界")));

        MockRequest req = new MockRequest(
                "anthropic", "Bearer sk-ant-xxx",
                "/anthropic/v1/messages/count_tokens", body);
        Object resp = handler.handle(req);

        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        assertThat((int) rb.get("input_tokens")).isGreaterThan(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void files_upload_returns_id_with_underscore_prefix() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("__file_name__", "doc.pdf");
        body.put("__file_size__", 100L);

        MockRequest req = new MockRequest(
                "anthropic", "Bearer sk-ant-xxx",
                "/anthropic/v1/files", "POST", body);
        Object resp = handler.handle(req);

        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        assertThat((String) rb.get("id")).startsWith("file_");
        assertThat(rb).containsEntry("type", "file");
        assertThat(rb).containsEntry("filename", "doc.pdf");
    }
}
