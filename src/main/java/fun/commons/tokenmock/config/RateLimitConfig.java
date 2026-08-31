package fun.commons.tokenmock.config;

import lombok.Data;

@Data
public class RateLimitConfig {

    private boolean enabled = false;

    /** 每秒最大请求数;0 = 不限。 */
    private double qps = 0;

    /** 突发请求桶容量。 */
    private double burstRequests = 0;

    /** 每秒最大 token 数;0 = 不限。 */
    private double tokensPerSecond = 0;

    /** 突发 token 桶容量。 */
    private double burstTokens = 0;
}
