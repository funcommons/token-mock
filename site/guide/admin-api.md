# 管理与统计

所有 `/admin/**` 接口需要请求头 `X-Mock-Admin-Token`,值匹配 `mock.admin-token`(默认 `mock-admin-secret`,可用环境变量 `MOCK_ADMIN_TOKEN` 覆盖)。

## 接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/vendors` | 列出所有已注册厂商及配置 |
| GET | `/admin/vendors/{slug}` | 单个厂商详情 |
| GET | `/admin/vendors/{slug}/stats` | 该厂商请求计数 / 延迟分位 / 错误率 / 限流次数 |
| POST | `/admin/vendors/{slug}/faults` | 动态调整故障注入参数(见[故障注入](/guide/fault-injection)) |
| GET | `/admin/vendors/{slug}/rate-limit` | 查看当前限流配置与桶余量 |
| POST | `/admin/vendors/{slug}/rate-limit` | 动态调整 QPS / Tokens-per-second / burst(见[限流](/guide/rate-limit)) |
| GET | `/admin/stats` | 全局统计(Top 厂商、Top 模型、错误分布、限流分布) |
| POST | `/admin/reset` | 清空所有统计计数 |

## 厂商统计示例

```bash
curl http://localhost:9999/admin/vendors/openai/stats \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

```json
{
  "slug": "openai",
  "totalRequests": 1543,
  "successCount": 1500,
  "failureCount": 43,
  "averageLatencyMs": 215,
  "p50LatencyMs": 200,
  "p99LatencyMs": 350,
  "rateLimit": {
    "qpsLimit": 50,
    "tokensPerSecondLimit": 200000,
    "rejectedByQps": 12,
    "rejectedByTokens": 3,
    "currentBurstRequests": 47,
    "currentBurstTokens": 480000
  },
  "byModel": {
    "gpt-4o":      { "count": 1200, "avgLatencyMs": 210 },
    "gpt-4o-mini": { "count": 343,  "avgLatencyMs": 220 }
  }
}
```

## 全局统计

```bash
curl http://localhost:9999/admin/stats \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

返回按厂商聚合的请求/成功/失败/限流计数,以及按模型的全局排行,适合压测后快速判断"流量到底打到了哪些渠道"。

## 清空统计

```bash
curl -X POST http://localhost:9999/admin/reset \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

::: tip 测试断言建议
`/admin/**` 不仅能看,还能**当断言用**:跑完一轮调用后,断言 `totalRequests` / `failureCount` / `rejectedByQps` 等计数,可以精确验证"客户端到底发了几次请求、重试了几次" —— 这比在业务侧埋点更贴近真相。
:::

## 健康检查

Actuator 已暴露 `health` / `info` / `metrics`:

```bash
curl http://localhost:9999/actuator/health
# {"status":"UP"}
```

## 安全提示

`X-Mock-Admin-Token` 默认值是公开的 `mock-admin-secret`,设计上就**只用于本地 / CI 环境**:

- 不要把 mock 服务暴露到公网
- 生产部署建议用 `MOCK_ADMIN_TOKEN` 覆盖默认值,并在网络层加访问控制
