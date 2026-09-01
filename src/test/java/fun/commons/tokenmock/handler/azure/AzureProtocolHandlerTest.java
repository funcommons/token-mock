package fun.commons.tokenmock.handler.azure;

import fun.commons.tokenmock.config.DeploymentConfig;
import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AzureProtocolHandlerTest {

    private AzureProtocolHandler handler;

    @BeforeEach
    void setup() {
        MockProperties props = new MockProperties();
        VendorConfig azure = new VendorConfig();
        azure.setSlug("azure");
        azure.setProtocol("azure");
        azure.setKey("azure-key");
        DeploymentConfig dep = new DeploymentConfig();
        dep.setDeployment("gpt-4o-deployment");
        dep.setModel("gpt-4o");
        azure.setDeployments(List.of(dep));
        props.setVendors(List.of(azure));
        props.setAdminToken("admin-secret");
        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        handler = new AzureProtocolHandler(registry,
                new ResponseGenerator(new fun.commons.tokenmock.core.TokenEstimator()),
                new EmbeddingGenerator(),
                new fun.commons.tokenmock.handler.openai.AudioImageHandler(new fun.commons.tokenmock.core.PlaceholderResources()),
                new fun.commons.tokenmock.handler.video.VideoJobHandler(new fun.commons.tokenmock.core.PlaceholderResources()),
                new fun.commons.tokenmock.handler.openai.ResponseJobHandler(),
                new fun.commons.tokenmock.handler.openai.BatchJobHandler());
    }

    @Test
    void protocol_name_is_azure() {
        assertThat(handler.protocol()).isEqualTo("azure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddings_returns_vector() {
        Map<String, Object> body = Map.of("input", "hello");
        MockRequest req = new MockRequest("azure", "Bearer azure-key",
                "/azure/openai/deployments/gpt-4o-deployment/embeddings?api-version=2024-02-15-preview", body);
        Object resp = handler.handle(req);

        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        assertThat(rb).containsKey("data");
        List<Map<String, Object>> data = (List<Map<String, Object>>) rb.get("data");
        assertThat(data).isNotEmpty();
        assertThat((List<Double>) data.get(0).get("embedding")).hasSize(1536);
    }

    @Test
    @SuppressWarnings("unchecked")
    void v1_responses_returns_response_object() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o");
        body.put("input", "hi");
        MockRequest req = new MockRequest("azure", "Bearer azure-key",
                "/azure/openai/v1/responses?api-version=preview", body);
        Object resp = handler.handle(req);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        assertThat(rb.get("object")).isEqualTo("response");
        assertThat((String) rb.get("id")).startsWith("resp_");
    }

    @Test
    @SuppressWarnings("unchecked")
    void v1_batches_create_returns_batch_id() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input_file_id", "file-abc");
        body.put("endpoint", "/v1/chat/completions");
        body.put("completion_window", "24h");
        MockRequest req = new MockRequest("azure", "Bearer azure-key",
                "/azure/openai/v1/batches?api-version=preview", body);
        Object resp = handler.handle(req);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        assertThat(rb.get("object")).isEqualTo("batch");
        assertThat((String) rb.get("id")).startsWith("batch_");
    }

    @Test
    @SuppressWarnings("unchecked")
    void v1_audio_speech_returns_mp3() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "tts-1");
        body.put("input", "hi");
        MockRequest req = new MockRequest("azure", "Bearer azure-key",
                "/azure/openai/v1/audio/speech?api-version=preview", body);
        Object resp = handler.handle(req);
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        assertThat(re.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("audio/mpeg"));
        assertThat((byte[]) re.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void v1_images_generations_returns_url() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", "a cat");
        body.put("n", 1);
        MockRequest req = new MockRequest("azure", "Bearer azure-key",
                "/azure/openai/v1/images/generations?api-version=preview", body);
        Object resp = handler.handle(req);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        List<Map<String, Object>> data = (List<Map<String, Object>>) rb.get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsKey("url");
    }
}