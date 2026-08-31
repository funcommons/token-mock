package fun.commons.tokenmock.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseGeneratorStreamTest {

    private ResponseGenerator generator;

    @BeforeEach
    void setup() {
        generator = new ResponseGenerator(new TokenEstimator());
    }

    @SuppressWarnings("unchecked")
    @Test
    void stream_with_usage_appends_final_usage_chunk() {
        List<Map<String, Object>> chunks = generator.chatCompletionStream(
                "gpt-4o",
                List.of(Map.of("role", "user", "content", "你好世界")),
                true
        );

        Map<String, Object> last = chunks.get(chunks.size() - 1);
        assertThat(last.get("choices")).isEqualTo(List.of());
        Map<String, Object> usage = (Map<String, Object>) last.get("usage");
        assertThat(usage).isNotNull();
        assertThat((int) usage.get("prompt_tokens")).isGreaterThan(0);
        assertThat((int) usage.get("completion_tokens")).isGreaterThan(0);
        assertThat((int) usage.get("total_tokens"))
                .isEqualTo((int) usage.get("prompt_tokens") + (int) usage.get("completion_tokens"));
        Map<String, Object> details = (Map<String, Object>) usage.get("prompt_tokens_details");
        assertThat(details).containsEntry("cached_tokens", 0);
    }

    @Test
    void stream_without_usage_does_not_append_usage_chunk() {
        List<Map<String, Object>> chunks = generator.chatCompletionStream(
                "gpt-4o",
                List.of(Map.of("role", "user", "content", "你好世界")),
                false
        );

        Map<String, Object> last = chunks.get(chunks.size() - 1);
        assertThat(last).doesNotContainKey("usage");
    }
}
