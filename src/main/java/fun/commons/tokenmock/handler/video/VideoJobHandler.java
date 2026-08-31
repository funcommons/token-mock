package fun.commons.tokenmock.handler.video;

import fun.commons.tokenmock.core.PlaceholderResources;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async video generation: submit job → poll status → download binary.
 * <p>
 * State machine: queued (0-30s) → in_progress (30-60s) → completed
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

    public Object submit(String vendorSlug, String model, String prompt) {
        String id = "video_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        VideoJob job = new VideoJob(
                id, vendorSlug, model, prompt,
                "queued", Instant.now().getEpochSecond(), 0,
                "http://localhost:9999/" + vendorSlug + "/v1/videos/" + id
        );
        jobs.put(id, job);
        return ResponseEntity.ok(job.toMap());
    }

    public Object getStatus(String jobId) {
        VideoJob job = jobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", Map.of("message", "video job not found: " + jobId, "type", "not_found")
            ));
        }
        advanceState(job);
        return ResponseEntity.ok(job.toMap());
    }

    public Object downloadContent(String jobId) {
        VideoJob job = jobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", Map.of("message", "video job not found", "type", "not_found")
            ));
        }
        if (!"completed".equals(job.status)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", Map.of("message", "job not completed yet, current status: " + job.status,
                            "type", "not_ready")
            ));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(placeholders.placeholderMp4());
    }

    private void advanceState(VideoJob job) {
        long now = Instant.now().getEpochSecond();
        long elapsed = now - job.createdAt;
        if ("queued".equals(job.status) && elapsed >= QUEUED_DURATION_SECONDS) {
            job.status = "in_progress";
        }
        if ("in_progress".equals(job.status) && elapsed >= QUEUED_DURATION_SECONDS + IN_PROGRESS_DURATION_SECONDS) {
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

    public static class VideoJob {
        final String id;
        final String vendorSlug;
        final String model;
        final String prompt;
        volatile String status;
        final long createdAt;
        volatile long completedAt;
        final String url;

        VideoJob(String id, String vendorSlug, String model, String prompt,
                 String status, long createdAt, long completedAt, String url) {
            this.id = id;
            this.vendorSlug = vendorSlug;
            this.model = model;
            this.prompt = prompt;
            this.status = status;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
            this.url = url;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("status", status);
            m.put("model", model);
            m.put("prompt", prompt);
            m.put("created_at", createdAt);
            if (completedAt > 0) m.put("completed_at", completedAt);
            if ("completed".equals(status)) {
                m.put("url", url + "/content");
                m.put("duration_seconds", 5.0);
                m.put("resolution", "1080p");
            }
            return m;
        }
    }
}
