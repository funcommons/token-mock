package fun.commons.tokenmock.handler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockRequest {

    private String vendorSlug;
    private String authHeader;
    private String path;
    private Object body;

    public static MockRequest of(String slug, String auth, String path, Object body) {
        return new MockRequest(slug, auth, path, body);
    }
}
