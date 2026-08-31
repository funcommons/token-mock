package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.core.PlaceholderResources;
import fun.commons.tokenmock.handler.MockRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible audio + image endpoints.
 * <p>
 *   POST /v1/audio/speech            (TTS → MP3 bytes)
 *   POST /v1/audio/transcriptions    (STT ← multipart)
 *   POST /v1/audio/translations      (STT ← multipart)
 *   POST /v1/images/generations      (DALL-E → URL/b64)
 */
@Component
public class AudioImageHandler {

    private final PlaceholderResources placeholders;

    public AudioImageHandler(PlaceholderResources placeholders) {
        this.placeholders = placeholders;
    }

    public Object handleAudioSpeech(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String format = stringOr(body.get("response_format"), "mp3").toLowerCase();
        MediaType contentType = switch (format) {
            case "wav" -> MediaType.valueOf("audio/wav");
            case "opus" -> MediaType.valueOf("audio/opus");
            case "flac" -> MediaType.valueOf("audio/flac");
            case "aac" -> MediaType.valueOf("audio/aac");
            default -> MediaType.valueOf("audio/mpeg");
        };
        return ResponseEntity.ok().contentType(contentType).body(placeholders.silenceMp3());
    }

    public Object handleAudioTranscription(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String language = stringOr(body.get("language"), "english");
        Long fileSize = body.get("__file_size__") instanceof Number
                ? ((Number) body.get("__file_size__")).longValue()
                : 0L;
        String text = "[mock] 识别到一段 " + fileSize + " 字节的音频,语言 " + language;

        return ResponseEntity.ok(Map.of(
                "text", text,
                "language", language,
                "duration", 1.0
        ));
    }

    public Object handleImages(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String prompt = stringOr(body.get("prompt"), "(empty)");
        String responseFormat = stringOr(body.get("response_format"), "url");
        int n = body.get("n") instanceof Number ? ((Number) body.get("n")).intValue() : 1;

        List<Map<String, Object>> data = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            if ("b64_json".equals(responseFormat)) {
                item.put("b64_json", Base64.getEncoder().encodeToString(placeholders.placeholderPng()));
            } else {
                item.put("url", "http://localhost:9999/mock-files/images/placeholder.png");
            }
            item.put("revised_prompt", "[mock] " + prompt);
            data.add(item);
        }
        return ResponseEntity.ok(Map.of(
                "created", System.currentTimeMillis() / 1000,
                "data", data
        ));
    }

    /**
     * Extracts image dimensions from base64 data URI (without decoding).
     * Returns "NNxNN" string or "(unknown)" if parse fails.
     */
    public static String describeImage(String dataUri) {
        if (dataUri == null || dataUri.isEmpty()) return "(unknown)";
        int commaIdx = dataUri.indexOf(',');
        if (commaIdx < 0) return "(unknown)";
        String metadata = dataUri.substring(0, Math.min(commaIdx, 64));
        return metadata.length() + " bytes header";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object body) {
        if (body instanceof Map) return (Map<String, Object>) body;
        return new LinkedHashMap<>();
    }

    private String stringOr(Object v, String fb) {
        return v == null || String.valueOf(v).isBlank() ? fb : String.valueOf(v);
    }
}
