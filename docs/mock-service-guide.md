# onetoken4j-mock 接入指南

> 文档版本: V1.0.0 | 创建: 2026-07-18
> 关联: documents/mock-service-design.md / channel-service / relay-service
> 适用: 把 onetoken4j 平台渠道指向 mock,进行集成测试 / Demo / 故障演练

---

## 0. 快速开始

```bash
# 1. 启动 mock
cd /Users/justin/codes/funcommons/onetoken4j/backend
mvn -pl onetoken4j-mock spring-boot:run

# 2. 验证
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}'

# 3. 打开 Swagger UI
open http://localhost:9999/swagger-ui.html
```

---

## 1. 已配置厂商

启动后,mock 默认提供以下厂商。每家独立 key,所有请求按路径前缀路由。

| 厂商 | 路径前缀 | Key | 模型清单 |
|------|---------|-----|---------|
| OpenAI | `/openai` | `sk-mock-openai-1234567890abcdef` | gpt-4o, gpt-4o-mini, text-embedding-3-small |
| DeepSeek | `/deepseek` | `sk-mock-deepseek-abcdef1234567890` | deepseek-chat, deepseek-reasoner |
| Moonshot | `/moonshot` | `sk-mock-moonshot-xyz1234567890` | moonshot-v1-8k/32k/128k |
| Zhipu (GLM) | `/zhipu` | `sk-mock-zhipu-glmabcdef` | glm-4-plus/flash/air |
| Tongyi | `/tongyi` | `sk-mock-tongyi-qwen` | qwen-max/turbo |
| Minimax | `/minimax` | `sk-mock-minimax-abcd` | abab6.5-chat |
| Mistral | `/mistral` | `sk-mock-mistral-xyz` | mistral-large-latest |
| Anthropic | `/anthropic` | `sk-ant-mock-anthropic-xxx` | claude-3-5-sonnet/haiku |
| Gemini | `/gemini` | `AIzaSyMockGeminiKey1234567890` | gemini-1.5-pro/flash |
| Azure | `/azure` | `mock-azure-key-1234567890abcdef` | (deployment-style URL) |
| Bedrock | `/bedrock` | `mock-bedrock-aws4-key` | anthropic.claude-3-5-sonnet |
| Ollama | `/ollama` | `ollama` | llama3:8b, qwen2:7b |

---

## 2. 直接调用 Mock (绕过平台)

### OpenAI 协议族 (chat)

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}'
```

```bash
curl http://localhost:9999/deepseek/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-deepseek-abcdef1234567890" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}]}'
```

### 流式响应 (SSE)

```bash
curl -N http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"hi"}]}'
```

### 工具调用

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{
    "model":"gpt-4o",
    "messages":[{"role":"user","content":"weather"}],
    "tools":[{"type":"function","function":{"name":"get_weather","parameters":{}}}]
  }'
```

返回:`choices[0].message.tool_calls[0].function.name = "get_weather"`

### Embeddings

```bash
curl http://localhost:9999/openai/v1/embeddings \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"text-embedding-3-small","input":"hello"}'
```

### Anthropic Messages

```bash
curl http://localhost:9999/anthropic/v1/messages \
  -H "x-api-key: sk-ant-mock-anthropic-xxx" \
  -d '{"model":"claude-3-5-sonnet-20241022","messages":[{"role":"user","content":"hi"}]}'
```

### Gemini generateContent

```bash
curl -X POST "http://localhost:9999/gemini/v1/models/gemini-1.5-pro:generateContent" \
  -H "Authorization: Bearer AIzaSyMockGeminiKey1234567890" \
  -d '{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}'
```

### Azure (deployment URL)

```bash
curl http://localhost:9999/azure/openai/deployments/gpt-4o-deployment/chat/completions \
  -H "Authorization: Bearer mock-azure-key-1234567890abcdef" \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
```

### Bedrock (简化 SigV4)

```bash
curl http://localhost:9999/bedrock/model/anthropic.claude-v1/invoke \
  -H "Authorization: AWS4-HMAC-SHA256 Credential=..." \
  -d '{"prompt":"hi"}'
```

### Ollama

```bash
curl http://localhost:9999/ollama/api/chat \
  -d '{"model":"llama3:8b","messages":[{"role":"user","content":"hi"}]}'
```

---

## 3. 多模态

### TTS — 文本转语音

```bash
curl http://localhost:9999/openai/v1/audio/speech \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"tts-1","input":"你好","voice":"alloy"}' \
  --output speech.mp3

# 返回: 8KB 静音 MP3 占位文件,Content-Type: audio/mpeg
```

### STT — 语音转文本

```bash
curl http://localhost:9999/openai/v1/audio/transcriptions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -F "file=@audio.mp3" \
  -F "model=whisper-1" \
  -F "language=zh"

# 返回: {"text":"[mock] 识别到一段 ... 字节的音频,语言 zh", ...}
```

### 图片生成 (URL)

```bash
curl http://localhost:9999/openai/v1/images/generations \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"dall-e-3","prompt":"a cat","n":2}'

# 返回: {"data":[{"url":"http://localhost:9999/mock-files/...","revised_prompt":"[mock] a cat"}, ...]}
```

### 图片生成 (b64_json)

```bash
curl http://localhost:9999/openai/v1/images/generations \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"dall-e-3","prompt":"a cat","response_format":"b64_json"}'
```

