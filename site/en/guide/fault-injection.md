# Fault Injection

The hard part of distributed-system testing isn't the happy path — it's the failure paths: do retries back off? does the breaker open? does the fallback hold? token-mock injects faults **per vendor**, adjustable at runtime, with no restart.

## Fault parameters

Adjusted via `POST /admin/vendors/{slug}/faults` (see [Admin & Stats](/en/guide/admin-api)); can also be preset statically in `application.yml`:

| Parameter | Type | Default | Notes |
|-----------|------|---------|-------|
| `failureRate` | double (0~1) | 0 | Failure proportion; 0.3 = 30% of requests fail |
| `statusCode` | int | 500 | HTTP status returned on injected failure (500 / 503 / 429…) |
| `forceNextNFailures` | int | 0 | Force the next N requests to **fail deterministically**; decrements per failure |
| `extraLatencyMs` | int | 0 | Extra fixed latency on top of the vendor's base `latency-ms` |

## Drill scenarios

### Make OpenAI return 500 on 30% of calls

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.3,"statusCode":500}'
```

### Force the next 5 requests to fail

Count-based injection beats ratio-based injection when you need **deterministic assertions** (e.g. "retries exactly 5 times, then succeeds"):

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"forceNextNFailures":5}'
```

### Inject extra latency

Simulate network degradation / slow responses to exercise client timeouts and cancellation:

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"extraLatencyMs":2000}'
```

### Simulate a vendor "rate-limiting you to death"

The injected status code can be 429 and combined with rate-limit simulation:

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":1.0,"statusCode":429}'
```

### Restore normal behavior

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.0,"forceNextNFailures":0,"extraLatencyMs":0}'
```

## Static presets

Configure faults up front so the service comes up already degraded (handy for fixed load scenarios):

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

## Typical CI usage

```bash
# 1. Bring the mock up
docker run -d -p 9999:9999 ghcr.io/funcommons/token-mock:latest

# 2. Inject "next 3 requests must 500"
curl -X POST localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"forceNextNFailures":3,"statusCode":500}'

# 3. Run your code → assert the 4th attempt succeeds after 3 retries
mvn test -Dtest=RetryPolicyTest

# 4. Reset
curl -X POST localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" -d '{}'
```
