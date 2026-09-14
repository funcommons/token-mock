package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Async image generation in OpenAI background shape.
 * <p>
 * Matches token-gateway 0.8.0+ {@code /v1/images/generations} background mode:
 *  - submit with {@code background:true} → image_generation job (T-prefixed id)
 *  - poll {@code GET /v1/images/generations/{id}} returns OpenAI shape
 *    {@code output[].content[].image_url} when completed
 *  - status: queued → in_progress → completed / failed
 */
@Component
public class ImageJobHandler {

    public enum Status { QUEUED, IN_PROGRESS, COMPLETED, FAILED }

    public record ImageJob(
            String id,
            String vendorSlug,
            String model,
            String prompt,
            String size,
            String resolution,
            Integer n,
            Status status,
            Instant createdAt,
            Instant completedAt,
            String errorMessage
    ) {}

    private final ConcurrentMap<String, ImageJob> byId = new ConcurrentHashMap<>();
    private final PlaceholderResources placeholders;

    public ImageJobHandler(PlaceholderResources placeholders) {
        this.placeholders = placeholders;
    }

    public ImageJob submit(String vendorSlug, Map<String, Object> body) {
        String model = stringOr(body.get("model"), "gpt-image-2");
        String prompt = stringOr(body.get("prompt"), "(empty)");
        String size = stringOr(body.get("size"), "1024x1024");
        String resolution = stringOr(body.get("resolution"), null);
        Integer n = body.get("n") instanceof Number num ? num.intValue() : null;
        String id = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
        ImageJob job = new ImageJob(id, vendorSlug, model, prompt, size, resolution, n,
                Status.QUEUED, Instant.now(), null, null);
        byId.put(id, job);
        // Promote to in_progress immediately (mock is fast)
        advanceToInProgress(job);
        // Then to completed on next poll (with a tiny delay so tests can observe state)
        scheduleComplete(id);
        return job;
    }

    public Optional<ImageJob> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    private void advanceToInProgress(ImageJob job) {
        ImageJob next = new ImageJob(job.id(), job.vendorSlug(), job.model(), job.prompt(),
                job.size(), job.resolution(), job.n(),
                Status.IN_PROGRESS, job.createdAt(), null, null);
        byId.put(job.id(), next);
    }

    private void scheduleComplete(String id) {
        // Mock-side: complete after a short delay so polling actually moves states.
        // Use a tiny daemon thread so tests can poll mid-flight if they want.
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ImageJob current = byId.get(id);
            if (current == null) return;
            ImageJob done = new ImageJob(current.id(), current.vendorSlug(), current.model(),
                    current.prompt(), current.size(), current.resolution(), current.n(),
                    Status.COMPLETED, current.createdAt(), Instant.now(), null);
            byId.put(id, done);
        }, "image-job-complete-" + id);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Build OpenAI-shaped API response for the image job.
     */
    public Map<String, Object> toApiResponse(ImageJob job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.id());
        body.put("object", "image_generation");
        body.put("status", job.status().name().toLowerCase());
        body.put("created_at", job.createdAt().getEpochSecond());
        if (job.completedAt() != null) {
            body.put("completed_at", job.completedAt().getEpochSecond());
        }
        if (job.status() == Status.COMPLETED) {
            int n = job.n() == null ? 1 : Math.min(10, Math.max(1, job.n()));
            List<Map<String, Object>> output = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("type", "output_image");
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", "/v1/resources/" + job.id() + "/" + i);
                content.put("image_url", imageUrl);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("content", List.of(content));
                output.add(item);
            }
            body.put("output", output);
        }
        if (job.status() == Status.FAILED && job.errorMessage() != null) {
            body.put("error", Map.of(
                    "code", "upstream_error",
                    "message", job.errorMessage()
            ));
        }
        return body;
    }

    /**
     * Sync wrapper around the async shape, matching token-gateway 0.8.0+ contract
     * for {@code POST /v1/images/sync}: synchronous create+poll up to {@code timeoutMs},
     * then either completed result or PROCESSING fallback.
     */
    public Map<String, Object> syncOrTimeout(String vendorSlug, Map<String, Object> body,
                                              long timeoutMs) {
        ImageJob job = submit(vendorSlug, body);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (job.status() == Status.COMPLETED || job.status() == Status.FAILED) break;
            try { Thread.sleep(20); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            job = byId.get(job.id());
            if (job == null) break;
        }
        Map<String, Object> body2 = new LinkedHashMap<>();
        if (job.status() == Status.COMPLETED) {
            body2.put("created", job.createdAt().getEpochSecond());
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", "/v1/resources/" + job.id() + "/0");
            data.add(item);
            body2.put("data", data);
            return body2;
        }
        if (job.status() == Status.FAILED) {
            // gateway emits 502 + error envelope when upstream fails; for a sync
            // wrapper we return null and let the caller map to 502.
            return null;
        }
        // Timeout fallback — still in_progress
        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("status", "PROCESSING");
        processing.put("task_no", job.id());
        processing.put("poll_url", "/v1/images/generations/" + job.id());
        return processing;
    }

    public List<String> listJobIds() {
        return List.copyOf(byId.keySet());
    }

    private String stringOr(Object v, String fb) {
        return v == null || String.valueOf(v).isBlank() ? fb : String.valueOf(v);
    }
}