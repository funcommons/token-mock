package fun.commons.tokenmock.core;

import fun.commons.tokenmock.config.FaultConfig;
import org.springframework.stereotype.Component;

import java.util.function.LongConsumer;

@Component
public class FaultInjector {

    private final LongConsumer sleeper;

    public FaultInjector() {
        this(ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Test-friendly constructor that lets us inject a fake sleeper. */
    public FaultInjector(LongConsumer sleeper) {
        this.sleeper = sleeper;
    }

    public FaultDecision check(FaultConfig cfg) {
        if (cfg == null) {
            return FaultDecision.ok();
        }
        if (cfg.getExtraLatencyMs() > 0) {
            sleeper.accept(cfg.getExtraLatencyMs());
        }
        if (cfg.getForceNextNFailures() > 0) {
            cfg.setForceNextNFailures(cfg.getForceNextNFailures() - 1);
            return FaultDecision.fail(cfg.getStatusCode());
        }
        if (cfg.getFailureRate() >= 1.0) {
            return FaultDecision.fail(cfg.getStatusCode());
        }
        if (cfg.getFailureRate() > 0.0 && Math.random() < cfg.getFailureRate()) {
            return FaultDecision.fail(cfg.getStatusCode());
        }
        return FaultDecision.ok();
    }

    public record FaultDecision(boolean shouldFail, int statusCode, String reason) {
        public static FaultDecision ok() {
            return new FaultDecision(false, 200, "ok");
        }
        public static FaultDecision fail(int statusCode) {
            return new FaultDecision(true, statusCode, "injected_fault");
        }
    }
}
