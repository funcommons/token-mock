package fun.commons.tokenmock.handler.gemini;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store for Gemini Files API.
 * <p>
 * Gemini uses a {@code resumable upload} protocol that boils down to two
 * requests — start + upload/finalize — but we skip the byte stream by
 * collapsing both into a single {@code POST /upload/v1beta/files} that
 * carries the file content. The bytes aren't kept (we just remember the
 * metadata) so token-mock can fit a real byte budget on disk.
 * <p>
 * File references are name URI like {@code files/abc123}; we generate
 * {@code mock-<uuid>} ids so tests can pick them out easily.
 */
@Component
public class GeminiFileStore {

    public enum State { PROCESSING, ACTIVE, FAILED }

    public record FileEntry(
            String name,            // files/xxx
            String displayName,     // original filename
            String mimeType,
            long sizeBytes,
            Instant createTime,
            Instant updateTime,
            Instant expireTime,
            State state,
            String sha256,
            String uri             // files/xxx (alias)
    ) {}

    private final ConcurrentMap<String, FileEntry> byName = new ConcurrentHashMap<>();

    public FileEntry save(String displayName, String mimeType, long sizeBytes) {
        String id = "mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String name = "files/" + id;
        Instant now = Instant.now();
        // Gemini marks files PROCESSING immediately and flips to ACTIVE shortly after.
        FileEntry entry = new FileEntry(
                name, displayName, mimeType, sizeBytes,
                now, now, now.plusSeconds(48 * 3600),
                State.ACTIVE, "",
                "https://generativelanguage.googleapis.com/v1beta/" + name);
        byName.put(name, entry);
        return entry;
    }

    public Optional<FileEntry> get(String name) {
        // Gemini clients may call with or without the "files/" prefix.
        String key = name.startsWith("files/") ? name : "files/" + name;
        return Optional.ofNullable(byName.get(key));
    }

    public List<FileEntry> list() {
        return List.copyOf(byName.values());
    }

    public boolean delete(String name) {
        String key = name.startsWith("files/") ? name : "files/" + name;
        return byName.remove(key) != null;
    }

    /**
     * Build the OpenAPI-shaped File payload. Mirrors the real Gemini response:
     * {@code {name, displayName, mimeType, sizeBytes, createTime, updateTime,
     * expireTime, sha256Hash, uri, state: {name, ...}}}.
     */
    public Map<String, Object> toApiResponse(FileEntry f) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", f.state().name());
        if (f.state() == State.FAILED) state.put("message", "[mock] simulated failure");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", f.name());
        body.put("displayName", f.displayName());
        body.put("mimeType", f.mimeType());
        body.put("sizeBytes", String.valueOf(f.sizeBytes()));
        body.put("createTime", f.createTime().toString());
        body.put("updateTime", f.updateTime().toString());
        body.put("expireTime", f.expireTime().toString());
        if (!f.sha256().isEmpty()) body.put("sha256Hash", f.sha256());
        body.put("uri", f.uri());
        body.put("state", state);
        body.put("source", "UPLOADED");
        body.put("videoMetadata", null);
        return body;
    }
}