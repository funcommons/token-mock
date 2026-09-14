package fun.commons.tokenmock.handler.video;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VideoJobHandlerTest {

    private VideoJobHandler handler;

    @BeforeEach
    void setup() {
        handler = new VideoJobHandler(new PlaceholderResources());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submit_returns_queued_job_with_video_generation_object() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "sora-2");
        body.put("prompt", "a cat playing piano");
        body.put("seconds", "8");
        body.put("size", "1280x720");
        ResponseEntity<?> resp = (ResponseEntity<?>) handler.submit("openai", body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        assertThat((String) b.get("id")).startsWith("T");
        assertThat(b.get("object")).isEqualTo("video_generation");
        assertThat(b.get("status")).isEqualTo("queued");
        assertThat(b.get("model")).isEqualTo("sora-2");
        assertThat(b.get("seconds")).isEqualTo("8");
    }

@Test
    void status_returns_404_for_unknown() {
        Object resp = handler.getStatus("Tghost");
        assertThat(resp).isInstanceOf(ResponseEntity.class);
        assertThat(((ResponseEntity<?>) resp).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void status_returns_queued_immediately_after_submit() {
        Map<String, Object> body = Map.of("model", "sora-2", "prompt", "x");
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", body);
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();
        ResponseEntity<?> status = (ResponseEntity<?>) handler.getStatus(id);
        Map<String, Object> b = (Map<String, Object>) status.getBody();
        assertThat(b.get("status")).isEqualTo("queued");
    }

    @Test
    @SuppressWarnings("unchecked")
    void force_status_completes_immediately() {
        Map<String, Object> body = Map.of("model", "sora-2", "prompt", "x");
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", body);
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();

        assertThat(handler.forceStatus(id, "completed")).isTrue();

        ResponseEntity<?> status = (ResponseEntity<?>) handler.getStatus(id);
        Map<String, Object> b = (Map<String, Object>) status.getBody();
        assertThat(b.get("status")).isEqualTo("completed");
        assertThat(b).containsKey("completed_at");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failed_status_includes_error_envelope() {
        Map<String, Object> body = Map.of("model", "sora-2", "prompt", "x");
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", body);
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();
        handler.forceStatus(id, "failed");

        ResponseEntity<?> status = (ResponseEntity<?>) handler.getStatus(id);
        Map<String, Object> b = (Map<String, Object>) status.getBody();
        assertThat(b.get("status")).isEqualTo("failed");
        Map<String, Object> err = (Map<String, Object>) b.get("error");
        assertThat(err).containsKeys("code", "message");
    }

    @Test
    void content_307_redirects_to_static_resource() {
        Map<String, Object> body = Map.of("model", "sora-2", "prompt", "x");
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", body);
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();
        handler.forceStatus(id, "completed");

        ResponseEntity<Void> redirect = handler.downloadContent(id);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(redirect.getHeaders().getLocation().toString())
                .isEqualTo("/mock-files/videos/" + id + ".mp4");
        assertThat(redirect.getHeaders().getFirst("X-Mock-Redirect")).isEqualTo("true");
    }

    @Test
    void content_410_for_failed_job() {
        Map<String, Object> body = Map.of("model", "sora-2", "prompt", "x");
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", body);
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();
        handler.forceStatus(id, "failed");

        ResponseEntity<Void> redirect = handler.downloadContent(id);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void content_404_for_unknown() {
        ResponseEntity<Void> redirect = handler.downloadContent("Tghost");
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}