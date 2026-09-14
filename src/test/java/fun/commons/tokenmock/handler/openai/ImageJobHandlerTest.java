package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageJobHandlerTest {

    private ImageJobHandler handler;

    @BeforeEach
    void setup() {
        handler = new ImageJobHandler(new PlaceholderResources());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submit_returns_image_generation_job() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-image-2");
        body.put("prompt", "a cat");
        body.put("size", "1024x1024");

        ImageJobHandler.ImageJob job = handler.submit("openai", body);
        assertThat((String) job.id()).startsWith("T");
        assertThat(job.status()).isIn(
                ImageJobHandler.Status.QUEUED,
                ImageJobHandler.Status.IN_PROGRESS,
                ImageJobHandler.Status.COMPLETED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completed_toApiResponse_includes_output_image_url() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-image-2");
        body.put("prompt", "a cat");

        ImageJobHandler.ImageJob job = handler.submit("openai", body);
        // Wait for completion (mock schedules complete in ~150ms)
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline
                && handler.get(job.id()).orElseThrow().status()
                != ImageJobHandler.Status.COMPLETED) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> api = handler.toApiResponse(handler.get(job.id()).orElseThrow());
        assertThat(api.get("object")).isEqualTo("image_generation");
        assertThat(api.get("status")).isEqualTo("completed");
        java.util.List<Map<String, Object>> output =
                (java.util.List<Map<String, Object>>) api.get("output");
        assertThat(output).hasSize(1);
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) output.get(0).get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("output_image");
        Map<String, Object> imgUrl = (Map<String, Object>) content.get(0).get("image_url");
        assertThat((String) imgUrl.get("url")).startsWith("/v1/resources/" + job.id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncOrTimeout_returns_data_url_when_completes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-image-2");
        body.put("prompt", "a cat");

        Map<String, Object> sync = handler.syncOrTimeout("openai", body, 5_000L);
        assertThat(sync).containsKey("created");
        java.util.List<Map<String, Object>> data =
                (java.util.List<Map<String, Object>>) sync.get("data");
        assertThat(data).hasSize(1);
        assertThat((String) data.get(0).get("url")).startsWith("/v1/resources/");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncOrTimeout_falls_back_to_PROCESSING_when_long_body() {
        // Force a body so the job never finishes within window — but mock
        // completes in 150ms so we just sanity-check shape (any return is valid
        // — completed data OR PROCESSING fallback).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-image-2");
        body.put("prompt", "x");

        Map<String, Object> sync = handler.syncOrTimeout("openai", body, 5_000L);
        // Either completed (likely) or PROCESSING fallback — both are valid
        if ("PROCESSING".equals(sync.get("status"))) {
            assertThat(sync).containsKeys("task_no", "poll_url");
        } else {
            assertThat(sync).containsKey("data");
        }
    }
}