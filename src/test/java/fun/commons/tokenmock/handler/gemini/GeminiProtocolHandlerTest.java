package fun.commons.tokenmock.handler.gemini;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiProtocolHandlerTest {

    private GeminiProtocolHandler handler;

    @BeforeEach
    void setup() {
        MockProperties props = new MockProperties();
        VendorConfig g = new VendorConfig();
        g.setSlug("gemini");
        g.setProtocol("gemini");
        g.setKey("AIza-key");
        ModelConfig m = new ModelConfig();
        m.setCode("gemini-1.5-pro");
        m.setModality("chat");
        g.setModels(List.of(m));
        props.setVendors(List.of(g));
        props.setAdminToken("admin-secret");
        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        handler = new GeminiProtocolHandler(registry, new TokenEstimator(), new EmbeddingGenerator());
    }

    @Test
    void protocol_name_is_gemini() {
        assertThat(handler.protocol()).isEqualTo("gemini");
    }

    @Test
    @SuppressWarnings("unchecked")
    void count_tokens_returns_total_tokens() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "你好")))));

        MockRequest req = new MockRequest("gemini", "Bearer AIza-key",
                "/gemini/v1/models/gemini-1.5-pro:countTokens", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        assertThat((int) rb.get("totalTokens")).isGreaterThan(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void embed_content_returns_values() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", Map.of("parts", List.of(Map.of("text", "hello"))));

        MockRequest req = new MockRequest("gemini", "Bearer AIza-key",
                "/gemini/v1/models/text-embedding-004:embedContent", body);
        Object resp = handler.handle(req);
        assertThat(resp).isInstanceOf(ResponseEntity.class);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        Map<String, Object> embedding = (Map<String, Object>) rb.get("embedding");
        List<Double> values = (List<Double>) embedding.get("values");
        assertThat(values).hasSize(768);
    }

    @Test
    @SuppressWarnings("unchecked")
    void batch_embed_contents_returns_one_per_request() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requests", List.of(
                Map.of("content", Map.of("parts", List.of(Map.of("text", "hello")))),
                Map.of("content", Map.of("parts", List.of(Map.of("text", "world"))))
        ));
        MockRequest req = new MockRequest("gemini", "Bearer AIza-key",
                "/gemini/v1/models/text-embedding-004:batchEmbedContents", body);
        Object resp = handler.handle(req);
        Map<String, Object> rb = (Map<String, Object>) ((ResponseEntity<?>) resp).getBody();
        List<Map<String, Object>> embs = (List<Map<String, Object>>) rb.get("embeddings");
        assertThat(embs).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void embed_is_deterministic() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", Map.of("parts", List.of(Map.of("text", "deterministic-input"))));

        MockRequest req1 = new MockRequest("gemini", "Bearer AIza-key",
                "/gemini/v1/models/text-embedding-004:embedContent", body);
        MockRequest req2 = new MockRequest("gemini", "Bearer AIza-key",
                "/gemini/v1/models/text-embedding-004:embedContent", body);
        Object r1 = handler.handle(req1);
        Object r2 = handler.handle(req2);
        List<Double> v1 = (List<Double>) ((Map<String, Object>) ((Map<String, Object>) ((ResponseEntity<?>) r1).getBody()).get("embedding")).get("values");
        List<Double> v2 = (List<Double>) ((Map<String, Object>) ((Map<String, Object>) ((ResponseEntity<?>) r2).getBody()).get("embedding")).get("values");
        assertThat(v1).isEqualTo(v2);
    }
}