package fun.commons.tokenmock.handler.staticfiles;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves placeholder binaries for the mock-files static endpoint.
 * <p>
 * URL: {@code GET /mock-files/{name}}. token-gateway 0.8.0+ returns 307 from
 * {@code /v1/videos/{id}/content} and {@code /v1/images/generations/{id}}
 * responses to signed proxy URLs; we redirect to {@code /mock-files/videos/{id}.mp4}
 * and {@code /mock-files/images/placeholder.png} directly from this controller.
 * <p>
 * No vendor/slug prefix — the path is independent so {@code Location:} headers
 * from any vendor can target it without per-vendor prefixing.
 */
@Controller
@RequestMapping("/mock-files")
public class StaticResourceController {

    private final PlaceholderResources placeholders;

    public StaticResourceController(PlaceholderResources placeholders) {
        this.placeholders = placeholders;
    }

    @GetMapping("/images/placeholder.png")
    public ResponseEntity<byte[]> placeholderPng() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(placeholders.placeholderPng());
    }

    @GetMapping("/videos/{filename}.mp4")
    public ResponseEntity<byte[]> placeholderMp4() {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(placeholders.placeholderMp4());
    }

    /**
     * Catch-all for any other resource path. Returns a small JSON envelope so
     * the redirect can be observed without 404'ing tests that hit
     * {@code /mock-files/audio/...} etc.
     */
    @GetMapping("/**")
    public ResponseEntity<Map<String, Object>> fallback() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "mock_file");
        body.put("note", "[mock] placeholder binary served as text fallback");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}