package fun.commons.tokenmock.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseChunkerTest {

    private final SseChunker chunker = new SseChunker();

    @Test
    void chunks_simple_text_into_multiple_pieces() {
        String text = "abcdefghijklmno";
        List<String> chunks = chunker.chunk(text, 5);
        assertThat(chunks).containsExactly("abcde", "fghij", "klmno");
    }

    @Test
    void chunks_smaller_than_chunk_size_return_single_piece() {
        List<String> chunks = chunker.chunk("hi", 10);
        assertThat(chunks).containsExactly("hi");
    }

    @Test
    void empty_text_returns_empty_list() {
        assertThat(chunker.chunk("", 10)).isEmpty();
        assertThat(chunker.chunk(null, 10)).isEmpty();
    }

    @Test
    void chunks_handle_remainder_correctly() {
        List<String> chunks = chunker.chunk("abcdefg", 3);
        assertThat(chunks).containsExactly("abc", "def", "g");
    }

    @Test
    void default_chunk_size_is_4() {
        List<String> chunks = chunker.chunk("abcdefgh", SseChunker.DEFAULT_CHUNK_SIZE);
        assertThat(chunks).hasSize(2);
    }
}
