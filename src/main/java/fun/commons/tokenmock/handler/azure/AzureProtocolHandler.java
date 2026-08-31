package fun.commons.tokenmock.handler.azure;

import fun.commons.tokenmock.config.DeploymentConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Azure OpenAI API (deployment-style URL).
 * <p>
 * Endpoints (relative to /azure):
 *   POST /openai/deployments/{deployment}/chat/completions?api-version=...
 *   POST /openai/deployments/{deployment}/embeddings?api-version=...
 * <p>
 * Auth: api-key header (treated like Bearer in mock).
 */
@Component
public class AzureProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final ResponseGenerator generator;
    private final EmbeddingGenerator embeddings;

    public AzureProtocolHandler(VendorRegistry registry, ResponseGenerator generator,
                                  EmbeddingGenerator embeddings) {
        this.registry = registry;
        this.generator = generator;
        this.embeddings = embeddings;
    }

    @Override
    public String protocol() {
        return "azure";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!registry.authenticate(vendor, request.getAuthHeader())) {
            return ResponseEntity.status(401).body(errorBody("invalid_api_key",
                    "Incorrect API key for azure"));
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        // strip query string for path matching
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        if (path.contains("/deployments/") && path.endsWith("/chat/completions")) {
            return handleChat(vendor, path, request);
        }
        if (path.contains("/deployments/") && path.endsWith("/embeddings")) {
            return handleEmbeddings(vendor, path, request);
        }
        throw new IllegalArgumentException("unknown azure path: " + path);
    }

    private Object handleChat(VendorConfig vendor, String path, MockRequest request) {
        String deployment = extractDeployment(path);
        String model = mapDeployment(vendor, deployment);
        Map<String, Object> body = asMap(request.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        boolean hasTools = body.containsKey("tools")
                && body.get("tools") instanceof List
                && !((List<?>) body.get("tools")).isEmpty();
        return ResponseEntity.ok(generator.chatCompletion(model, messages, hasTools));
    }

    private String extractDeployment(String path) {
        // /openai/deployments/{dep}/chat/completions
        int start = path.indexOf("/deployments/") + "/deployments/".length();
        int end = path.indexOf("/", start);
        return start >= 0 && end > start ? path.substring(start, end) : "default";
    }

    /**
     * {@code POST /openai/deployments/{dep}/embeddings} — Azure 版 OpenAI 形态 embedding.
     * <p>入参:{@code {input:string|string[]}};出参 shape 与 OpenAI 相同(便于 SDK 透明切换).
     */
    private Object handleEmbeddings(VendorConfig vendor, String path, MockRequest request) {
        String model = mapDeployment(vendor, extractDeployment(path));
        Map<String, Object> body = asMap(request.getBody());
        Object input = body.get("input");
        List<String> inputs = new java.util.ArrayList<>();
        if (input instanceof String s) {
            inputs.add(s);
        } else if (input instanceof List<?> list) {
            for (Object x : list) inputs.add(String.valueOf(x));
        }
        return ResponseEntity.ok(embeddings.embeddingsResponse(model, inputs, 1536));
    }

    private String mapDeployment(VendorConfig vendor, String deployment) {
        if (vendor.getDeployments() == null) return deployment;
        return vendor.getDeployments().stream()
                .filter(d -> d.getDeployment().equals(deployment))
                .map(DeploymentConfig::getModel)
                .findFirst()
                .orElse(deployment);
    }

    private Map<String, Object> asMap(Object body) {
        if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) body;
            return m;
        }
        return new LinkedHashMap<>();
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
