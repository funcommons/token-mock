package fun.commons.tokenmock.handler.video;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async video generation in OpenAI sora job shape.
 * <p>
 * Matches token-gateway 0.8.0+ {@code /v1/videos} contract:
 *  - submit body accepts {@code model} / {@code prompt} / {@code seconds} / {@code size} / {@code notify_url}
 *  - response object: {@code video_generation}
 *  - id format: T-prefixed (OneToken task_no) — token-gateway's actual id shape
 *  - status: queued → in_progress → completed / failed
 *  - {@code /v1/videos/{id}/content} returns 307 to signed proxy URL
 *    (the mock serves it from {@code /mock-files/videos/{id}.mp4} directly)
 */
@Component
public class VideoJobHandler {

    private static final long QUEUED_DURATION_SECONDS = 30;
    private static final long IN_PROGRESS_DURATION_SECONDS = 30;

    private final Map<String, VideoJob> jobs = new ConcurrentHashMap<>();
    private final PlaceholderResources placeholders;

    public VideoJobHandler(PlaceholderResources placeholders) {
        this.placeholders = placeholders;
    }

    public Object submit(String vendorSlug, Map<String, Object> body) {
        String model = stringOr(body.get("model"), "sora-2");
        String prompt = stringOr(body.get("prompt"), "(empty)");
        String seconds = body.get("seconds") == null ? null : String.valueOf(body.get("seconds"));
        String size = stringOr(body.get("size"), "1280x720");
        String notifyUrl = stringOr(body.get("notify_url"), null);
        String id = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
        VideoJob job = new VideoJob(
                id, vendorSlug, model, prompt, seconds, size, notifyUrl,
                "queued", Instant.now().getEpochSecond(), 0
        );
        jobs.put(id, job);
        return ResponseEntity.ok(job.toMap());
    }

    public Object getStatus(String jobId) {
        VideoJob job = jobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", Map.of("message", "video job not found: " + jobId,
                            "type", "not_found", "code", "video_not_found")
            ));
        }
        advanceState(job);
        return ResponseEntity.ok(job.toMap());
    }

    /**
     * token-gateway 0.8.0+ contract: 307 redirect to signed proxy URL.
     * <p>The mock returns 307 to {@code /mock-files/videos/{id}.mp4} (served by
     * {@link fun.commons.tokenmock.handler.staticfiles.StaticResourceController}).
     * Real OpenAI SDK + curl {@code -L} follow the redirect.
     */
    public ResponseEntity<Void> downloadContent(String jobId) {
        VideoJob job = jobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(null);
        }
        if (!"completed".equals(job.status) && !"in_progress".equals(job.status)) {
            // failed -> 410 Gone (resource won't be available)
            if ("failed".equals(job.status)) {
                return ResponseEntity.status(HttpStatus.GONE).build();
            }
        }
        // For mock, redirect directly to our own static endpoint.
        // Real gateway would 307 to a pre-signed OSS / S3 URL.
        URI redirectUri = URI.create("/mock-files/videos/" + jobId + ".mp4");
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(redirectUri)
                .header("X-Mock-Redirect", "true")
                .build();
    }

    private void advanceState(VideoJob job) {
        long now = Instant.now().getEpochSecond();
        long elapsed = now - job.createdAt;
        if ("queued".equals(job.status) && elapsed >= QUEUED_DURATION_SECONDS) {
            job.status = "in_progress";
        }
        if ("in_progress".equals(job.status)
                && elapsed >= QUEUED_DURATION_SECONDS + IN_PROGRESS_DURATION_SECONDS) {
            job.status = "completed";
            job.completedAt = now;
        }
    }

    /** Force a job into a specific status (used by admin endpoints). */
    public boolean forceStatus(String jobId, String newStatus) {
        VideoJob job = jobs.get(jobId);
        if (job == null) return false;
        job.status = newStatus;
        if ("completed".equals(newStatus)) {
            job.completedAt = Instant.now().getEpochSecond();
        }
        return true;
    }

    public List<String> listJobIds() {
        return List.copyOf(jobs.keySet());
    }

    private String stringOr(Object v, String fb) {
        return v == null || String.valueOf(v).isBlank() ? fb : String.valueOf(v);
    }

    public static class VideoJob {
        final String id;
        final String vendorSlug;
        final String model;
        final String prompt;
        final String seconds;
        final String size;
        final String notifyUrl;
        volatile String status;
        final long createdAt;
        volatile long completedAt;

        VideoJob(String id, String vendorSlug, String model, String prompt,
                 String seconds, String size, String notifyUrl,
                 String status, long createdAt, long completedAt) {
            this.id = id;
            this.vendorSlug = vendorSlug;
            this.model = model;
            this.prompt = prompt;
            this.seconds = seconds;
            this.size = size;
            this.notifyUrl = notifyUrl;
            this.status = status;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("object", "video_generation");
            m.put("status", status);
            m.put("created_at", createdAt);
            if (completedAt > 0) m.put("completed_at", completedAt);
            m.put("model", model);
            m.put("prompt", prompt);
            if (seconds != null) m.put("seconds", seconds);
            m.put("size", size);
            if (notifyUrl != null) m.put("notify_url", notifyUrl);
            if ("failed".equals(status)) {
                m.put("error", Map.of("code", "upstream_error", "message", "[mock] simulated failure"));
            }
            return m;
        }
    }
}