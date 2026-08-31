package fun.commons.tokenmock.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void empty_text_estimates_to_zero() {
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate(null)).isZero();
    }

    @Test
    void ascii_text_uses_char_over_4_ratio() {
        int tokens = estimator.estimate("hello world hello world hello world");
        assertThat(tokens).isPositive();
        // ~33 chars → ~8 tokens
        assertThat(tokens).isBetween(5, 12);
    }

    @Test
    void chinese_text_uses_char_count_directly() {
        // 中文字符大约 1 字 = 1 token
        int tokens = estimator.estimate("你好世界");
        assertThat(tokens).isGreaterThanOrEqualTo(4);
    }

    @Test
    void total_combines_prompt_and_completion() {
        int total = estimator.total(100, 200);
        assertThat(total).isEqualTo(300);
    }

    @Test
    void total_handles_zero() {
        assertThat(estimator.total(0, 0)).isZero();
    }

    @Test
    void long_text_scales_proportionally() {
        String short_ = "hello";
        String long_ = short_.repeat(100);
        assertThat(estimator.estimate(long_)).isGreaterThan(estimator.estimate(short_) * 50);
    }
}
