package fun.commons.tokenmock.handler.staticfiles;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticResourceControllerTest {

    private StaticResourceController controller;

    @BeforeEach
    void setup() {
        controller = new StaticResourceController(new PlaceholderResources());
    }

    @Test
    void placeholder_png_returns_image_png_bytes() {
        ResponseEntity<byte[]> resp = controller.placeholderPng();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void placeholder_mp4_returns_video_mp4_bytes() {
        ResponseEntity<byte[]> resp = controller.placeholderMp4();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("video/mp4"));
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallback_returns_json_envelope() {
        ResponseEntity<Map<String, Object>> resp = controller.fallback();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("object", "mock_file");
    }
}