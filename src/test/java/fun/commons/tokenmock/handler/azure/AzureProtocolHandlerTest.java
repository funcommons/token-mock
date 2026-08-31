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
import org.springframework.http.ResponseEntity;

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

        handler = new AzureProtocolHandler(registry, new ResponseGenerator(new fun.commons.tokenmock.core.TokenEstimator()),
                new EmbeddingGenerator());
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
}