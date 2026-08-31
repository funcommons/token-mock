package fun.commons.tokenmock.core;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates deterministic embedding vectors for any input text.
 * <p>
 * Uses SHA-256 of input as seed; values normalized to [-1, 1].
 */
@Component
public class EmbeddingGenerator {

    private static final MessageDigest SHA256;

    static {
        try {
            SHA256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public List<Double> vector(String input, int dimensions) {
        byte[] hash = SHA256.digest(input.getBytes(StandardCharsets.UTF_8));
        List<Double> v = new ArrayList<>(dimensions);
        for (int i = 0; i < dimensions; i++) {
            int byteIdx = i % hash.length;
            // Normalize byte (-128..127) to [-1, 1]
            double normalized = (hash[byteIdx] & 0xFF) / 127.5 - 1.0;
            v.add(normalized);
        }
        return v;
    }

    public Map<String, Object> embeddingsResponse(String model, List<String> inputs, int dimensions) {
        List<Map<String, Object>> data = new ArrayList<>();
        int totalTokens = 0;
        TokenEstimator estimator = new TokenEstimator();
        for (int i = 0; i < inputs.size(); i++) {
            List<Double> vector = vector(inputs.get(i), dimensions);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("object", "embedding");
            item.put("index", i);
            item.put("embedding", vector);
            data.add(item);
            totalTokens += estimator.estimate(inputs.get(i));
        }

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", totalTokens);
        usage.put("total_tokens", totalTokens);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        response.put("model", model);
        response.put("usage", usage);
        return response;
    }
}
