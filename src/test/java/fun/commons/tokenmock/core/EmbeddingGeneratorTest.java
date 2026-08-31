package fun.commons.tokenmock.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingGeneratorTest {

    private final EmbeddingGenerator generator = new EmbeddingGenerator();

    @Test
    void generates_vector_of_requested_dimensions() {
        List<Double> v = generator.vector("hello", 1536);
        assertThat(v).hasSize(1536);
    }

    @Test
    void same_input_produces_same_vector() {
        List<Double> v1 = generator.vector("hello world", 8);
        List<Double> v2 = generator.vector("hello world", 8);
        assertThat(v1).isEqualTo(v2);
    }

    @Test
    void different_input_produces_different_vector() {
        List<Double> v1 = generator.vector("hello", 8);
        List<Double> v2 = generator.vector("world", 8);
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void values_are_in_valid_range() {
        List<Double> v = generator.vector("test", 128);
        assertThat(v).allSatisfy(d -> {
            assertThat(d).isBetween(-1.0, 1.0);
        });
    }

    @Test
    void empty_input_still_returns_vector() {
        List<Double> v = generator.vector("", 4);
        assertThat(v).hasSize(4);
    }

    @Test
    void embeddings_response_format() {
        var resp = generator.embeddingsResponse("text-embedding-3-small", List.of("hello", "world"), 8);
        assertThat(resp.get("object")).isEqualTo("list");
        @SuppressWarnings("unchecked")
        List<?> data = (List<?>) resp.get("data");
        assertThat(data).hasSize(2);
    }
}
