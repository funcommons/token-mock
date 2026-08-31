# 故障注入

分布式系统最难的测试不是"成功路径",而是**失败路径**:重试有没有退避?熔断有没有打开?降级有没有兜底?token-mock 支持对**每个厂商独立**注入故障,且可运行时动态调整,不用重启服务。

## 故障参数

通过 `POST /admin/vendors/{slug}/faults` 调整(见[管理与统计](/guide/admin-api)),也可在 `application.yml` 里静态预置:

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `failureRate` | double (0~1) | 0 | 失败比例。0.3 = 30% 的请求失败 |
| `statusCode` | int | 500 | 失败时返回的 HTTP 状态码(500 / 503 / 429…都可) |
| `forceNextNFailures` | int | 0 | 强制接下来 N 个请求**必失败**,每失败一次递减 |
| `extraLatencyMs` | int | 0 | 额外固定延迟(叠加在厂商基础 `latency-ms` 之上) |

## 演练场景

### 让 OpenAI 30% 返回 500

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.3,"statusCode":500}'
```

### 强制接下来 5 次必失败

按次数注入比按比例更适合做**确定性断言**(测试重试逻辑:恰好重试 5 次后成功):

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"forceNextNFailures":5}'
```

### 注入额外延迟

模拟网络劣化 / 慢响应,验证客户端超时与取消逻辑:

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"extraLatencyMs":2000}'
```

### 模拟厂商"限流罢工"

失败状态码可以设为 429,与限流仿真叠加使用:

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":1.0,"statusCode":429}'
```

### 恢复正常

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.0,"forceNextNFailures":0,"extraLatencyMs":0}'
```

## 静态预置故障

在厂商配置里预置,服务一启动就带故障(适合固定压测场景):

```yaml
mock:
  vendors:
    - slug: openai
      protocol: openai
      key: "sk-mock-openai-1234567890abcdef"
      fault:
        failure-rate: 0.2
        status-code: 503
        extra-latency-ms: 500
```

## CI 里的典型用法

```bash
# 1. 拉起 mock
docker run -d -p 9999:9999 ghcr.io/funcommons/token-mock:latest

# 2. 注入"接下来 3 次必 500"
curl -X POST localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"forceNextNFailures":3,"statusCode":500}'

# 3. 跑你的业务代码 → 断言重试 3 次后第 4 次成功
mvn test -Dtest=RetryPolicyTest

# 4. 复位
curl -X POST localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" -d '{}'
```
