# Architecture & Design

How the mock is built and why. The full design document (Chinese) lives at [docs/mock-service-design.md](https://github.com/funcommons/token-mock/blob/main/docs/mock-service-design.md).

## Package layout

```
fun.commons.tokenmock
├── web/        MockDispatchController  single entry: /{slug}/** routing
│               AdminVendorController    /admin/** management API
├── handler/    ProtocolHandler SPI      6 protocol implementations
│   ├── openai/    OpenAIProtocolHandler + AudioImageHandler
│   ├── anthropic/ AnthropicProtocolHandler
│   ├── gemini/    GeminiProtocolHandler
│   ├── azure/     AzureProtocolHandler
│   ├── bedrock/   BedrockProtocolHandler
│   ├── ollama/    OllamaProtocolHandler
│   └── video/     VideoJobHandler        async video job state machine
├── core/       ResponseGenerator        template responses + token estimation + SSE
│               SseChunker               chunk splitting and cadence
│               TokenEstimator           token estimation (chars/4)
│               FaultInjector            failure rate / forced failures / latency
│               VendorRateLimiter        two-dimensional token bucket
│               EmbeddingGenerator       deterministic vectors
│               PlaceholderResources     bundled MP3/PNG/MP4 placeholders
├── registry/   VendorRegistry           slug → vendor config (in-memory registry)
│               StatsCollector           request counts and latency percentiles
├── config/     MockProperties + VendorConfig/FaultConfig/RateLimitConfig…
└── exception/  MockExceptionHandler     unified errors → per-protocol error shapes
```

## Key design decisions

### 1. One entry point, shared protocol handlers

A single `MockDispatchController` catches `/{slug}/**`, resolves the slug through `VendorRegistry` to get the protocol, then dispatches to the matching `ProtocolHandler`. As a result the **seven OpenAI-family vendors share one implementation** — adding a vendor is configuration, not code.

### 2. Responses are "template + echo", not random text

Response text = a `[mock]` prefix plus the user's input. That buys three things:

- **Assertable** — tests can verify the request parameters were actually processed (model, message content and tool name all surface in the response)
- **Distinguishable** — you can tell at a glance a response came from the mock, so it can't be mistaken for a real result
- **Deterministic** — same input, same output; tests are replayable

### 3. SSE chunks arrive at a realistic cadence

`SseChunker` splits the echoed text into chunks at token-estimated boundaries, pushes them with a typing cadence derived from the vendor's `latency-ms`, and finishes with `finish_reason: stop` and `data: [DONE]`. Client timeouts, frame-by-frame parsing and reconnect handling all get genuinely exercised.

### 4. Fault injection sits after rate limiting, before generation

The pipeline is fixed: **auth → rate limit → fault injection → modality routing → response generation**. That ordering lets you compose precise scenarios ("was it the limiter or the fault that failed this call?"); all fault state is in-memory behind the admin API, so injecting and resetting never restarts the service.

### 5. Two-dimensional token-bucket rate limiting

`VendorRateLimiter` keeps one bucket per dimension — requests (QPS) and tokens (tokens/s) — with burst as bucket capacity and an atomic check-and-decrement. The 429 response carries `Retry-After` (derived from the refill rate) and `X-RateLimit-*` headers, matching real vendors. QPS rejections and token rejections are counted separately.

### 6. Deterministic embeddings

`EmbeddingGenerator` derives vectors from a hash of the input, so identical input always yields the identical vector. Tests can assert "twice the same / different inputs differ", and even use it to verify cache hits.

### 7. All in-memory, no persistence

Stats, fault parameters, rate-limit parameters and video job state live in memory. The payoff is **zero dependencies, second-scale startup, CI friendliness**; the cost is that a restart returns to the static config — the right trade for what a mock server is for.

## Testing

Unit tests cover the core components (response generation, SSE chunking, fault injection, rate limiter, every protocol handler, the admin API); `mvn verify` additionally enforces JaCoCo coverage. The manual acceptance matrix for load/chaos scenarios is at the end of [docs/mock-service-guide.md](https://github.com/funcommons/token-mock/blob/main/docs/mock-service-guide.md).
