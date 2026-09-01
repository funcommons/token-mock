# API Reference

Business endpoints are prefixed with the vendor slug; admin endpoints live under `/admin/**` (see [Admin & Stats](/en/guide/admin-api)). Everything is also browsable in the Swagger UI at <http://localhost:9999/swagger-ui.html>.

::: tip Conventions
- Replace `{slug}` in the URLs with any configured vendor: `openai`, `deepseek`, `anthropic`…
- Except for Ollama, every request must carry the vendor's key (see [Protocols & Vendor Routing](/en/guide/protocols))
- Response JSON matches the real vendors; unrecognized fields are ignored
:::

## Endpoint overview

### OpenAI family (`/{slug}/…`)

| Method | Endpoint | Modality | Notes |
|--------|----------|----------|-------|
| POST | `/v1/chat/completions` | chat | non-streaming / `stream:true` SSE / `tools` |
| POST | `/v1/embeddings` | embed | deterministic vectors |
| GET | `/v1/models` | - | this vendor's model list |
| POST | `/v1/audio/speech` | tts | text → binary MP3 |
| POST | `/v1/audio/transcriptions` | stt | multipart audio → JSON text |
| POST | `/v1/images/generations` | image-gen | URL or b64_json |
| POST | `/v1/images/edits` | image-gen | same (placeholder image) |
| POST | `/v1/videos` | video-gen | submit an async video job |
| GET | `/v1/videos/{id}` | video-gen | job status |
| GET | `/v1/videos/{id}/content` | video-gen | download MP4 |

### Anthropic (`/anthropic/…`)

| Method | Endpoint | Notes |
|--------|----------|-------|
| POST | `/v1/messages` | Messages API; `stream:true` emits SSE (`message_start` / `content_block_delta` / `message_stop`) |
| POST | `/v1/messages/count_tokens` | input-side token budget (`{input_tokens:N}`) |
| POST | `/v1/files` (multipart) | upload, returns `file_xxx` id |
| GET | `/v1/files` | list |
| GET | `/v1/files/{id}` | detail |
| GET | `/v1/files/{id}/content` | download |
| DELETE | `/v1/files/{id}` | delete |
| POST | `/v1/messages/batches` | create offline batch (`msgbatch_xxx`, `processing_status: in_progress`) |
| GET | `/v1/messages/batches/{id}` | detail |
| GET | `/v1/messages/batches/{id}/results` | jsonl, one line per request (`succeeded`/`errored`) |
| POST | `/v1/messages/batches/{id}/cancel` | cancel |
| GET | `/v1/messages/batches?limit=N` | list |
| GET | `/v1/models` | model list |

### Gemini (`/gemini/…`)

| Method | Endpoint | Notes |
|--------|----------|-------|
| POST | `/v1/models/{model}:generateContent` | non-streaming, accepts `file_data.file_uri` references |
| POST | `/v1/models/{model}:streamGenerateContent` | SSE streaming |
| POST | `/v1/models/{model}:countTokens` | `{totalTokens:N}` |
| POST | `/v1/models/{model}:embedContent` | single text embedding, `{embedding:{values:[768]}}` |
| POST | `/v1/models/{model}:batchEmbedContents` | batch embedding |
| POST | `/upload/v1beta/files` | resumable upload (single POST, bytes are received), `{name:"files/xxx", state:{name:"ACTIVE"}}` |
| GET | `/v1beta/files` | list |
| GET | `/v1beta/files/{name}` | detail |
| DELETE | `/v1beta/files/{name}` | delete |
| GET | `/v1/models` | model list |

### Azure (`/azure/…`)

| Method | Endpoint | Notes |
|--------|----------|-------|
| POST | `/openai/deployments/{dep}/chat/completions?api-version=…` | legacy deployment style |
| POST | `/openai/deployments/{dep}/embeddings?api-version=…` | legacy embedding |
| POST | `/openai/v1/responses?api-version=preview` | Responses API (sync / background / previous_response_id) |
| POST | `/openai/v1/responses/{id}/cancel` | cancel |
| GET | `/openai/v1/responses/{id}` | detail |
| POST | `/openai/v1/audio/speech?api-version=preview` | TTS, MP3 |
| POST | `/openai/v1/audio/transcriptions?api-version=preview` | STT (multipart) |
| POST | `/openai/v1/images/generations?api-version=preview` | image generation, URL / b64_json |
| POST | `/openai/v1/batches?api-version=preview` | offline batch (`batch_xxx`) |
| GET | `/openai/v1/batches/{id}` | detail |
| POST | `/openai/v1/batches/{id}/cancel` | cancel |

### Bedrock (`/bedrock/…`)

| Method | Endpoint | Notes |
|--------|----------|-------|
| POST | `/model/{vendor}.{model}/invoke` | legacy model-native payload (SigV4 skipped) |
| POST | `/model/{modelId}/converse` | Converse sync, unified schema, `messages[].content[].{text\|toolUse\|…}` |
| POST | `/model/{modelId}/converse-stream` | Converse SSE (`messageStart` / `contentBlockDelta` / `messageStop` / `metadata`) |

### Ollama (`/ollama/…`)

| Method | Endpoint | Notes |
|--------|----------|-------|
| POST | `/api/chat` | Ollama chat format |
| POST | `/api/embeddings` | Ollama embeddings format |

## Request / response examples

### chat.completion (non-streaming)

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hello"}]}'
```

```json
{
  "id": "chatcmpl-mock-24ce0a60a4a640cfabccada7",
  "object": "chat.completion",
  "created": 1788152332,
  "model": "gpt-4o",
  "choices": [{
    "index": 0,
    "message": { "role": "assistant", "content": "[mock] 你说的内容是: hello" },
    "finish_reason": "stop"
  }],
  "usage": { "prompt_tokens": 2, "completion_tokens": 9, "total_tokens": 11 }
}
```

Echo rule: the last user input appears in the response text as `[mock] 你说的内容是: <your input>` ("here's what you said"), so tests can assert both that the response came from the mock and that the request parameters were processed.

### SSE streaming

```bash
curl -N http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"hi"}]}'
```

```text
event:message
data:{"id":"chatcmpl-mock-40052ac1","object":"chat.completion.chunk","created":1788152351,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

event:message
data:{"id":"chatcmpl-mock-40052ac1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"[mock]"},"finish_reason":null}]}

event:message
data:{"id":"chatcmpl-mock-40052ac1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":" 你说的内容是: hi"},"finish_reason":null}]}

data:{"id":"chatcmpl-mock-40052ac1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data:[DONE]
```

The text is split into chunks at token-estimated boundaries and pushed with a simulated typing cadence derived from the vendor's `latency-ms`, so client timeout, frame-by-frame parsing and disconnect handling are all genuinely exercised.

### Tool calls

When the request carries `tools`:

```json
{
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_mock_1",
        "type": "function",
        "function": { "name": "get_weather", "arguments": "{\"location\":\"Beijing\"}" }
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

### Error responses

| Scenario | Status | Body |
|----------|--------|------|
| Missing / wrong key | 401 | `{"error":{"message":"Incorrect API key provided for vendor openai","type":"invalid_api_key"}}` |
| Unknown model | 404 | `{"error":{"message":"model xxx not found",...}}` |
| Rate limited | 429 | `{"error":{"message":"Rate limit exceeded",...}}` + `Retry-After` header |
| Injected fault | configurable (500 etc.) | `{"error":{"message":"[mock] injected failure",...}}` |
| Malformed request | 400 | standard validation error |
