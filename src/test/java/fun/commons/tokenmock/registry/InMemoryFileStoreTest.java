package fun.commons.tokenmock.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFileStoreTest {

    private InMemoryFileStore store;

    @BeforeEach
    void setup() { store = new InMemoryFileStore(); }

    @Test
    void openai_ids_start_with_file_dash() {
        InMemoryFileStore.FileEntry e = store.save(
                InMemoryFileStore.Namespace.OPENAI, "a.jsonl", "fine-tune", "application/jsonl",
                "hello".getBytes());
        assertThat(e.id()).startsWith("file-");
    }

    @Test
    void anthropic_ids_start_with_file_underscore() {
        InMemoryFileStore.FileEntry e = store.save(
                InMemoryFileStore.Namespace.ANTHROPIC, "doc.pdf", "file", "application/pdf",
                "data".getBytes());
        assertThat(e.id()).startsWith("file_");
    }

    @Test
    void save_then_find_roundtrip() {
        InMemoryFileStore.FileEntry e = store.save(
                InMemoryFileStore.Namespace.OPENAI, "x.bin", "assistants", "application/octet-stream",
                new byte[]{1, 2, 3});
        assertThat(store.findById(e.id())).isPresent();
        assertThat(store.findById(e.id()).orElseThrow().bytes()).containsExactly(1, 2, 3);
        assertThat(store.findById(e.id()).orElseThrow().size()).isEqualTo(3);
    }

    @Test
    void list_filters_by_namespace() {
        store.save(InMemoryFileStore.Namespace.OPENAI, "a", "fine-tune", "text/plain", "a".getBytes());
        store.save(InMemoryFileStore.Namespace.OPENAI, "b", "fine-tune", "text/plain", "b".getBytes());
        store.save(InMemoryFileStore.Namespace.ANTHROPIC, "c", "file", "text/plain", "c".getBytes());
        assertThat(store.list(InMemoryFileStore.Namespace.OPENAI)).hasSize(2);
        assertThat(store.list(InMemoryFileStore.Namespace.ANTHROPIC)).hasSize(1);
    }

    @Test
    void delete_returns_true_and_subsequent_find_is_empty() {
        InMemoryFileStore.FileEntry e = store.save(
                InMemoryFileStore.Namespace.OPENAI, "x", "p", "text/plain", "x".getBytes());
        assertThat(store.delete(e.id())).isTrue();
        assertThat(store.findById(e.id())).isEmpty();
    }

    @Test
    void ids_are_unique() {
        InMemoryFileStore.FileEntry a = store.save(InMemoryFileStore.Namespace.OPENAI, "a", "p", "text/plain", "a".getBytes());
        InMemoryFileStore.FileEntry b = store.save(InMemoryFileStore.Namespace.OPENAI, "b", "p", "text/plain", "b".getBytes());
        assertThat(a.id()).isNotEqualTo(b.id());
    }
}