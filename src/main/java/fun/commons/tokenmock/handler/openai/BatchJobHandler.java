package fun.commons.tokenmock.handler.openai;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store for OpenAI Batches API.
 * <p>
 * Batch 上传一个 jsonl 文件(每行一个 {@code /v1/chat/completions} 请求),
 * mock 不真解析/转发,只是给个状态机走完:`validating → in_progress → finalizing
 * → completed`,结果输出一个空 jsonl 占位. cancel 把状态切到 {@code cancelling → cancelled}.
 */
@Component
public class BatchJobHandler {

    public enum Status { VALIDATING, IN_PROGRESS, FINALIZING, COMPLETED, CANCELLING, CANCELLED, FAILED }

    public record BatchJob(
            String id,
            String slug,
            String inputFileId,
            String endpoint,
            String completionWindow,
            Status status,
            int totalRequests,
            int completedRequests,
            int failedRequests,
            String outputFileId,
            String errorFileId,
            Instant createdAt,
            Instant completedAt
    ) {}

    private final ConcurrentMap<String, BatchJob> byId = new ConcurrentHashMap<>();

    public String newId() {
        return "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    public BatchJob create(String slug, String inputFileId, String endpoint, String completionWindow) {
        String id = newId();
        BatchJob job = new BatchJob(id, slug, inputFileId,
                endpoint == null ? "/v1/chat/completions" : endpoint,
                completionWindow == null ? "24h" : completionWindow,
                Status.VALIDATING, 0, 0, 0, null, null,
                Instant.now(), null);
        byId.put(id, job);
        // simulate immediate progression to in_progress
        byId.put(id, withStatus(job, Status.IN_PROGRESS));
        return byId.get(id);
    }

    public java.util.Optional<BatchJob> get(String id) {
        return java.util.Optional.ofNullable(byId.get(id));
    }

    public boolean cancel(String id) {
        BatchJob old = byId.get(id);
        if (old == null) return false;
        if (old.status() == Status.COMPLETED || old.status() == Status.CANCELLED) return false;
        byId.put(id, withStatus(old, Status.CANCELLING));
        return true;
    }

    /**
     * Mark a batch as completed with synthesized counts. Real OpenAI counts the
     * successful lines in the input file; we just report 0 to keep tests stable.
     */
    public BatchJob markCompleted(String id, int total) {
        BatchJob old = byId.get(id);
        if (old == null) return null;
        BatchJob finalized = new BatchJob(old.id(), old.slug(), old.inputFileId(),
                old.endpoint(), old.completionWindow(), Status.FINALIZING,
                total, 0, 0, null, null,
                old.createdAt(), null);
        byId.put(id, finalized);
        BatchJob completed = new BatchJob(old.id(), old.slug(), old.inputFileId(),
                old.endpoint(), old.completionWindow(), Status.COMPLETED,
                total, total, 0, "file_batch_result_" + old.id().substring(6), null,
                old.createdAt(), Instant.now());
        byId.put(id, completed);
        return completed;
    }

    private BatchJob withStatus(BatchJob j, Status s) {
        return new BatchJob(j.id(), j.slug(), j.inputFileId(), j.endpoint(),
                j.completionWindow(), s, j.totalRequests(), j.completedRequests(),
                j.failedRequests(), j.outputFileId(), j.errorFileId(),
                j.createdAt(), j.completedAt());
    }

    /**
     * Build OpenAI-shaped response payload for a batch.
     */
    public Map<String, Object> toApiResponse(BatchJob j) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", j.id());
        body.put("object", "batch");
        body.put("endpoint", j.endpoint());
        body.put("input_file_id", j.inputFileId());
        body.put("completion_window", j.completionWindow());
        body.put("status", j.status().name().toLowerCase());
        if (j.outputFileId() != null) body.put("output_file_id", j.outputFileId());
        if (j.errorFileId() != null) body.put("error_file_id", j.errorFileId());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", j.totalRequests());
        counts.put("completed", j.completedRequests());
        counts.put("failed", j.failedRequests());
        body.put("request_counts", counts);
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("object", "list");
        errors.put("data", List.of());
        body.put("errors", errors);
        body.put("created_at", j.createdAt().getEpochSecond());
        if (j.completedAt() != null) body.put("completed_at", j.completedAt().getEpochSecond());
        body.put("metadata", Map.of());
        return body;
    }
}