package fun.commons.tokenmock.handler.gemini;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiFileStoreTest {

    private GeminiFileStore store;

    @BeforeEach
    void setup() { store = new GeminiFileStore(); }

    @Test
    void save_assigns_files_prefixed_name_and_active_state() {
        var e = store.save("video.mp4", "video/mp4", 1024);
        assertThat(e.name()).startsWith("files/");
        assertThat(e.state()).isEqualTo(GeminiFileStore.State.ACTIVE);
        assertThat(e.sizeBytes()).isEqualTo(1024);
    }

    @Test
    void save_then_get_by_full_name_and_short_id() {
        var e = store.save("x.bin", "application/octet-stream", 100);
        assertThat(store.get(e.name())).isPresent();
        // strip prefix
        String shortName = e.name().substring("files/".length());
        assertThat(store.get(shortName)).isPresent();
    }

    @Test
    void list_includes_saved_files() {
        store.save("a.bin", "text/plain", 10);
        store.save("b.bin", "text/plain", 20);
        assertThat(store.list()).hasSize(2);
    }

    @Test
    void delete_removes_and_returns_true() {
        var e = store.save("x.bin", "text/plain", 1);
        assertThat(store.delete(e.name())).isTrue();
        assertThat(store.get(e.name())).isEmpty();
        // second delete returns false
        assertThat(store.delete(e.name())).isFalse();
    }

    @Test
    void toApiResponse_has_files_shape() {
        var e = store.save("video.mp4", "video/mp4", 2048);
        var api = store.toApiResponse(e);
        assertThat(api.get("name")).isEqualTo(e.name());
        assertThat(api.get("displayName")).isEqualTo("video.mp4");
        assertThat(api.get("mimeType")).isEqualTo("video/mp4");
        assertThat(api.get("sizeBytes")).isEqualTo("2048");
        assertThat(api).containsKey("uri");
        Map<String, Object> state = (Map<String, Object>) api.get("state");
        assertThat(state.get("name")).isEqualTo("ACTIVE");
    }
}