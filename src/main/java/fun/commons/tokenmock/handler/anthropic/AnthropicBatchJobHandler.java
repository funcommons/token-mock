package fun.commons.tokenmock.handler.anthropic;

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
 * In-memory store for Anthropic Message Batches API.
 * <p>
 * Anthropic's batch shape differs from OpenAI:
 *  - id: {@code msgbatch_xxx}
 *  - processing_status: {@code in_progress / canceling / ended}
 *  - request_counts: {processing / succeeded / errored / canceled / expired}
 *  - results fetched via {@code GET .../results} returning one JSON line per
 *    request: {@code {"custom_id":"...","result":{type:"succeeded",message:{...}}}}
 *    or {@code {"custom_id":"...","result":{type:"errored",error:{...}}}}.
 */
@Component
public class AnthropicBatchJobHandler {

    public enum Status { IN_PROGRESS, CANCELING, ENDED }

    public record BatchJob(
            String id,
            Status status,
            int totalRequests,
            int succeeded,
            int errored,
            int canceled,
            int expired,
            Instant createdAt,
            Instant endedAt,
            List<Map<String, Object>> results
    ) {}

    private static final int DEFAULT_TOTAL = 10;

    private final ConcurrentMap<String, BatchJob> byId = new ConcurrentHashMap<>();

    public String newId() {
        return "msgbatch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    public BatchJob create(int total) {
        String id = newId();
        BatchJob job = new BatchJob(id, Status.IN_PROGRESS, total, 0, 0, 0, 0,
                Instant.now(), null, synthesizeResults(id, total, 0));
        byId.put(id, job);
        return job;
    }

    public Optional<BatchJob> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean cancel(String id) {
        BatchJob old = byId.get(id);
        if (old == null) return false;
        if (old.status() != Status.IN_PROGRESS) return false;
        byId.put(id, new BatchJob(old.id(), Status.CANCELING, old.totalRequests(),
                old.succeeded(), old.errored(), old.canceled(), old.expired(),
                old.createdAt(), null, old.results()));
        return true;
    }

    /** List with simple pagination (limit/before_id/after_id). */
    public List<BatchJob> list(int limit) {
        return byId.values().stream().limit(limit > 0 ? limit : 20).toList();
    }

    /**
     * Build the per-request results payload — one JSON line per request, mirroring
     * Anthropic's actual stream body shape.
     */
    private List<Map<String, Object>> synthesizeResults(String batchId, int total, int succeeded) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("custom_id", "request-" + (i + 1));
            if (i < succeeded) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "succeeded");
                result.put("message", Map.of(
                        "id", "msg_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                        "type", "message",
                        "role", "assistant",
                        "model", "claude-3-5-sonnet-20241022",
                        "content", List.of(Map.of(
                                "type", "text",
                                "text", "[mock batch " + batchId + " #" + (i + 1) + "]")),
                        "stop_reason", "end_turn"
                ));
                line.put("result", result);
            } else {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "errored");
                result.put("error", Map.of("type", "invalid_request_error",
                        "message", "[mock] batch " + batchId + " item " + (i + 1) + " simulated failure"));
                line.put("result", result);
            }
            out.add(line);
        }
        return out;
    }

    /**
     * OpenAI-shaped API response (object: "message_batch").
     */
    public Map<String, Object> toApiResponse(BatchJob j) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", j.id());
        body.put("object", "message_batch");
        body.put("processing_status", j.status().name().toLowerCase());
        body.put("request_counts", Map.of(
                "processing", j.status() == Status.ENDED ? 0
                        : Math.max(0, j.totalRequests() - j.succeeded() - j.errored() - j.canceled()),
                "succeeded", j.succeeded(),
                "errored", j.errored(),
                "canceled", j.canceled(),
                "expired", j.expired()
        ));
        body.put("created_at", j.createdAt().toString());
        body.put("ended_at", j.endedAt() == null ? null : j.endedAt().toString());
        body.put("results_url",
                j.status() == Status.ENDED ? "/v1/messages/batches/" + j.id() + "/results" : null);
        return body;
    }

    public static int defaultTotal() {
        return DEFAULT_TOTAL;
    }
}