### 视频生成 (异步任务)

```bash
# 1. 提交
JOB=$(curl -s http://localhost:9999/openai/v1/videos \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"sora-2","prompt":"a cat playing"}' | jq -r .id)

# 2. 强制完成 (跳过等待)
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{}' # (status is forceable via /admin/videos/{id}/complete — extend if needed)

# 3. 查询状态
curl http://localhost:9999/openai/v1/videos/$JOB

# 4. 下载视频
curl http://localhost:9999/openai/v1/videos/$JOB/content --output video.mp4
```

---

## 4. 通过 onetoken4j 平台调用

### 4.1 配置渠道指向 mock

```bash
# OpenAI 渠道指向 mock
curl -X POST http://localhost:8080/v1/channels \
  -H "X-Tenant-ID: tenant_demo" \
  -H "Authorization: Bearer <admin-jwt>" \
  -d '{
    "name":"Mock OpenAI",
    "type":"openai",
    "baseUrl":"http://localhost:9999/openai",
    "apiKey":"sk-mock-openai-1234567890abcdef",
    "supportedModelsCsv":"gpt-4o,gpt-4o-mini",
    "ownerType":"PLATFORM",
    "weight":1,
    "priority":0
  }'

# Anthropic 渠道
curl -X POST http://localhost:8080/v1/channels \
  -H "X-Tenant-ID: tenant_demo" \
  -d '{
    "name":"Mock Anthropic",
    "type":"anthropic",
    "baseUrl":"http://localhost:9999/anthropic",
    "apiKey":"sk-ant-mock-anthropic-xxx",
    "supportedModelsCsv":"claude-3-5-sonnet-20241022",
    "ownerType":"PLATFORM"
  }'
```

### 4.2 通过 relay 调用

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-ot-user-token" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}'

# 平台会:验 token → 选 channel → preConsume → 转发到 mock → settle
```

---

## 5. 故障演练

### 5.1 让 OpenAI 30% 返回 500

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.3,"statusCode":500}'
```

### 5.2 强制接下来 5 次必失败

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"forceNextNFailures":5}'
```

### 5.3 注入额外延迟

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"extraLatencyMs":2000}'
```

### 5.4 恢复正常

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.0,"forceNextNFailures":0,"extraLatencyMs":0}'
```

---

## 6. 限流测试

### 6.1 启用 QPS 限流 (每秒 5 次)

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":true,"qps":5,"burstRequests":5}'
```

### 6.2 触发限流

```bash
for i in {1..20}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:9999/openai/v1/chat/completions \
    -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
    -d '{"model":"gpt-4o","messages":[{"role":"user","content":"x"}]}'
done
# 输出: 200 200 200 200 200 429 429 429 ... (5 通过 + 15 限流)
```

429 响应含 `Retry-After` 和 `X-RateLimit-Remaining` 头。

### 6.3 关闭限流

```bash
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":false}'
```

---

## 7. 统计与监控

```bash
# 单个厂商统计
curl http://localhost:9999/admin/vendors/openai/stats \
  -H "X-Mock-Admin-Token: mock-admin-secret"

# 全局统计
curl http://localhost:9999/admin/stats \
  -H "X-Mock-Admin-Token: mock-admin-secret"

# 清空统计
curl -X POST http://localhost:9999/admin/reset \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

返回示例:
```json
{
  "openai": {
    "totalRequests": 1543,
    "successCount": 1500,
    "failureCount": 43,
    "rateLimitedCount": 12,
    "byModel": {"gpt-4o": 1200, "gpt-4o-mini": 343}
  }
}
```

---

## 8. Swagger UI

启动后访问: <http://localhost:9999/swagger-ui.html>

顶部下拉切换分组:
- `openai` — OpenAI 协议族 (7 家)
- `anthropic` / `gemini` / `azure` / `bedrock` / `ollama`
- `admin` — 管理接口

OpenAPI JSON: <http://localhost:9999/v3/api-docs>

---

## 9. 测试矩阵

| 场景 | curl 命令 | 期望 |
|------|----------|------|
| OpenAI chat | `POST /openai/v1/chat/completions` | 200 + chat.completion 对象 |
| OpenAI stream | `POST /openai/v1/chat/completions` (`stream:true`) | SSE 分块 + `[DONE]` |
| OpenAI tool_calls | 加 `tools` 字段 | `finish_reason: tool_calls` |
| OpenAI embeddings | `POST /openai/v1/embeddings` | 1536 维向量 |
| OpenAI TTS | `POST /openai/v1/audio/speech` | 二进制 MP3 |
| OpenAI STT | `POST /openai/v1/audio/transcriptions` | JSON 含 text |
| OpenAI 图片 URL | `POST /openai/v1/images/generations` | url 字段 |
| OpenAI 图片 b64 | `response_format=b64_json` | b64_json 字段 |
| 视频提交 | `POST /openai/v1/videos` | 200 + job_id |
| 视频状态 | `GET /openai/v1/videos/{id}` | status: queued/in_progress/completed |
| 视频下载 | `GET /openai/v1/videos/{id}/content` | 二进制 MP4 |
| 错误 key | `Authorization: Bearer wrong` | 401 |
| 故障注入 | `failureRate=1.0` | 500 |
| QPS 限流 | `qps=5`,20 并发 | 5 × 200 + 15 × 429 |
