package fun.commons.tokenmock.handler.staticfiles;

import fun.commons.tokenmock.core.PlaceholderResources;
import fun.commons.tokenmock.handler.openai.ImageJobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticResourceControllerTest {

    private StaticResourceController controller;
    private ImageJobHandler imageJobs;

    @BeforeEach
    void setup() {
        imageJobs = new ImageJobHandler(new PlaceholderResources());
        controller = new StaticResourceController(new PlaceholderResources(), imageJobs);
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

    @Test
    void imageResource_unknown_job_returns_404() {
        ResponseEntity<byte[]> resp = controller.imageResource("TUNKNOWN0000000000000000", 0);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void imageResource_serves_png_for_completed_job() throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-image-2");
        body.put("prompt", "a cat");
        var job = imageJobs.submit("openai", body);
        Thread.sleep(400);

        ResponseEntity<byte[]> resp = controller.imageResource(job.id(), 0);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(resp.getBody()).isNotEmpty();
    }
}