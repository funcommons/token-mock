# Changelog

## v1.5.0 (2026-09-04)

Aligned with token-gateway 0.8.0+ OpenAI-protocol task surface:

- **Sora-shape video job** — `POST /v1/videos` accepts `{model, prompt, seconds, size, notify_url}`, returns `{object:"video_generation", id:"T<24hex>"}` (same shape as the OneToken `task_no`)
- **Video /content 307 redirect** — `GET /v1/videos/{id}/content` returns 307 Location to signed proxy URL (mock redirects to `/mock-files/videos/{id}.mp4`; real gateway points at OSS / S3 pre-signed URL); `failed` state → 410 Gone
- **`failed` carries error envelope** — `{error:{code, message}}` aligned with token-gateway 0.8.0 §3.7
- **Image background mode** — `POST /v1/images/generations` with `{background:true}` → `image_generation` async job; `GET /v1/images/generations/{id}` returns OpenAI shape `{object, status, output:[{content:[{type:"output_image", image_url:{url:"/v1/resources/{id}/{i}"}}]}]}`
- **`/v1/images/sync` sync wrapper** — three exits: success `{created, data:[{url}]}` / 502 upstream failure (refunded) / 60s timeout `{status:"PROCESSING", task_no, poll_url}`; `n>1` → 400
- **`/mock-files/**` static resource** — `placeholder.png` / `videos/{id}.mp4` placeholders backing the 307 redirect, JSON envelope fallback for anything else

## v1.4.0 (2026-09-01)

Completes five-vendor protocol coverage; token-mock is now the only engineering-grade mock server covering Anthropic + Google Gemini + Azure OpenAI + Bedrock + OpenAI:

- **Bedrock Converse + ConverseStream** — `POST /model/{modelId}/converse` + `/converse-stream` (SSE), unified schema with `messages[].content[].{text|image|toolUse|toolResult}` polymorphic blocks; what LangChain `ChatBedrockConverse` and Strands Agents use by default
- **Anthropic Message Batches** — `POST/GET/cancel` + `GET .../results` (jsonl) + `GET .../batches` (list, `?limit=`), 5 endpoints; mirrors the OpenAI batches surface
- **Gemini Files API** — `POST /upload/v1beta/files` (resumable, receives bytes in our mock) + `GET/DELETE /v1beta/files/{name}` + list; `generateContent` accepts `file_data.file_uri` references (`state: ACTIVE`)
- **Azure OpenAI v1 API** — `/openai/v1/{responses, audio/speech, audio/transcriptions, images/generations, batches}` + cancel; same schema as OpenAI, accepts `?api-version=preview`; legacy `/openai/deployments/{dep}/...` coexists

## v1.3.0 (2026-08-31)

OpenAI's two main 2025 endpoints, covering Agent SDK and offline batch integration tests:

- **Responses API** — `POST /v1/responses` + `GET /v1/responses/{id}` + `POST /v1/responses/{id}/cancel`
  - `previous_response_id` state chaining (multi-turn conversation)
  - `background:true` async: returns queued, completes 250ms later; client polling semantics match real vendor
  - Internal: `ResponseJobHandler` (`ConcurrentHashMap` + single-thread `ScheduledExecutorService`)
- **Batches API** — `POST /v1/batches` + `GET /v1/batches/{id}` + `POST /v1/batches/{id}/cancel`
  - State machine `validating → in_progress → finalizing → completed`; cancel transitions to `cancelling`
  - Output `output_file_id` placeholder `file_batch_result_xxx`
  - Depends on the Files API (v1.2) for the full upload → batch → result chain

## v1.2.0 (2026-08-31)

Files API — upload / list / detail / content download / delete, shared between OpenAI and Anthropic through one in-memory store (`file-` vs `file_` prefixes keep namespaces separate):

- OpenAI `POST /v1/files` + 4 siblings — `file-xxx` ids, OpenAI response shape (`object/bytes/purpose/created_at`)
- Anthropic `POST /v1/files` + 4 siblings — `file_xxx` ids, Anthropic response shape (`type/filename/mime_type/size_bytes/downloadable`)
- Backend: `InMemoryFileStore` (in `registry/`, thread-safe via `ConcurrentHashMap`); tests assert namespace isolation
- Dispatch controller gains GET/DELETE routes plus multipart body extraction

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
