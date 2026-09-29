package fun.commons.tokenmock.handler.staticfiles;

import fun.commons.tokenmock.core.PlaceholderResources;
import fun.commons.tokenmock.handler.openai.ImageJobHandler;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves placeholder binaries for slug-independent mock endpoints.
 * <p>
 * token-gateway 0.8.0+ returns 307 from {@code /v1/videos/{id}/content} and
 * image-job responses point at signed proxy URLs; we redirect to
 * {@code /mock-files/videos/{id}.mp4} and {@code /mock-files/images/placeholder.png}
 * from here.
 * <p>
 * {@code /v1/resources/{jobId}/{index}} is the image-job artifact URL (issue #1).
 * The URL is emitted as a relative reference, so consumers either join it onto
 * the vendor base (handled by the OpenAI protocol handler's own
 * {@code /{slug}/v1/resources/...} branch) or resolve it against the host root
 * (RFC 3986) — this top-level alias covers the latter, mirroring how
 * {@code /mock-files} is slug-independent.
 * <p>
 * No vendor/slug prefix — paths are independent so {@code Location:} headers
 * from any vendor can target them without per-vendor prefixing.
 */
@Controller
public class StaticResourceController {

    private final PlaceholderResources placeholders;
    private final ImageJobHandler imageJobs;

    public StaticResourceController(PlaceholderResources placeholders, ImageJobHandler imageJobs) {
        this.placeholders = placeholders;
        this.imageJobs = imageJobs;
    }

    @GetMapping("/mock-files/images/placeholder.png")
    public ResponseEntity<byte[]> placeholderPng() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(placeholders.placeholderPng());
    }

    @GetMapping("/mock-files/videos/{filename}.mp4")
    public ResponseEntity<byte[]> placeholderMp4() {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(placeholders.placeholderMp4());
    }

    /** Top-level alias for the image-job artifact bytes (same logic as the
     *  slug-prefixed route in the OpenAI protocol handler). */
    @GetMapping("/v1/resources/{jobId}/{index}")
    public ResponseEntity<byte[]> imageResource(@PathVariable String jobId,
                                                @PathVariable int index) {
        return imageJobs.resource(jobId, index)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .<byte[]>body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Catch-all for any other resource path. Returns a small JSON envelope so
     * the redirect can be observed without 404'ing tests that hit
     * {@code /mock-files/audio/...} etc.
     */
    @GetMapping("/mock-files/**")
    public ResponseEntity<Map<String, Object>> fallback() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "mock_file");
        body.put("note", "[mock] placeholder binary served as text fallback");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
