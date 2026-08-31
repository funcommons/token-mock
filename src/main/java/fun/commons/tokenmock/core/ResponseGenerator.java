package fun.commons.tokenmock.core;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ResponseGenerator {

    private static final String MOCK_PREFIX = "[mock] ";

    private final TokenEstimator tokenEstimator;
    private final SseChunker sseChunker;
    private final AtomicLong counter = new AtomicLong(0);

    public ResponseGenerator(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
        this.sseChunker = new SseChunker();
    }

    public Map<String, Object> chatCompletion(String model, List<Map<String, Object>> messages, boolean hasTools) {
        String userMessage = extractLastUserMessage(messages);
        String content = MOCK_PREFIX + "你说的内容是: " + userMessage;

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        if (hasTools) {
            message.put("tool_calls", List.of(buildToolCall()));
        } else {
            message.put("content", content);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", hasTools ? "tool_calls" : "stop");
        choice.put("logprobs", null);

        int promptTokens = messages.stream()
                .mapToInt(m -> tokenEstimator.estimate(String.valueOf(m.getOrDefault("content", ""))))
                .sum();
        int completionTokens = tokenEstimator.estimate(content);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", tokenEstimator.total(promptTokens, completionTokens));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", newCompletionId());
        response.put("object", "chat.completion");
        response.put("created", Instant.now().getEpochSecond());
        response.put("model", model);
        response.put("choices", List.of(choice));
        response.put("usage", usage);
        return response;
    }

    /** OpenAI SSE chunks for streaming chat completions. */
    public List<Map<String, Object>> chatCompletionStream(String model, List<Map<String, Object>> messages,
                                                           boolean includeUsage) {
        String userMessage = extractLastUserMessage(messages);
        String fullContent = MOCK_PREFIX + "你说的内容是: " + userMessage;

        String id = newCompletionId();
        long created = Instant.now().getEpochSecond();
        List<Map<String, Object>> chunks = new ArrayList<>();

        chunks.add(buildStreamChunk(id, model, created, "role", "assistant", null));
        for (String piece : sseChunker.chunk(fullContent)) {
            chunks.add(buildStreamChunk(id, model, created, "content", piece, null));
        }
        chunks.add(buildStreamChunk(id, model, created, null, null, "stop"));

        if (includeUsage) {
            int promptTokens = messages.stream()
                    .mapToInt(m -> tokenEstimator.estimate(String.valueOf(m.getOrDefault("content", ""))))
                    .sum();
            int completionTokens = tokenEstimator.estimate(fullContent);
            chunks.add(buildUsageChunk(id, model, created, promptTokens, completionTokens));
        }

        return chunks;
    }

    private Map<String, Object> buildUsageChunk(String id, String model, long created,
                                                 int promptTokens, int completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", tokenEstimator.total(promptTokens, completionTokens));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cached_tokens", 0);
        usage.put("prompt_tokens_details", details);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of());
        chunk.put("usage", usage);
        return chunk;
    }

    public Map<String, Object> listModels(String vendorSlug, List<String> modelCodes) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (String code : modelCodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", code);
            m.put("object", "model");
            m.put("created", Instant.now().getEpochSecond());
            m.put("owned_by", vendorSlug);
            data.add(m);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }

    public long nextCounter() {
        return counter.incrementAndGet();
    }

    private Map<String, Object> buildStreamChunk(String id, String model, long created,
                                                   String deltaKey, String deltaValue, String finishReason) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (deltaKey != null) {
            delta.put(deltaKey, deltaValue);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private Map<String, Object> buildToolCall() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_weather");
        function.put("arguments", "{\"location\":\"Beijing\"}");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_mock_" + String.format("%03d", counter.incrementAndGet()));
        call.put("type", "function");
        call.put("function", function);
        return call;
    }

    private String newCompletionId() {
        return "chatcmpl-mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String extractLastUserMessage(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(空)";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("user".equals(m.get("role"))) {
                Object content = m.get("content");
                if (content == null) return "(空)";
                return String.valueOf(content);
            }
        }
        Object last = messages.get(messages.size() - 1).get("content");
        return last == null ? "(空)" : String.valueOf(last);
    }
}
