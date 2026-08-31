package fun.commons.tokenmock.core;

import fun.commons.tokenmock.config.FaultConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectorTest {

    private FaultInjector injector;
    private final AtomicLong sleepCounter = new AtomicLong(0);

    @BeforeEach
    void setup() {
        injector = new FaultInjector((ms) -> sleepCounter.addAndGet(ms));
    }

    @Test
    void no_fault_config_always_passes() {
        FaultConfig cfg = new FaultConfig();
        assertThat(injector.check(cfg).shouldFail()).isFalse();
    }

    @Test
    void force_next_n_failures_takes_priority() {
        FaultConfig cfg = new FaultConfig();
        cfg.setForceNextNFailures(3);

        assertThat(injector.check(cfg).shouldFail()).isTrue();
        assertThat(injector.check(cfg).shouldFail()).isTrue();
        assertThat(injector.check(cfg).shouldFail()).isTrue();
        assertThat(injector.check(cfg).shouldFail()).isFalse();
    }

    @Test
    void force_failures_decrement_per_call() {
        FaultConfig cfg = new FaultConfig();
        cfg.setForceNextNFailures(2);
        injector.check(cfg);
        injector.check(cfg);
        assertThat(cfg.getForceNextNFailures()).isZero();
    }

    @Test
    void extra_latency_sleeps_before_proceeding() {
        FaultConfig cfg = new FaultConfig();
        cfg.setExtraLatencyMs(500);

        injector.check(cfg);
        assertThat(sleepCounter.get()).isEqualTo(500);
    }

    @Test
    void failure_rate_zero_never_fails() {
        FaultConfig cfg = new FaultConfig();
        cfg.setFailureRate(0.0);
        for (int i = 0; i < 100; i++) {
            assertThat(injector.check(cfg).shouldFail()).isFalse();
        }
    }

    @Test
    void failure_rate_one_always_fails() {
        FaultConfig cfg = new FaultConfig();
        cfg.setFailureRate(1.0);
        for (int i = 0; i < 10; i++) {
            assertThat(injector.check(cfg).shouldFail()).isTrue();
        }
    }

    @Test
    void check_returns_configured_status_code_on_failure() {
        FaultConfig cfg = new FaultConfig();
        cfg.setFailureRate(1.0);
        cfg.setStatusCode(429);

        FaultInjector.FaultDecision decision = injector.check(cfg);
        assertThat(decision.shouldFail()).isTrue();
        assertThat(decision.statusCode()).isEqualTo(429);
    }

    @Test
    void default_failure_status_is_500() {
        FaultConfig cfg = new FaultConfig();
        cfg.setFailureRate(1.0);
        assertThat(injector.check(cfg).statusCode()).isEqualTo(500);
    }
}
