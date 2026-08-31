package fun.commons.tokenmock.handler.openai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ResponseJobHandlerTest {

    private ResponseJobHandler handler;

    @BeforeEach
    void setup() { handler = new ResponseJobHandler(); }

    @Test
    void sync_create_completes_immediately() {
        var job = handler.create("openai", "gpt-4o", "hi", null, false);
        assertThat(job.status()).isEqualTo(ResponseJobHandler.Status.COMPLETED);
        assertThat(job.assistantText()).isEqualTo("[mock] hi");
    }

    @Test
    void background_starts_queued_then_completes() {
        var job = handler.create("openai", "gpt-4o", "hi", null, true);
        assertThat(job.status()).isEqualTo(ResponseJobHandler.Status.QUEUED);
        // poll up to 2s
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (handler.get(job.id()).orElseThrow().status()
                    == ResponseJobHandler.Status.COMPLETED) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { fail(e); }
        }
        fail("background job never completed");
    }

    @Test
    void cancel_only_pending_jobs() {
        var job = handler.create("openai", "gpt-4o", "hi", null, true);
        assertThat(handler.cancel(job.id())).isTrue();
        assertThat(handler.get(job.id()).orElseThrow().status())
                .isEqualTo(ResponseJobHandler.Status.CANCELLED);
        // Re-cancel is no-op
        assertThat(handler.cancel(job.id())).isFalse();
    }

    @Test
    void toApiResponse_has_response_shape() {
        var job = handler.create("openai", "gpt-4o", "hello", "resp_prev", false);
        var api = handler.toApiResponse(job, false);
        assertThat(api).containsKeys("id", "object", "model", "status", "output", "usage");
        assertThat(api.get("object")).isEqualTo("response");
        assertThat(api.get("previous_response_id")).isEqualTo("resp_prev");
        assertThat(api.get("status")).isEqualTo("completed");
    }

    @Test
    void previous_response_id_chain_preserves_chain() {
        var first = handler.create("openai", "gpt-4o", "first", null, false);
        var second = handler.create("openai", "gpt-4o", "second", first.id(), false);
        assertThat(second.previousResponseId()).isEqualTo(first.id());
    }
}