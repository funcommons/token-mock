package fun.commons.tokenmock.handler.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicBatchJobHandlerTest {

    private AnthropicBatchJobHandler handler;

    @BeforeEach
    void setup() { handler = new AnthropicBatchJobHandler(); }

    @Test
    void create_returns_in_progress_with_correct_total() {
        var job = handler.create(5);
        assertThat(job.id()).startsWith("msgbatch_");
        assertThat(job.status()).isEqualTo(AnthropicBatchJobHandler.Status.IN_PROGRESS);
        assertThat(job.totalRequests()).isEqualTo(5);
        assertThat(job.succeeded()).isEqualTo(0);
    }

    @Test
    void cancel_only_in_progress_batches() {
        var job = handler.create(3);
        assertThat(handler.cancel(job.id())).isTrue();
        assertThat(handler.get(job.id()).orElseThrow().status())
                .isEqualTo(AnthropicBatchJobHandler.Status.CANCELING);
        // re-cancel: already canceling, returns false
        assertThat(handler.cancel(job.id())).isFalse();
    }

    @Test
    void list_returns_all_batches_capped_by_limit() {
        handler.create(1);
        handler.create(2);
        handler.create(3);
        assertThat(handler.list(2)).hasSize(2);
        assertThat(handler.list(0)).hasSize(3); // 0 -> default 20
    }

    @Test
    void toApiResponse_has_message_batch_shape() {
        var job = handler.create(2);
        Map<String, Object> api = handler.toApiResponse(job);
        assertThat(api.get("object")).isEqualTo("message_batch");
        assertThat(api.get("processing_status")).isEqualTo("in_progress");
        Map<String, Object> counts = (Map<String, Object>) api.get("request_counts");
        assertThat(counts).containsKeys("processing", "succeeded", "errored", "canceled", "expired");
        assertThat(api.get("results_url")).isNull();
    }

    @Test
    void results_have_one_line_per_request() {
        var job = handler.create(4);
        assertThat(job.results()).hasSize(4);
        assertThat(job.results().get(0)).containsKey("custom_id");
        Map<String, Object> r0 = (Map<String, Object>) job.results().get(0).get("result");
        assertThat(r0).isNotNull();
        assertThat(r0).containsKey("type");
    }
}