# Rate Limiting

A real vendor's 429 isn't a plain error: it carries `Retry-After`, quota headers, and is enforced on **two dimensions** — request count and token count. token-mock simulates all of it with token buckets so you can verify backoff and quota handling.

## Parameters

Each vendor has its own limiter, adjustable at runtime via `POST /admin/vendors/{slug}/rate-limit`:

| Parameter | Type | Default | Notes |
|-----------|------|---------|-------|
| `enabled` | boolean | false | Master switch |
| `qps` | double | 0 | Max requests per second; 0 = unlimited |
| `burstRequests` | double | 0 | Request bucket burst capacity |
| `tokensPerSecond` | double | 0 | Max tokens per second; 0 = unlimited |
| `burstTokens` | double | 0 | Token bucket burst capacity |

Both dimensions apply **simultaneously**: if either bucket is empty, the request is rejected.

## Drill scenarios

### Enable QPS limiting (5 requests/second)

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":true,"qps":5,"burstRequests":5}'
```

### Trip the limit

```bash
for i in {1..20}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:9999/openai/v1/chat/completions \
    -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
    -H "Content-Type: application/json" \
    -d '{"model":"gpt-4o","messages":[{"role":"user","content":"x"}]}'
done
# Output: a few 200s first, then all 429s once the bucket drains
# (passes ≈ burstRequests, varying slightly with pacing and bucket refill)
```

The 429 carries the same headers real vendors send:

```text
HTTP/1.1 429 Too Many Requests
Retry-After: 1
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
```

### Limit by tokens

Simulate "long prompts hitting the quota wall" (common for long-context apps):

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":true,"tokensPerSecond":200000,"burstTokens":100000}'
```

When the estimated token count exceeds the bucket, the request gets 429 and is counted as `rejectedByTokens` (see [Admin & Stats](/en/guide/admin-api)).

### Disable limiting

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":false}'
```

### Inspect config and bucket state

```bash
curl http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

## Static presets

Configured up front, effective from boot:

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

## Implementation notes

- **Token buckets**: one per dimension, refilled continuously at `qps` / `tokensPerSecond`; capacity is the burst
- **Atomic check-and-decrement**: acquiring and deducting tokens happen in the same critical section — no races
- **Separate counters**: QPS rejections and token rejections are counted apart, so bottlenecks are easy to localize
- **`Retry-After`**: rounded up from the refill time needed for one more request
