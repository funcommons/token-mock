# 限流

真实厂商的 429 不是普通错误:带 `Retry-After`、带配额头、按 **请求数** 和 **token 数** 两个维度分别限。token-mock 用令牌桶算法完整仿真这套行为,可以验证客户端的退避重试和配额处理逻辑。

## 限流参数

每个厂商独立一套限流器,通过 `POST /admin/vendors/{slug}/rate-limit` 动态调整:

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `enabled` | boolean | false | 限流总开关 |
| `qps` | double | 0 | 每秒最大请求数;0 = 不限 |
| `burstRequests` | double | 0 | 请求桶突发容量(允许的瞬时尖峰) |
| `tokensPerSecond` | double | 0 | 每秒最大 token 数;0 = 不限 |
| `burstTokens` | double | 0 | token 桶突发容量 |

两个维度**同时生效**:任一桶里没余量,请求即被拒。

## 演练场景

### 启用 QPS 限流(每秒 5 次)

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":true,"qps":5,"burstRequests":5}'
```

### 触发限流

```bash
for i in {1..20}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:9999/openai/v1/chat/completions \
    -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
    -H "Content-Type: application/json" \
    -d '{"model":"gpt-4o","messages":[{"role":"user","content":"x"}]}'
done
# 输出: 开头若干 200,桶抽干后全是 429
# (通过数 ≈ burstRequests,随请求间隔与桶回填速度略有浮动)
```

429 响应带有真实厂商同款响应头:

```text
HTTP/1.1 429 Too Many Requests
Retry-After: 1
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
```

### 按 token 数限流

模拟"大 prompt 撞配额墙"的场景(长上下文应用常见):

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":true,"tokensPerSecond":200000,"burstTokens":100000}'
```

估算的请求 token 数超过桶内余量时同样返回 429,统计口径记在 `rejectedByTokens`(见[管理与统计](/guide/admin-api))。

### 关闭限流

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":false}'
```

### 查看当前配置与桶余量

```bash
curl http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

## 静态预置限流

在厂商配置里预置,服务一启动即生效:

```yaml
mock:
  vendors:
    - slug: openai
      protocol: openai
      key: "sk-mock-openai-1234567890abcdef"
      rate-limit:
        enabled: true
        qps: 50
        burst-requests: 20
        tokens-per-second: 200000
        burst-tokens: 100000
```

## 实现要点

- **令牌桶**:每维度一个桶,按 `qps` / `tokensPerSecond` 的速率持续回填,容量即 burst
- **原子判定**:取 token 与扣减在同一临界区内完成,无竞态
- **统计口径**:被 QPS 拒与被 token 拒分开计数,便于定位瓶颈
- **`Retry-After`**:按桶回填到足够 1 个请求所需时间向上取整
