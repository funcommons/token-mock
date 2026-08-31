package fun.commons.tokenmock.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SseChunker {

    public static final int DEFAULT_CHUNK_SIZE = 4;

    public List<String> chunk(String text, int chunkSize) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, chunkSize);
        List<String> out = new ArrayList<>((text.length() + size - 1) / size);
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    public List<String> chunk(String text) {
        return chunk(text, DEFAULT_CHUNK_SIZE);
    }
}
