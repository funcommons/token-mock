package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.PlaceholderResources;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.registry.VendorRegistry;
import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.handler.video.VideoJobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIProtocolHandlerStreamTest {

    private OpenAIProtocolHandler handler;

    @BeforeEach
    void setup() {
        MockProperties props = new MockProperties();
        VendorConfig openai = new VendorConfig();
        openai.setSlug("openai");
        openai.setProtocol("openai");
        openai.setKey("sk-openai-xxx");
        ModelConfig m = new ModelConfig();
        m.setCode("gpt-4o");
        m.setModality("chat");
        openai.setModels(List.of(m));
        props.setVendors(List.of(openai));
        props.setAdminToken("admin-secret");
        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        TokenEstimator estimator = new TokenEstimator();
        ResponseGenerator generator = new ResponseGenerator(estimator);
        EmbeddingGenerator embed = new EmbeddingGenerator();
        PlaceholderResources ph = new PlaceholderResources();
        AudioImageHandler audioImage = new AudioImageHandler(ph);
        VideoJobHandler video = new VideoJobHandler(ph);
        handler = new OpenAIProtocolHandler(registry, generator, embed, audioImage, video);
    }

    @Test
    void stream_with_include_usage_returns_sse_emitter() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "你好世界")));
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));

        MockRequest req = new MockRequest("openai", "Bearer sk-openai-xxx", "/v1/chat/completions", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(SseEmitter.class);
    }

    @Test
    void stream_without_stream_options_still_returns_emitter() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "你好")));
        body.put("stream", true);

        MockRequest req = new MockRequest("openai", "Bearer sk-openai-xxx", "/v1/chat/completions", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(SseEmitter.class);
    }
}
