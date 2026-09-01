# API 参考

业务端点全部以厂商 slug 开头,管理端点在 `/admin/**`(见[管理与统计](/guide/admin-api))。所有端点也可通过 Swagger UI(<http://localhost:9999/swagger-ui.html>)在线调试。

::: tip 通用约定
- 替换 URL 中的 `{slug}` 为任意已配置厂商,如 `openai`、`deepseek`、`anthropic`…
- 除 Ollama 外,所有请求必须携带对应厂商的 key(见[协议与厂商路由](/guide/protocols))
- 响应的 JSON 结构与真实厂商对齐;未识别的字段会被忽略
:::

## 端点总览

### OpenAI 协议族(`/{slug}/…`)

| 方法 | 端点 | 模态 | 说明 |
|------|------|------|------|
| POST | `/v1/chat/completions` | chat | 非流式 / `stream:true` SSE / `tools` 工具调用 |
| POST | `/v1/embeddings` | embed | 确定性向量 |
| GET | `/v1/models` | - | 该厂商模型清单 |
| POST | `/v1/audio/speech` | tts | 文本 → 二进制 MP3 |
| POST | `/v1/audio/transcriptions` | stt | multipart 音频 → JSON 文本 |
| POST | `/v1/images/generations` | image-gen | URL 或 b64_json |
| POST | `/v1/images/edits` | image-gen | 同上(回显占位图) |
| POST | `/v1/videos` | video-gen | 提交视频异步任务 |
| GET | `/v1/videos/{id}` | video-gen | 查询任务状态 |
| GET | `/v1/videos/{id}/content` | video-gen | 下载 MP4 |

### Anthropic(`/anthropic/…`)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/v1/messages` | Messages API,`stream:true` 走 SSE(`message_start` / `content_block_delta` / `message_stop`) |
| POST | `/v1/messages/count_tokens` | 输入侧 token 预算(`{input_tokens:N}`) |
| POST | `/v1/files` (multipart) | 上传,返回 `file_xxx` id |
| GET | `/v1/files` | 列表 |
| GET | `/v1/files/{id}` | 详情 |
| GET | `/v1/files/{id}/content` | 下载 |
| DELETE | `/v1/files/{id}` | 删除 |
| POST | `/v1/messages/batches` | 创建离线 batch(`msgbatch_xxx`,processing_status `in_progress`) |
| GET | `/v1/messages/batches/{id}` | 详情 |
| GET | `/v1/messages/batches/{id}/results` | jsonl,每行一个 request 结果(`succeeded`/`errored`) |
| POST | `/v1/messages/batches/{id}/cancel` | 取消 |
| GET | `/v1/messages/batches?limit=N` | 列表 |
| GET | `/v1/models` | 模型清单 |

### Gemini(`/gemini/…`)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/v1/models/{model}:generateContent` | 非流式,接受 `file_data.file_uri` 引用 |
| POST | `/v1/models/{model}:streamGenerateContent` | SSE 流式 |
| POST | `/v1/models/{model}:countTokens` | `{totalTokens:N}` |
| POST | `/v1/models/{model}:embedContent` | 单文本 embedding,`{embedding:{values:[768个]}}` |
| POST | `/v1/models/{model}:batchEmbedContents` | 批量 embedding |
| POST | `/upload/v1beta/files` | resumable 上传(单 POST,实际收字节),`{name:"files/xxx", state:{name:"ACTIVE"}}` |
| GET | `/v1beta/files` | 列表 |
| GET | `/v1beta/files/{name}` | 详情 |
| DELETE | `/v1beta/files/{name}` | 删除 |
| GET | `/v1/models` | 模型清单 |

### Azure(`/azure/…`)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/openai/deployments/{dep}/chat/completions?api-version=…` | legacy deployment 风格 |
| POST | `/openai/deployments/{dep}/embeddings?api-version=…` | legacy embedding |
| POST | `/openai/v1/responses?api-version=preview` | Responses API(同步/异步/previous_response_id) |
| POST | `/openai/v1/responses/{id}/cancel` | 取消 |
| GET | `/openai/v1/responses/{id}` | 详情 |
| POST | `/openai/v1/audio/speech?api-version=preview` | TTS,返回 MP3 |
| POST | `/openai/v1/audio/transcriptions?api-version=preview` | STT(multipart) |
| POST | `/openai/v1/images/generations?api-version=preview` | 文生图 URL / b64_json |
| POST | `/openai/v1/batches?api-version=preview` | 离线 batch(`batch_xxx`) |
| GET | `/openai/v1/batches/{id}` | 详情 |
| POST | `/openai/v1/batches/{id}/cancel` | 取消 |

### Bedrock(`/bedrock/…`)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/model/{vendor}.{model}/invoke` | legacy 模型原生 payload(SigV4 跳过) |
| POST | `/model/{modelId}/converse` | Converse 同步,统一 schema,`messages[].content[].{text\|toolUse\|…}` |
| POST | `/model/{modelId}/converse-stream` | Converse SSE 流式(`messageStart` / `contentBlockDelta` / `messageStop` / `metadata`) |

### Ollama(`/ollama/…`)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/chat` | Ollama chat 格式 |
| POST | `/api/embeddings` | Ollama embeddings 格式 |

## 请求 / 响应示例

### chat.completion(非流式)

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}'
```

```json
{
  "id": "chatcmpl-mock-24ce0a60a4a640cfabccada7",
  "object": "chat.completion",
  "created": 1788152332,
  "model": "gpt-4o",
  "choices": [{
    "index": 0,
    "message": { "role": "assistant", "content": "[mock] 你说的内容是: 你好" },
    "finish_reason": "stop"
  }],
  "usage": { "prompt_tokens": 3, "completion_tokens": 9, "total_tokens": 12 }
}
```

回显规则:`messages` 里最后一条用户输入会出现在响应文本中,格式为 `[mock] 你说的内容是: <你的输入>` —— 方便断言"这条响应确实来自 mock、且参数传对了"。

### SSE 流式

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

分块粒度按估算 token 数切成若干 chunk,并模拟打字节奏(每 chunk 间隔随厂商 `latency-ms` 变化),客户端的超时/逐帧解析逻辑都能被真实覆盖。

### 工具调用

请求带 `tools` 时:

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

### 错误响应

| 场景 | 状态码 | 响应 |
|------|-------|------|
| key 缺失/错误 | 401 | `{"error":{"message":"Incorrect API key provided for vendor openai","type":"invalid_api_key"}}` |
| 模型不存在 | 404 | `{"error":{"message":"model xxx not found",...}}` |
| 触发限流 | 429 | `{"error":{"message":"Rate limit exceeded",...}}` + `Retry-After` 头 |
| 故障注入 | 500 等可配 | `{"error":{"message":"[mock] injected failure",...}}` |
| 请求体不合法 | 400 | 标准校验错误 |
