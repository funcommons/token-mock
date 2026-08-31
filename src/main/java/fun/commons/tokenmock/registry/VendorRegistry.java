package fun.commons.tokenmock.registry;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.VendorConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VendorRegistry {

    private final MockProperties properties;
    private final Map<String, VendorConfig> bySlug = new LinkedHashMap<>();

    public VendorRegistry(MockProperties properties) {
        this.properties = properties;
    }

    public void init() {
        bySlug.clear();
        for (VendorConfig v : properties.getVendors()) {
            if (bySlug.put(v.getSlug(), v) != null) {
                throw new IllegalStateException("duplicate vendor slug: " + v.getSlug());
            }
        }
    }

    public VendorConfig findBySlug(String slug) {
        if (slug == null) return null;
        return bySlug.get(slug);
    }

    public VendorConfig require(String slug) {
        VendorConfig v = findBySlug(slug);
        if (v == null) {
            throw new IllegalArgumentException("vendor not found: " + slug);
        }
        return v;
    }

    public List<VendorConfig> all() {
        return List.copyOf(bySlug.values());
    }

    public List<VendorConfig> findByProtocol(String protocol) {
        return bySlug.values().stream()
                .filter(v -> protocol.equals(v.getProtocol()))
                .toList();
    }

    /** Validate Bearer token from Authorization header. */
    public boolean authenticate(VendorConfig vendor, String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        return vendor.getKey().equals(token);
    }
}
