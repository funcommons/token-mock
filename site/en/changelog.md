# Changelog

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
