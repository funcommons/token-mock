# Changelog

## v1.1.0 (2026-08-31)

New endpoints (covers higher-frequency vendor-original capabilities):

- **Anthropic `POST /v1/messages/count_tokens`** — used by Claude SDK at startup / for long-context quota checks; returns `{input_tokens:N}`
- **Google Gemini `:countTokens` + `:embedContent` + `:batchEmbedContents`** — deterministic 768-dim vectors, covers RAG and offline batch embedding
- **Azure OpenAI `/openai/deployments/{dep}/embeddings?api-version=...`** — required for Azure-side RAG integration tests; OpenAI-shaped so SDKs switch transparently

## v1.0.0 (2026-08-31)

Initial release.

- **6 protocols**: OpenAI / Anthropic / Gemini / Azure OpenAI / AWS Bedrock / Ollama, routed by path prefix on a single port
- **12 built-in vendors**: OpenAI, DeepSeek, Moonshot, Zhipu, Tongyi, Minimax and Mistral on the OpenAI family, each with its own key and model list
- **Every modality**: text (non-streaming / SSE / tool calls), embeddings (deterministic), TTS (MP3), STT (multipart), image generation (URL / b64_json), vision, async video generation (job polling + download)
- **Fault injection**: failureRate / forceNextNFailures / extraLatencyMs / statusCode, adjustable at runtime
- **Rate-limit simulation**: dual-dimension token buckets (QPS + tokens-per-second) with burst, 429 + Retry-After
- **Admin API**: `/admin/**` for vendor listing, stats (including p50 / p99), faults and rate limits, guarded by `X-Mock-Admin-Token`
- **OpenAPI**: springdoc-openapi with Swagger UI grouped by protocol
- **Distribution**: Spring Boot fat jar + Docker image (`ghcr.io/funcommons/token-mock`)
