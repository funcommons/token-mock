package fun.commons.tokenmock.core;

public record RateLimitResult(boolean accepted, String limitType, double configuredRate, long retryAfterSeconds) {

    public static RateLimitResult allow() {
        return new RateLimitResult(true, null, 0, 0);
    }

    public static RateLimitResult rejectByQps(double qps) {
        long retry = qps > 0 ? Math.max(1, (long) Math.ceil(1.0 / qps)) : 1;
        return new RateLimitResult(false, "qps", qps, retry);
    }

    public static RateLimitResult rejectByTokens(double tps) {
        return new RateLimitResult(false, "tokens", tps, 1);
    }
}
