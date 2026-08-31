package fun.commons.tokenmock.handler.openai;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store + scheduler for OpenAI Responses API.
 * <p>
 * Two responsibilities:
 * <ol>
 *   <li>Chain responses via {@code previous_response_id} — a follow-up
 *       request references the prior response id and we splice the assistant
 *       text into the new input as a previous assistant message.
 *   <li>Background jobs ({@code background:true}) — return immediately with
 *       a queued response, then mark it completed a moment later so the
 *       client can poll {@code GET /v1/responses/{id}}.
 * </ol>
 */
@Component
public class ResponseJobHandler {

    public enum Status { QUEUED, IN_PROGRESS, COMPLETED, CANCELLED, FAILED }

    public record ResponseJob(
            String id,
            String slug,
            String model,
            Status status,
            String userText,
            String assistantText,
            Instant createdAt,
            Instant completedAt,
            String previousResponseId
    ) {}

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "response-job-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentMap<String, ResponseJob> byId = new ConcurrentHashMap<>();

    public String newId() {
        return "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    public ResponseJob create(String slug, String model, String userText,
                               String previousResponseId, boolean background) {
        String id = newId();
        ResponseJob job = new ResponseJob(id, slug, model,
                background ? Status.QUEUED : Status.IN_PROGRESS,
                userText, "[mock] " + userText,
                Instant.now(), null, previousResponseId);
        byId.put(id, job);
        if (background) {
            // simulate a short generation — don't cancel the future
            SCHEDULER.schedule(() -> complete(id), 250, TimeUnit.MILLISECONDS);
        } else {
            complete(id);
        }
        return byId.get(id);
    }

    public Optional<ResponseJob> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean cancel(String id) {
        ResponseJob old = byId.get(id);
        if (old == null) return false;
        if (old.status() == Status.COMPLETED || old.status() == Status.CANCELLED) return false;
        byId.put(id, new ResponseJob(old.id(), old.slug(), old.model(),
                Status.CANCELLED, old.userText(), old.assistantText(),
                old.createdAt(), Instant.now(), old.previousResponseId()));
        return true;
    }

    private void complete(String id) {
        ResponseJob old = byId.get(id);
        if (old == null) return;
        if (old.status() == Status.CANCELLED) return;
        byId.put(id, new ResponseJob(old.id(), old.slug(), old.model(),
                Status.COMPLETED, old.userText(), old.assistantText(),
                old.createdAt(), Instant.now(), old.previousResponseId()));
    }

    /**
     * Build the OpenAI-shaped response payload for a response job.
     */
    public Map<String, Object> toApiResponse(ResponseJob job, boolean includeBackground) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.id());
        body.put("object", "response");
        body.put("created_at", job.createdAt().getEpochSecond());
        body.put("model", job.model);
        body.put("status", job.status().name().toLowerCase());
        body.put("previous_response_id", job.previousResponseId());
        if (includeBackground) body.put("background", true);
        if (job.completedAt() != null) body.put("completed_at", job.completedAt().getEpochSecond());

        Map<String, Object> outputItem = new LinkedHashMap<>();
        outputItem.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        outputItem.put("type", "message");
        outputItem.put("role", "assistant");
        outputItem.put("status", "completed");
        outputItem.put("content", List.of(Map.of(
                "type", "output_text",
                "text", job.assistantText(),
                "annotations", List.of()
        )));
        body.put("output", List.of(outputItem));

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", Math.max(1, job.userText().length() / 4));
        usage.put("output_tokens", Math.max(1, job.assistantText().length() / 4));
        usage.put("total_tokens",
                (int) usage.get("input_tokens") + (int) usage.get("output_tokens"));
        body.put("usage", usage);
        return body;
    }
}