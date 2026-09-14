package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.registry.InMemoryFileStore;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAIProtocolHandlerTest {

    private VendorRegistry registry;
    private OpenAIProtocolHandler handler;
    private VendorConfig openai;

    @BeforeEach
    void setup() {
        var props = new fun.commons.tokenmock.config.MockProperties();
        openai = new VendorConfig();
        openai.setSlug("openai");
        openai.setProtocol("openai");
        openai.setKey("sk-openai-xxx");
        ModelConfig m = new ModelConfig();
        m.setCode("gpt-4o");
        m.setModality("chat");
        ModelConfig embedModel = new ModelConfig();
        embedModel.setCode("text-embedding-3-small");
        embedModel.setModality("embed");
        openai.setModels(List.of(m, embedModel));
        props.setVendors(List.of(openai));
        props.setAdminToken("admin-secret");
        registry = new VendorRegistry(props);
        registry.init();

        TokenEstimator estimator = new TokenEstimator();
        ResponseGenerator generator = new ResponseGenerator(estimator);
        EmbeddingGenerator embed = new EmbeddingGenerator();
        fun.commons.tokenmock.core.PlaceholderResources ph = new fun.commons.tokenmock.core.PlaceholderResources();
        AudioImageHandler audioImage = new AudioImageHandler(ph);
        fun.commons.tokenmock.handler.video.VideoJobHandler video = new fun.commons.tokenmock.handler.video.VideoJobHandler(ph);
        handler = new OpenAIProtocolHandler(registry, generator, embed, audioImage, video, new InMemoryFileStore(), new ResponseJobHandler(), new BatchJobHandler(), new ImageJobHandler(ph));
    }

    @Test
    void protocol_name_is_openai() {
        assertThat(handler.protocol()).isEqualTo("openai");
    }

    @Test
    void chat_completion_returns_response_for_valid_request() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "你好")));

        MockRequest req = new MockRequest(
                "openai", "Bearer sk-openai-xxx", "/v1/chat/completions", body
        );

        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        assertThat(re.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> rb = (Map<String, Object>) re.getBody();
        assertThat(rb.get("object")).isEqualTo("chat.completion");
    }

    @Test
    void chat_completion_uses_default_model_when_missing() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        MockRequest req = new MockRequest("openai", "Bearer sk-openai-xxx", "/v1/chat/completions", body);
        Object result = handler.handle(req);
        ResponseEntity<?> re = (ResponseEntity<?>) result;

        @SuppressWarnings("unchecked")
        Map<String, Object> rb = (Map<String, Object>) re.getBody();
        assertThat(rb.get("model")).isEqualTo("gpt-4o");
    }

    @Test
    void list_models_endpoint_returns_vendor_models() {
        MockRequest req = new MockRequest("openai", "Bearer sk-openai-xxx", "/v1/models", null);
        Object result = handler.handle(req);
        ResponseEntity<?> re = (ResponseEntity<?>) result;

        assertThat(re.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) re.getBody();
        assertThat(body.get("object")).isEqualTo("list");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).hasSize(2);
    }

    @Test
    void handle_throws_when_invalid_path() {
        MockRequest req = new MockRequest("openai", "Bearer sk-openai-xxx", "/v1/unknown", Map.of());
        assertThatThrownBy(() -> handler.handle(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown path");
    }

    @Test
    void returns_401_for_wrong_key() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        MockRequest req = new MockRequest("openai", "Bearer wrong", "/v1/chat/completions", body);

        Object result = handler.handle(req);
        ResponseEntity<?> re = (ResponseEntity<?>) result;
        assertThat(re.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void responses_sync_returns_response_object() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("input", "hi");

        MockRequest req = new MockRequest(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/responses", "POST", body);
        Object resp = handler.handle(req);
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        Map<String, Object> rb = (Map<String, Object>) re.getBody();
        assertThat(rb.get("object")).isEqualTo("response");
        assertThat((String) rb.get("id")).startsWith("resp_");
        assertThat(rb.get("status")).isEqualTo("completed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void batches_create_returns_batch_id_in_progress() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input_file_id", "file-abc");
        body.put("endpoint", "/v1/chat/completions");
        body.put("completion_window", "24h");

        MockRequest req = new MockRequest(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/batches", "POST", body);
        Object resp = handler.handle(req);
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        Map<String, Object> rb = (Map<String, Object>) re.getBody();
        assertThat(rb.get("object")).isEqualTo("batch");
        assertThat((String) rb.get("id")).startsWith("batch_");
        assertThat(rb.get("status")).isEqualTo("in_progress");
    }
}
