package fun.commons.tokenmock.handler;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
public class MockRequest {

    private String vendorSlug;
    private String authHeader;
    private String path;
    private String method;
    private Object body;

    public static MockRequest of(String slug, String auth, String path, Object body) {
        return MockRequest.builder().vendorSlug(slug).authHeader(auth).path(path).body(body).build();
    }

    /** Convenience for tests: default to GET (used by /v1/models etc.). */
    public MockRequest(String slug, String auth, String path, Object body) {
        this(slug, auth, path, "GET", body);
    }

    public MockRequest(String slug, String auth, String path, String method, Object body) {
        this.vendorSlug = slug;
        this.authHeader = auth;
        this.path = path;
        this.method = method;
        this.body = body;
    }
}
