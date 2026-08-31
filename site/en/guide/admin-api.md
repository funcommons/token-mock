# Admin & Stats

Every `/admin/**` endpoint requires the header `X-Mock-Admin-Token` matching `mock.admin-token` (default `mock-admin-secret`; override with the `MOCK_ADMIN_TOKEN` environment variable).

## Endpoints

| Method | Path | Notes |
|--------|------|-------|
| GET | `/admin/vendors` | List all registered vendors and their configuration |
| GET | `/admin/vendors/{slug}` | Single vendor details |
| GET | `/admin/vendors/{slug}/stats` | Request counts, latency percentiles, error rate, rate-limit counters |
| POST | `/admin/vendors/{slug}/faults` | Adjust fault injection at runtime (see [Fault Injection](/en/guide/fault-injection)) |
| GET | `/admin/vendors/{slug}/rate-limit` | Current limiter config and bucket state |
| POST | `/admin/vendors/{slug}/rate-limit` | Adjust QPS / tokens-per-second / burst (see [Rate Limiting](/en/guide/rate-limit)) |
| GET | `/admin/stats` | Global stats (top vendors, top models, error and rate-limit breakdowns) |
| POST | `/admin/reset` | Clear all counters |

## Vendor stats example

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

## Global stats

```bash
curl http://localhost:9999/admin/stats \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

Returns per-vendor request / success / failure / rate-limit counts plus a global per-model ranking — the quickest way to see where traffic actually went after a load run.

## Reset counters

```bash
curl -X POST http://localhost:9999/admin/reset \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

::: tip Use the admin API as an assertion surface
Beyond dashboards, `/admin/**` doubles as a **test assertion surface**: after a round of calls, assert on `totalRequests` / `failureCount` / `rejectedByQps` to verify exactly how many requests your client sent and how many times it retried — closer to the truth than instrumenting the client itself.
:::

## Health checks

Actuator exposes `health` / `info` / `metrics`:

```bash
curl http://localhost:9999/actuator/health
# {"status":"UP"}
```

## Security notes

The default `X-Mock-Admin-Token` is the public value `mock-admin-secret` — by design, **local / CI use only**:

- Don't expose the mock to the public internet
- For shared deployments, override it via `MOCK_ADMIN_TOKEN` and add network-level access control
