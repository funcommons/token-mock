package fun.commons.tokenmock.handler.bedrock;

import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS Bedrock (simplified — skips SigV4 signature verification).
 * <p>
 * Endpoint: POST /model/{vendor}/{model}/invoke
 * Auth: Authorization header containing "AWS4-HMAC-SHA256" prefix OR matches vendor.key.
 */
@Component
public class BedrockProtocolHandler implements ProtocolHandler {

    private final VendorRegistry registry;
    private final TokenEstimator estimator;

    public BedrockProtocolHandler(VendorRegistry registry, TokenEstimator estimator) {
        this.registry = registry;
        this.estimator = estimator;
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
        if (!path.startsWith("/model/") || !path.endsWith("/invoke")) {
            throw new IllegalArgumentException("unknown bedrock path: " + path);
        }
        return handleInvoke(request);
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
}
