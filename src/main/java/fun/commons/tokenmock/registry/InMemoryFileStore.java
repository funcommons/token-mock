package fun.commons.tokenmock.registry;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory file store, shared by OpenAI / Anthropic Files API.
 * <p>
 * Both vendors expose {@code POST /v1/files} + {@code GET /v1/files/{id}/content} +
 * a few CRUD methods. Real S3-backed storage isn't needed for a mock; we just
 * generate an id, remember the bytes, and surface the metadata both protocols
 * expect. Slugs are namespaced ({@code file-} for OpenAI, {@code file_} for
 * Anthropic) so the two namespaces don't collide if a client uses both.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}; no eviction policy — the mock
 * is single-instance and the file ids are opaque to tests.
 */
@Component
public class InMemoryFileStore {

    public enum Namespace { OPENAI, ANTHROPIC }

    public record FileEntry(
            String id,
            String namespace,
            String filename,
            String purpose,
            String contentType,
            long size,
            Instant createdAt,
            byte[] bytes
    ) {}

    private static final String OPENAI_PREFIX = "file-";
    private static final String ANTHROPIC_PREFIX = "file_";
    // 24-char suffix is what the OpenAI docs show, Anthropic uses 29-ish; either way
    // the id is opaque to tests so a fixed length is fine.
    private static final int ID_SUFFIX_LEN = 24;

    private final ConcurrentMap<String, FileEntry> byId = new ConcurrentHashMap<>();

    public String createId(Namespace ns) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < ID_SUFFIX_LEN; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return (ns == Namespace.OPENAI ? OPENAI_PREFIX : ANTHROPIC_PREFIX) + sb;
    }

    public FileEntry save(Namespace ns, String filename, String purpose,
                          String contentType, byte[] bytes) {
        String id = createId(ns);
        FileEntry entry = new FileEntry(
                id, ns.name().toLowerCase(), filename, purpose, contentType,
                bytes.length, Instant.now(), bytes);
        byId.put(id, entry);
        return entry;
    }

    public Optional<FileEntry> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<FileEntry> list(Namespace ns) {
        return byId.values().stream()
                .filter(f -> f.namespace().equals(ns.name().toLowerCase()))
                .toList();
    }

    public boolean delete(String id) {
        return byId.remove(id) != null;
    }
}