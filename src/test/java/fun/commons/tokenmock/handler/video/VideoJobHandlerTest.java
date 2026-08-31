package fun.commons.tokenmock.handler.video;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VideoJobHandlerTest {

    private VideoJobHandler handler;

    @BeforeEach
    void setup() {
        handler = new VideoJobHandler(new PlaceholderResources());
    }

    @Test
    void submit_returns_queued_job() {
        ResponseEntity<?> resp = (ResponseEntity<?>) handler.submit("openai", "sora-2", "a cat playing piano");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat((String) body.get("id")).startsWith("video_mock_");
        assertThat(body.get("status")).isEqualTo("queued");
        assertThat(body.get("model")).isEqualTo("sora-2");
    }

    @Test
    void status_returns_404_for_unknown() {
        ResponseEntity<?> resp = (ResponseEntity<?>) handler.getStatus("video_mock_ghost");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void status_returns_queued_immediately_after_submit() {
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", "sora-2", "x");
        @SuppressWarnings("unchecked")
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();

        ResponseEntity<?> status = (ResponseEntity<?>) handler.getStatus(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) status.getBody();
        assertThat(body.get("status")).isEqualTo("queued");
    }

    @Test
    void force_status_completes_immediately() {
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", "sora-2", "x");
        @SuppressWarnings("unchecked")
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();

        assertThat(handler.forceStatus(id, "completed")).isTrue();

        ResponseEntity<?> status = (ResponseEntity<?>) handler.getStatus(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) status.getBody();
        assertThat(body.get("status")).isEqualTo("completed");
        assertThat(body).containsKey("url");
        assertThat(body).containsKey("completed_at");
    }

    @Test
    void download_returns_409_when_not_completed() {
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", "sora-2", "x");
        @SuppressWarnings("unchecked")
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();

        ResponseEntity<?> dl = (ResponseEntity<?>) handler.downloadContent(id);
        assertThat(dl.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void download_returns_mp4_bytes_when_completed() {
        ResponseEntity<?> submit = (ResponseEntity<?>) handler.submit("openai", "sora-2", "x");
        @SuppressWarnings("unchecked")
        String id = ((Map<String, Object>) submit.getBody()).get("id").toString();
        handler.forceStatus(id, "completed");

        ResponseEntity<?> dl = (ResponseEntity<?>) handler.downloadContent(id);
        assertThat(dl.getStatusCode().value()).isEqualTo(200);
        assertThat(dl.getHeaders().getContentType().toString()).startsWith("video/mp4");
        assertThat((byte[]) dl.getBody()).isNotEmpty();
    }

    @Test
    void force_status_returns_false_for_unknown() {
        assertThat(handler.forceStatus("ghost", "completed")).isFalse();
    }

    @Test
    void list_job_ids_returns_submitted_jobs() {
        handler.submit("openai", "sora-2", "a");
        handler.submit("openai", "sora-2", "b");
        assertThat(handler.listJobIds()).hasSize(2);
    }
}
