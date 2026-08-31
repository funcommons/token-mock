package fun.commons.tokenmock.handler.openai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatchJobHandlerTest {

    private BatchJobHandler handler;

    @BeforeEach
    void setup() { handler = new BatchJobHandler(); }

    @Test
    void create_starts_in_progress() {
        var job = handler.create("openai", "file-abc", "/v1/chat/completions", "24h");
        assertThat(job.id()).startsWith("batch_");
        assertThat(job.status()).isEqualTo(BatchJobHandler.Status.IN_PROGRESS);
        assertThat(job.inputFileId()).isEqualTo("file-abc");
    }

    @Test
    void markCompleted_sets_output_file_id() {
        var job = handler.create("openai", "file-abc", "/v1/chat/completions", "24h");
        var done = handler.markCompleted(job.id(), 100);
        assertThat(done.status()).isEqualTo(BatchJobHandler.Status.COMPLETED);
        assertThat(done.outputFileId()).isNotNull();
        assertThat(done.totalRequests()).isEqualTo(100);
        assertThat(done.completedRequests()).isEqualTo(100);
    }

    @Test
    void cancel_only_active_batches() {
        var job = handler.create("openai", "file-abc", "/v1/chat/completions", "24h");
        assertThat(handler.cancel(job.id())).isTrue();
        assertThat(handler.get(job.id()).orElseThrow().status())
                .isEqualTo(BatchJobHandler.Status.CANCELLING);
        // Re-cancel on cancelling state is allowed (still not in terminal state)
        assertThat(handler.cancel(job.id())).isTrue();
    }

    @Test
    void cancel_completed_batch_fails() {
        var job = handler.create("openai", "file-abc", "/v1/chat/completions", "24h");
        handler.markCompleted(job.id(), 50);
        assertThat(handler.cancel(job.id())).isFalse();
    }

    @Test
    void toApiResponse_has_batch_shape() {
        var job = handler.create("openai", "file-abc", "/v1/chat/completions", "24h");
        var api = handler.toApiResponse(job);
        assertThat(api).containsKeys("id", "object", "endpoint", "input_file_id",
                "completion_window", "status", "request_counts");
        assertThat(api.get("object")).isEqualTo("batch");
        assertThat(api.get("status")).isEqualTo("in_progress");
    }
}