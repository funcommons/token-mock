package fun.commons.tokenmock.config;

import lombok.Data;

@Data
public class FaultConfig {

    /** 0~1,失败比例。 */
    private double failureRate = 0.0;

    /** 失败时返回的 HTTP 状态码。 */
    private int statusCode = 500;

    /** 接下来 N 个请求必失败;每次失败后递减。 */
    private int forceNextNFailures = 0;

    /** 固定延迟 (毫秒);VendorConfig.latencyMs 之上的额外延迟。 */
    private int extraLatencyMs = 0;
}
