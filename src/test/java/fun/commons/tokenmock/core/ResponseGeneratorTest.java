package fun.commons.tokenmock.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseGeneratorTest {

    private ResponseGenerator generator;

    @BeforeEach
    void setup() {
        TokenEstimator estimator = new TokenEstimator();
        generator = new ResponseGenerator(estimator);
    }

    @Test
    void echo_completion_extracts_last_user_message() {
        Map<String, Object> response = generator.chatCompletion(
                "gpt-4o",
                List.of(
                        Map.of("role", "system", "content", "You are helpful"),
                        Map.of("role", "user", "content", "你好")
                ),
                false
        );

        assertThat(response.get("object")).isEqualTo("chat.completion");
        assertThat(response.get("model")).isEqualTo("gpt-4o");
        assertThat(response.get("id")).asString().startsWith("chatcmpl-mock-");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        assertThat(choices).hasSize(1);
        assertThat(choices.get(0).get("finish_reason")).isEqualTo("stop");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        assertThat((String) message.get("content")).contains("你好");
        assertThat(message.get("role")).isEqualTo("assistant");

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        assertThat((Integer) usage.get("total_tokens")).isPositive();
    }

    @Test
    void echo_completion_handles_empty_messages() {
        Map<String, Object> response = generator.chatCompletion("gpt-4o", List.of(), false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        assertThat(choices).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        assertThat((String) message.get("content")).contains("[mock]");
    }

    @Test
    void tool_call_response_returned_when_tools_present() {
        Map<String, Object> response = generator.chatCompletion(
                "gpt-4o",
                List.of(Map.of("role", "user", "content", "weather in beijing")),
                true
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        assertThat(choices.get(0).get("finish_reason")).isEqualTo("tool_calls");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        assertThat(message.get("tool_calls")).asList().isNotEmpty();
    }

    @Test
    void models_listing_returns_known_models() {
        List<String> codes = List.of("gpt-4o", "gpt-4o-mini");
        Map<String, Object> response = generator.listModels("openai", codes);

        assertThat(response.get("object")).isEqualTo("list");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0)).containsEntry("id", "gpt-4o");
    }

    @Test
    void unique_id_generated_per_request() {
        Map<String, Object> r1 = generator.chatCompletion("m", List.of(), false);
        Map<String, Object> r2 = generator.chatCompletion("m", List.of(), false);
        assertThat(r1.get("id")).isNotEqualTo(r2.get("id"));
    }
}
