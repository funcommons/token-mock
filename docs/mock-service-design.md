# onetoken4j-mock 设计方案

> 文档版本: V1.2.0 | 创建: 2026-07-18 | 更新: 2026-07-18 — 增加声音 / 图片 / 视频多模态 + QPS/Tokens 限流配置
> 关联: documents/integration/rest-contracts.md / documents/dual-model-system-design.md / channel-service
> 适用: 平台集成测试 / 渠道压测 / 故障演练 / Demo 演示

---

## 0. 目标与非目标

### 目标

- **独立可启**: 一个 Spring Boot fat jar,无外部依赖 (PG/Redis/Kafka 都不需要)
- **多厂商协议**: OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama 6 种协议
- **同协议多厂商**: OpenAI 协议下可同时挂 8 家厂商 (OpenAI/DeepSeek/Moonshot/Zhipu/Tongyi/Minimax/GLM/Mistral),每家独立 key 和模型清单
- **多模态**: 文本 / **声音 (TTS+STT)** / **图片 (生成+理解)** / **视频 (异步生成)**
- **OpenAPI 文档**: Swagger UI 可视化 + 在线 curl 调试
- **接入文档**: 一篇 markdown 指导如何把 onetoken4j channel 接到 mock
- **行为完整**: 非流式 / SSE 流式 / 工具调用 / Embeddings / 多模态回显
- **故障注入**: 按比例返回 429 / 500 / 模拟延迟 / 触发超时

### 非目标

- ❌ 不做真实推理 (返回固定/回显内容,不调用任何真实模型)
- ❌ 不做持久化 (重启即清空,所有状态在内存)
- ❌ 不做用户管理 (单 admin token 保护管理接口)
- ❌ 不做高可用 (单实例,开发/测试用)
- ❌ 不替换 `relay-service` 的真实转发职责
- ❌ 不做真实音视频编解码 (返回固定二进制占位文件,校验协议而非内容)

---

## 1. 架构

```
┌────────────────────────────────────────────────────────────────┐
│                    onetoken4j-mock (port 9999)                 │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │  OpenAI      │  │ Anthropic    │  │  Gemini      │  ...    │
│  │  Protocol    │  │ Protocol     │  │  Protocol    │  (6 个) │
│  │  Handler     │  │ Handler      │  │  Handler     │         │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘         │
│         │                 │                 │                  │
│         └────────┬────────┴────────┬────────┘                  │
│                  ▼                 ▼                            │
│         ┌──────────────────────────────────┐                   │
│         │     Vendor Registry (内存)       │                   │
│         │  - slug → protocol/key/models    │                   │
│         │  - 配置在 application.yml        │                   │
│         └────────────────┬─────────────────┘                   │
│                          │                                     │
│         ┌────────────────▼─────────────────┐                   │
│         │   Response Generator             │                   │
│         │  - 模板响应 (echo / fixed / random)│                  │
│         │  - Token 估算 (jtokkit 不可用→字数/4)│                │
│         │  - SSE 分块                       │                   │
│         └────────────────┬─────────────────┘                   │
│                          │                                     │
│         ┌────────────────▼─────────────────┐                   │
│         │   Fault Injector                 │                   │
│         │  - 失败率 / 延迟 / 状态码         │                   │
│         └────────────────┬─────────────────┘                   │
│                          │                                     │
│         ┌────────────────▼─────────────────┐                   │
│         │   RateLimiter (令牌桶)            │                   │
│         │  - QPS / Tokens-per-second        │                   │
│         │  - 突发容量 (burst)               │                   │
│         │  - 429 + Retry-After              │                   │
│         └────────────────┬─────────────────┘                   │
│                          │                                     │
│         ┌────────────────▼─────────────────┐                   │
│         │   Modality Router                │                   │
│         │  - chat / embed / tts / stt       │                   │
│         │  - image-gen / image-vision       │                   │
│         │  - video-gen (异步)               │                   │
│         └────────────────┬─────────────────┘                   │
│                          │                                     │
│         ┌────────────────▼─────────────────┐                   │
│         │   Placeholders (内置二进制资源)   │                   │
│         │  - silence-1s.mp3 (TTS)           │                   │
│         │  - placeholder-256x256.png        │                   │
│         │  - placeholder-5s.mp4             │                   │
│         └──────────────────────────────────┘                   │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐    │
│  │  管理接口 (/admin/**, admin-token 保护)                │    │
│  │  - GET  /admin/vendors        列出已配置厂商            │    │
│  │  - POST /admin/vendors/{slug}/faults  动态调故障率      │    │
│  │  - GET  /admin/stats          请求计数/延迟分布         │    │
│  │  - POST /admin/reset          清空统计                  │    │
│  └────────────────────────────────────────────────────────┘    │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐    │
│  │  springdoc-openapi (Swagger UI @ /swagger-ui.html)     │    │
│  └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
         ▲
         │ HTTP
         │
  ┌──────┴────────┐
  │ onetoken4j    │
  │ channel-service 指向 http://localhost:9999/{vendor-slug}
  │ relay-service  转发请求
  └───────────────┘
```

---

## 2. 协议与厂商路由

### 2.1 路径前缀映射

**单端口 9999**,通过路径前缀区分协议;同一协议下可挂多个厂商 slug。

| 路径前缀              | 协议               | 支持的厂商 slug (示例)                                  |
|----------------------|-------------------|-------------------------------------------------------|
| `/openai/**`         | OpenAI Chat       | `openai` / `deepseek` / `moonshot` / `zhipu` / `tongyi` / `minimax` / `glm` / `mistral` |
| `/anthropic/**`      | Anthropic Messages| `anthropic`                                           |
| `/gemini/**`         | Google Gemini     | `gemini`                                              |
| `/azure/**`          | Azure OpenAI      | `azure` (deployment-style URL)                        |
| `/bedrock/**`        | AWS Bedrock       | `bedrock`                                             |
| `/ollama/**`         | Ollama            | `ollama`                                              |

> 同一协议下的多家厂商,**仅 key 和模型清单不同**,协议处理代码共用。

### 2.2 厂商路由规则

```
请求: POST http://localhost:9999/deepseek/v1/chat/completions
              └────────┬───────┘
                       │
                ┌──────▼──────┐
                │ Path 前缀   │ → deepseek
                │ = vendor slug│
                └──────┬──────┘
                       │
                ┌──────▼──────┐
                │ 查 Registry │ → protocol=OpenAI, key=sk-mock-deepseek-xxx
                └──────┬──────┘
                       │
                ┌──────▼──────┐
                │ 验 Authorization │
                └──────┬──────┘
                       │
                ┌──────▼──────┐
                │ OpenAI      │ → 处理 chat completions
                │ Protocol    │
                └─────────────┘
```

### 2.3 各协议端点清单

| 协议 | 方法 | 端点 (示例用 openai slug) | 说明 |
|------|------|---------------------------|------|
| OpenAI | POST | `/openai/v1/chat/completions` | 非流式 + stream=true |
| OpenAI | POST | `/openai/v1/embeddings` | 文本向量化 |
| OpenAI | GET  | `/openai/v1/models` | 返回该厂商模型清单 |
| Anthropic | POST | `/anthropic/v1/messages` | Messages API + stream |
| Anthropic | GET  | `/anthropic/v1/models` | 模型清单 |
| Gemini | POST | `/gemini/v1/models/{model}:generateContent` | 非流式 |
| Gemini | POST | `/gemini/v1/models/{model}:streamGenerateContent` | SSE 流式 |
| Gemini | GET  | `/gemini/v1/models` | 模型清单 |
| Azure | POST | `/azure/openai/deployments/{dep}/chat/completions?api-version=2024-02-15-preview` | deployment 风格 |
| Bedrock | POST | `/bedrock/model/{vendor}/{model}/invoke` | 简化 (跳过 SigV4 签名) |
| Ollama | POST | `/ollama/api/chat` | Ollama 格式 |

---

## 3. 配置模型

### 3.1 厂商注册表 (application.yml)

```yaml
mock:
  admin-token: "mock-admin-secret"

  vendors:
    # ============ OpenAI 协议族 (8 家) ============
    - slug: openai
      protocol: openai
      key: "sk-mock-openai-1234567890abcdef"
      latency-ms: 200
      failure-rate: 0.0
      models:
        - code: gpt-4o
          context: 128000
          modality: chat
        - code: gpt-4o-mini
          context: 128000
          modality: chat
        - code: text-embedding-3-small
          context: 8191
          modality: embed

    - slug: deepseek
      protocol: openai
      key: "sk-mock-deepseek-abcdef1234567890"
      latency-ms: 300
      failure-rate: 0.0
      models:
        - code: deepseek-chat
          context: 64000
          modality: chat
        - code: deepseek-reasoner
          context: 64000
          modality: chat

    - slug: moonshot
      protocol: openai
      key: "sk-mock-moonshot-xyz1234567890"
      latency-ms: 250
      failure-rate: 0.0
      models:
        - code: moonshot-v1-8k
          context: 8000
        - code: moonshot-v1-32k
          context: 32000
        - code: moonshot-v1-128k
          context: 128000

    - slug: zhipu
      protocol: openai
      key: "sk-mock-zhipu-glmabcdef"
      latency-ms: 280
      models:
        - code: glm-4-plus
        - code: glm-4-flash
        - code: glm-4-air

    - slug: tongyi
      protocol: openai
      key: "sk-mock-tongyi-qwen"
      latency-ms: 220
      models:
        - code: qwen-max
        - code: qwen-plus
        - code: qwen-turbo

    - slug: minimax
      protocol: openai
      key: "sk-mock-minimax-abcd"
      latency-ms: 320
      models:
        - code: abab6.5-chat
        - code: abab6.5s-chat

    - slug: mistral
      protocol: openai
      key: "sk-mock-mistral-xyz"
      latency-ms: 180
      models:
        - code: mistral-large-latest
        - code: mistral-small-latest
        - code: mistral-embed

    # ============ OpenAI 协议族 — 附加模态厂商 ============
    - slug: elevenlabs
      protocol: openai
      key: "sk-mock-elevenlabs-xxx"
      latency-ms: 400
      models:
        - code: eleven-multilingual-v2
          modality: tts
        - code: eleven-v2
          modality: tts

    - slug: whisper
      protocol: openai
      key: "sk-mock-whisper-xxx"
      latency-ms: 600
      models:
        - code: whisper-1
          modality: stt

    - slug: dall-e
      protocol: openai
      key: "sk-mock-dalle-xxx"
      latency-ms: 1500
      models:
        - code: dall-e-3
          modality: image-gen
        - code: dall-e-2
          modality: image-gen

    - slug: stability
      protocol: openai
      key: "sk-mock-stability-xxx"
      latency-ms: 1800
      models:
        - code: stable-diffusion-xl
          modality: image-gen
        - code: stable-image-ultra
          modality: image-gen

    - slug: sora
      protocol: openai
      key: "sk-mock-sora-xxx"
      latency-ms: 200       # 提交快,但生成时间长
      models:
        - code: sora-2
          modality: video-gen
        - code: sora-turbo
          modality: video-gen

    - slug: runway
      protocol: openai
      key: "sk-mock-runway-xxx"
      latency-ms: 250
      models:
        - code: gen-3-alpha
          modality: video-gen
        - code: gen-3-alpha-turbo
          modality: video-gen

    # ============ Anthropic ============
    - slug: anthropic
      protocol: anthropic
      key: "sk-ant-mock-anthropic-xxx"
      latency-ms: 250
      failure-rate: 0.0
      models:
        - code: claude-3-5-sonnet-20241022
        - code: claude-3-5-haiku-20241022
        - code: claude-3-opus-20240229

    # ============ Gemini ============
    - slug: gemini
      protocol: gemini
      key: "AIzaSyMockGeminiKey1234567890"
      latency-ms: 280
      models:
        - code: gemini-1.5-pro
        - code: gemini-1.5-flash
        - code: gemini-1.0-pro

    # ============ Azure OpenAI (deployment-style) ============
    - slug: azure
      protocol: azure
      key: "mock-azure-key-1234567890abcdef"
      latency-ms: 300
      deployments:
        - deployment: gpt-4o-deployment
          model: gpt-4o
        - deployment: gpt-35-turbo-deployment
          model: gpt-35-turbo

    # ============ AWS Bedrock (简化 SigV4) ============
    - slug: bedrock
      protocol: bedrock
      key: "mock-bedrock-aws4-key"
      latency-ms: 350
      models:
        - code: anthropic.claude-3-5-sonnet-20240620-v1:0
        - code: amazon.titan-text-express-v1

    # ============ Ollama ============
    - slug: ollama
      protocol: ollama
      key: "ollama"  # Ollama 默认无 key,占位
      latency-ms: 500
      models:
        - code: llama3:8b
        - code: qwen2:7b
```

### 3.2 配置类

```java
@ConfigurationProperties(prefix = "mock")
public class MockProperties {
    private String adminToken;
    private List<VendorConfig> vendors;
    // ...
}

public class VendorConfig {
    private String slug;          // openai
    private String protocol;      // openai / anthropic / gemini / azure / bedrock / ollama
    private String key;
    private int latencyMs;
    private double failureRate;
    private List<ModelConfig> models;
    private List<DeploymentConfig> deployments;  // 仅 Azure
}
```

---

## 4. 协议处理器接口

```java
public interface ProtocolHandler {

    /** 协议名 (openai / anthropic / gemini / ...) */
    String protocol();

    /** 注册此协议下的所有 HTTP 路由 (PathConsumer 注册到 RouterFunctions) */
    void register(VendorConfig vendor, RouterFunctions.Builder router);

    /** 验证 Authorization 头 (返回 401 时抛 ProtocolAuthException) */
    void authenticate(VendorConfig vendor, String authHeader);

    /** 主响应生成逻辑 */
    Object generate(VendorConfig vendor, MockRequest request);
}
```

### 4.1 实现清单

| Handler | 复杂度 | 备注 |
|---------|-------|------|
| `OpenAIProtocolHandler` | 中 | chat / embed / models,8 家厂商共用 |
| `AnthropicProtocolHandler` | 中 | messages / models, anthropic-specific header (`x-api-key`) |
| `GeminiProtocolHandler` | 中 | generateContent / streamGenerateContent,key 在 query |
| `AzureProtocolHandler` | 低 | 部署名→模型,其余复用 OpenAI schema |
| `BedrockProtocolHandler` | 低 | invoke 路径,SigV4 仅做格式校验 |
| `OllamaProtocolHandler` | 低 | /api/chat,json 字段不同 |

---

## 5. 响应模板

### 5.1 OpenAI 非流式响应

```json
{
  "id": "chatcmpl-mock-<uuid>",
  "object": "chat.completion",
  "created": 1784338744,
  "model": "gpt-4o",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "[mock] 你说的内容是: <回显用户最后一条消息>"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 25,
    "completion_tokens": 12,
    "total_tokens": 37
  }
}
```

### 5.2 OpenAI SSE 流式

```
data: {"id":"chatcmpl-mock-x","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"[mock]"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-x","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"content":" 你说的"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-x","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"content":" 内容是"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-x","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

```

### 5.3 OpenAI 工具调用响应

当请求 `tools` 字段非空时,返回一个固定 tool_call:

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "tool_calls": [{
        "id": "call_mock_001",
        "type": "function",
        "function": {
          "name": "get_weather",
          "arguments": "{\"location\":\"Beijing\"}"
        }
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

### 5.4 Embeddings 响应

```json
{
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "index": 0,
      "embedding": [0.0123, -0.0456, ...]   // 固定 1536 维 (text-embedding-3-small)
    }
  ],
  "model": "text-embedding-3-small",
  "usage": {"prompt_tokens": 8, "total_tokens": 8}
}
```

> 向量值: 按文本 hash 生成确定性伪随机数,相同输入永远得到相同向量 (便于测试相似度场景)。

### 5.5 Anthropic Messages 响应

```json
{
  "id": "msg_mock_<uuid>",
  "type": "message",
  "role": "assistant",
  "model": "claude-3-5-sonnet-20241022",
  "content": [
    {"type": "text", "text": "[mock] 回显: ..."}
  ],
  "stop_reason": "end_turn",
  "usage": {"input_tokens": 25, "output_tokens": 12}
}
```

### 5.6 Gemini generateContent 响应

```json
{
  "candidates": [{
    "content": {
      "parts": [{"text": "[mock] 回显: ..."}],
      "role": "model"
    },
    "finishReason": "STOP"
  }],
  "usageMetadata": {
    "promptTokenCount": 25,
    "candidatesTokenCount": 12,
    "totalTokenCount": 37
  }
}
```

---

## 5.7 多模态 — 声音 (TTS / STT)

### 5.7.1 端点清单

| 协议 | 方法 | 端点 | 模态 | 说明 |
|------|------|------|------|------|
| OpenAI | POST | `/openai/v1/audio/speech` | TTS | 文本→语音,返回二进制 MP3/WAV |
| OpenAI | POST | `/openai/v1/audio/transcriptions` | STT | multipart 上传音频→文本 |
| OpenAI | POST | `/openai/v1/audio/translations` | STT | 同上,翻译为英文 |
| ElevenLabs | POST | `/elevenlabs/v1/text-to-speech/{voice-id}` | TTS | 自定义协议 |
| Gemini | POST | `/gemini/v1/models/{model}:generateContent` | STT | inline_data 传 base64 音频 |

### 5.7.2 TTS 响应 (二进制)

```
POST /openai/v1/audio/speech
Content-Type: application/json
Authorization: Bearer sk-mock-dalle-xxx

{
  "model": "tts-1",
  "input": "你好世界",
  "voice": "alloy",
  "response_format": "mp3"
}

Response 200
Content-Type: audio/mpeg
Content-Length: 1024

<二进制 MP3 数据>
```

**Mock 实现**: 返回内置的 1 秒静音 MP3 (`classpath:/mock/silence-1s.mp3`),体积约 8KB。可选按 `input` 长度返回时长成比例的占位音频。

### 5.7.3 STT 响应

```
POST /openai/v1/audio/transcriptions
Content-Type: multipart/form-data; boundary=...
Authorization: Bearer sk-mock-whisper-xxx

--boundary
Content-Disposition: form-data; name="file"; filename="audio.mp3"
Content-Type: audio/mpeg

<二进制>
--boundary
Content-Disposition: form-data; name="model"

whisper-1
--boundary
Content-Disposition: form-data; name="language"

zh
--boundary--

Response 200
Content-Type: application/json

{
  "text": "[mock] 识别到一段 1234 字节的音频,语言 zh",
  "duration": 1.0,
  "language": "chinese"
}
```

**Mock 实现**: 不解码音频,只读取 `Content-Length` 和 form 中的 `language` 字段回显。可选 `verbose_json` 格式返回 segments。

---

## 5.8 多模态 — 图片 (生成 + 理解)

### 5.8.1 端点清单

| 协议 | 方法 | 端点 | 模态 | 说明 |
|------|------|------|------|------|
| OpenAI | POST | `/openai/v1/images/generations` | image-gen | DALL-E 风格文生图 |
| OpenAI | POST | `/openai/v1/images/edits` | image-edit | multipart 图+prompt |
| OpenAI | POST | `/openai/v1/images/variations` | image-var | 基于图片生成变体 |
| Stability | POST | `/stability/v2beta/stable-image/generate/sd3` | image-gen | Stability 协议 |
| OpenAI Chat | POST | `/openai/v1/chat/completions` | image-vision | messages 含 `image_url` |
| Gemini | POST | `/gemini/v1/models/{model}:generateContent` | image-vision | inline_data 传 base64 图 |

### 5.8.2 文生图响应

```json
{
  "created": 1784338744,
  "data": [
    {
      "url": "http://localhost:9999/mock-files/images/sample-001.png",
      "revised_prompt": "[mock] a cat sitting on a chair"
    }
  ]
}
```

或 `response_format=b64_json`:

```json
{
  "created": 1784338744,
  "data": [
    {
      "b64_json": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB...",
      "revised_prompt": "[mock] a cat"
    }
  ]
}
```

**Mock 实现**: 返回内置占位 PNG (`classpath:/mock/placeholder-256x256.png`,纯色 256×256,体积约 300B)。`revised_prompt` 字段回显请求的 `prompt` 字段。

`url` 模式下,实际二进制文件通过额外的静态端点 `/mock-files/images/{filename}` 提供 (mock 自带 simple file server)。

### 5.8.3 视觉理解 (Vision) 响应

请求示例 (OpenAI Chat):

```json
{
  "model": "gpt-4o",
  "messages": [{
    "role": "user",
    "content": [
      {"type": "text", "text": "这是什么?"},
      {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBOR..."}}
    ]
  }]
}
```

响应: 复用 5.1 的 chat 响应格式,内容为 `[mock] 收到一张 NNxNN 的图片,问题: 这是什么?`。

**Mock 实现**: 不解码 base64,只读取长度。识别 messages[].content 数组中 `type=image_url` 的元素,提取其 url 或 data URI 头部 (`data:image/png`),回显。

---

## 5.9 多模态 — 视频 (异步生成)

### 5.9.1 端点清单 (异步任务模型)

| 协议 | 方法 | 端点 | 说明 |
|------|------|------|------|
| OpenAI/Sora | POST | `/sora/v1/videos` | 提交生成任务,返回 job_id |
| OpenAI/Sora | GET  | `/sora/v1/videos/{id}` | 查询任务状态 |
| OpenAI/Sora | GET  | `/sora/v1/videos/{id}/content` | 下载视频二进制 |
| Runway | POST | `/runway/v1/image_to_video` | 提交 |
| Runway | GET  | `/runway/v1/tasks/{id}` | 查询 |

### 5.9.2 提交任务响应

```
POST /sora/v1/videos
Authorization: Bearer sk-mock-sora-xxx

{
  "model": "sora-2",
  "prompt": "a cat playing piano"
}

Response 200
{
  "id": "video_mock_<uuid>",
  "status": "queued",
  "model": "sora-2",
  "created_at": 1784338744,
  "url": "http://localhost:9999/sora/v1/videos/video_mock_<uuid>"
}
```

### 5.9.3 查询任务状态

任务状态机: `queued` → `in_progress` (延迟 N 秒) → `completed` / `failed`

```
GET /sora/v1/videos/video_mock_<uuid>

Response 200
{
  "id": "video_mock_<uuid>",
  "status": "completed",          ← 第二次轮询后变成 completed
  "model": "sora-2",
  "created_at": 1784338744,
  "completed_at": 1784338810,
  "url": "http://localhost:9999/sora/v1/videos/video_mock_<uuid>/content",
  "duration_seconds": 5.0,
  "resolution": "1080p"
}
```

**Mock 实现**: 任务存在内存 Map 中。提交时 `status=queued`,首次查询 30s 后变 `in_progress`,再过 30s 变 `completed`。可通过 admin 接口调整时长或强制 complete/failed。

### 5.9.4 下载视频内容

```
GET /sora/v1/videos/video_mock_<uuid>/content

Response 200
Content-Type: video/mp4
Content-Length: 24576

<二进制 MP4 占位数据>
```

**Mock 实现**: 返回内置占位 MP4 (`classpath:/mock/placeholder-5s.mp4`,5 秒黑屏,体积约 24KB)。

---

## 5.10 多模态响应路由总结

| 模态字段 (`ModelConfig.modality`) | 路径关键字 | 响应类型 |
|----------------------------------|-----------|---------|
| `chat` (默认) | `/v1/chat/completions`, `/v1/messages`, `/v1/models/x:generateContent` | JSON / SSE |
| `embed` | `/v1/embeddings` | JSON (向量数组) |
| `tts` | `/v1/audio/speech`, `/text-to-speech/{voice}` | 二进制 (MP3) |
| `stt` | `/v1/audio/transcriptions`, `/v1/audio/translations` | JSON (识别文本) |
| `image-gen` | `/v1/images/generations`, `/v1/images/edits` | JSON (URL / b64) |
| `image-vision` | `/v1/chat/completions` (含 image_url) | JSON (描述) |
| `video-gen` | `/v1/videos`, `/v1/image_to_video` | JSON (job_id), 后续轮询 |

**统一约定**: 所有厂商支持 OpenAI 协议族路径 (不同 slug 仅换前缀),如 `/elevenlabs/v1/audio/speech`、`/sora/v1/videos`、`/dall-e/v1/images/generations` 等。

---

## 6. 故障注入

### 6.1 静态配置 (per vendor)

```yaml
mock:
  vendors:
    - slug: openai
      failure-rate: 0.0     # 0~1,默认 0
      latency-ms: 200       # 固定延迟
```

### 6.2 动态调整 (运行时)

```
POST /admin/vendors/openai/faults
Content-Type: application/json
X-Mock-Admin-Token: mock-admin-secret

{
  "failureRate": 0.3,      # 30% 请求返回 500
  "latencyMs": 2000,       # 延迟提到 2s
  "statusCode": 429,        # 失败时返回的状态码 (默认 500)
  "forceNextNFailures": 5   # 接下来 5 个请求必失败,然后恢复正常
}
```

### 6.3 故障类型

| 类型 | 行为 | 用途 |
|------|------|------|
| `latency-ms` | Thread.sleep 之后再返回 | 测试超时和 P99 |
| `failure-rate` | 按比例返回指定状态码 | 测试重试/熔断 |
| `forceNextNFailures` | 接下来 N 个请求必失败 | 复现 bug |
| `force-failure-status` | 失败时的状态码 (429 / 500 / 503) | 触发不同重试策略 |

---

## 6.5 限流配置 (QPS / Tokens/s / 突发)

### 6.5.1 设计

每个 vendor 可独立配置:

| 字段 | 单位 | 说明 |
|------|------|------|
| `rate-limit.qps` | req/s | 每秒最大请求数 (TPS-like,按调用次数计) |
| `rate-limit.tokens-per-second` | token/s | 每秒可消费的最大 token 数 (按 prompt+completion 估算) |
| `rate-limit.burst-requests` | req | 突发请求桶容量 (令牌桶上限) |
| `rate-limit.burst-tokens` | token | 突发 token 桶容量 |
| `rate-limit.enabled` | bool | 是否启用限流 (默认 false) |

使用**双令牌桶**算法 (Guava `RateLimiter` 或自实现):
- 桶 A: 请求计数 (QPS)
- 桶 B: token 累计 (TPS)

任一桶耗尽,即返回 **429 Too Many Requests** + `Retry-After` 头。

### 6.5.2 配置示例

```yaml
mock:
  vendors:
    - slug: openai
      protocol: openai
      key: "sk-mock-openai-xxx"
      rate-limit:
        enabled: true
        qps: 50                          # 每秒最多 50 次调用
        burst-requests: 100              # 突发 100 次
        tokens-per-second: 200000        # 每秒最多 20 万 token
        burst-tokens: 500000             # 突发 50 万 token

    - slug: deepseek
      protocol: openai
      rate-limit:
        enabled: true
        qps: 20                          # 较小厂商,QPS 限制更严
        tokens-per-second: 80000

    - slug: sora                          # 视频生成,按调用而非 token 限流
      protocol: openai
      rate-limit:
        enabled: true
        qps: 2                           # 每秒最多 2 个视频任务
        burst-requests: 5
```

### 6.5.3 429 响应格式

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 2                            ← 建议 2 秒后重试
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1784338800

{
  "error": {
    "type": "rate_limit_exceeded",
    "message": "[mock] QPS limit (50/s) exceeded for vendor openai",
    "vendor": "openai",
    "limit_type": "qps",                  ← qps / tokens / both
    "retry_after_seconds": 2
  }
}
```

### 6.5.4 运行时调整

通过管理接口动态调整 (无需重启):

```
POST /admin/vendors/openai/rate-limit
X-Mock-Admin-Token: mock-admin-secret
Content-Type: application/json

{
  "enabled": true,
  "qps": 100,
  "tokensPerSecond": 500000,
  "burstRequests": 200
}

GET /admin/vendors/openai/rate-limit/status
→ 返回当前桶剩余量、最近 1 分钟被限流次数
```

### 6.5.5 测试场景

| 场景 | 触发方式 | 验证点 |
|------|---------|--------|
| QPS 触顶 | 100 个并发请求,QPS=50 | 后 50 个返回 429,`Retry-After` 正确 |
| Token 限流 | 单个超大 prompt 触发 TPS 耗尽 | 返回 429,`limit_type=tokens` |
| 突发恢复 | 突发 100 后等待 1 秒 | 桶恢复,后续请求成功 |
| 双桶协同 | QPS 未满但 TPS 满 | 仍 429,提示 token 限流 |
| 限流计数器 | /admin/stats | 记录被限流次数 |

### 6.5.6 实现要点

```java
public class VendorRateLimiter {
    private final RateLimiter requestBucket;       // Guava RateLimiter (QPS)
    private final RateLimiter tokenBucket;          // token/s
    private final AtomicLong rejectedCount;

    public RateLimitResult tryAcquire(int estimatedTokens) {
        if (!requestBucket.tryAcquire()) {
            rejectedCount.incrementAndGet();
            return RateLimitResult.rejectedByQps(requestBucket.getRate());
        }
        if (!tokenBucket.tryAcquire(estimatedTokens)) {
            rejectedCount.incrementAndGet();
            return RateLimitResult.rejectedByTokens(tokenBucket.getRate());
        }
        return RateLimitResult.ok();
    }
}
```

> Guava `RateLimiter` 已支持突发容量 (`SmoothBursty`),无需自己实现令牌桶。

---

## 7. 管理接口

所有 `/admin/**` 接口需要 `X-Mock-Admin-Token` 头,值匹配 `mock.admin-token`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/vendors` | 列出所有已注册厂商及配置 |
| GET | `/admin/vendors/{slug}` | 单个厂商详情 |
| GET | `/admin/vendors/{slug}/stats` | 该厂商请求计数 / 平均延迟 / 错误率 / 限流次数 |
| POST | `/admin/vendors/{slug}/faults` | 动态调整故障注入参数 |
| **GET** | **`/admin/vendors/{slug}/rate-limit`** | **查看当前限流配置和桶剩余量** |
| **POST** | **`/admin/vendors/{slug}/rate-limit`** | **动态调整 QPS / Tokens-per-second / burst** |
| POST | `/admin/reset` | 清空所有统计计数 |
| GET | `/admin/stats` | 全局统计 (Top 厂商、Top 模型、错误分布、限流分布) |

返回示例 (`GET /admin/vendors/openai/stats`):

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
    "gpt-4o": {"count": 1200, "avgLatencyMs": 210},
    "gpt-4o-mini": {"count": 343, "avgLatencyMs": 220}
  }
}
```

---

## 8. OpenAPI 文档

### 8.1 依赖

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

### 8.2 访问入口

| 入口 | 路径 |
|------|------|
| Swagger UI | http://localhost:9999/swagger-ui.html |
| OpenAPI JSON | http://localhost:9999/v3/api-docs |
| 分组: OpenAI 协议族 | http://localhost:9999/v3/api-docs/swagger-config |
| 分组配置 | 按协议分组,Swagger UI 顶部下拉切换 |

### 8.3 分组配置

```java
@Bean
public GroupedOpenApi openAiGroup() {
    return GroupedOpenApi.builder()
        .group("openai")
        .pathsToMatch("/openai/**", "/deepseek/**", "/moonshot/**", ...)
        .build();
}
// 类似 anthropic / gemini / azure / bedrock / ollama / admin
@Bean
public GroupedOpenApi audioGroup() {
    return GroupedOpenApi.builder()
        .group("audio")
        .pathsToMatch("/openai/v1/audio/**", "/elevenlabs/**", "/whisper/**")
        .build();
}
@Bean
public GroupedOpenApi imageGroup() {
    return GroupedOpenApi.builder()
        .group("image")
        .pathsToMatch("/dall-e/**", "/stability/**", "/openai/v1/images/**")
        .build();
}
@Bean
public GroupedOpenApi videoGroup() {
    return GroupedOpenApi.builder()
        .group("video")
        .pathsToMatch("/sora/**", "/runway/**")
        .build();
}
```

> Swagger UI 顶部下拉可切换: openai / anthropic / gemini / azure / bedrock / ollama / audio / image / video / admin。

---

## 9. 接入 onetoken4j

### 9.1 在 channel-service 配置 mock 渠道

调用 `POST /v1/channels` (admin token):

```bash
# OpenAI 渠道指向 mock
curl -X POST http://localhost:8080/v1/channels \
  -H "X-Tenant-ID: tenant_demo" \
  -H "Authorization: Bearer <admin-jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Mock OpenAI",
    "type": "openai",
    "baseUrl": "http://localhost:9999/openai",
    "apiKey": "sk-mock-openai-1234567890abcdef",
    "supportedModelsCsv": "gpt-4o,gpt-4o-mini",
    "ownerType": "PLATFORM",
    "weight": 1,
    "priority": 0
  }'

# Anthropic 渠道指向 mock
curl -X POST http://localhost:8080/v1/channels \
  -H "X-Tenant-ID: tenant_demo" \
  -d '{
    "name": "Mock Anthropic",
    "type": "anthropic",
    "baseUrl": "http://localhost:9999/anthropic",
    "apiKey": "sk-ant-mock-anthropic-xxx",
    "supportedModelsCsv": "claude-3-5-sonnet-20241022",
    "ownerType": "PLATFORM"
  }'

# DeepSeek (OpenAI 兼容协议) 渠道指向 mock
curl -X POST http://localhost:8080/v1/channels \
  -H "X-Tenant-ID: tenant_demo" \
  -d '{
    "name": "Mock DeepSeek",
    "type": "openai",
    "baseUrl": "http://localhost:9999/deepseek",
    "apiKey": "sk-mock-deepseek-abcdef1234567890",
    "supportedModelsCsv": "deepseek-chat,deepseek-reasoner",
    "ownerType": "PLATFORM"
  }'
```

### 9.2 通过平台调用

```bash
# 用户侧 — 通过 onetoken4j relay 调用
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-ot-user-token" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "你好"}]
  }'

# 响应:
# {"choices":[{"message":{"content":"[mock] 你说的内容是: 你好"}}],...}
```

### 9.3 故障演练脚本

```bash
# 1. 让 OpenAI mock 30% 返回 500
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -H "Content-Type: application/json" \
  -d '{"failureRate": 0.3, "statusCode": 500}'

# 2. 触发 10 次调用,观察 onetoken4j relay-service 的重试/熔断日志
for i in {1..10}; do
  curl http://localhost:8080/v1/chat/completions \
    -H "Authorization: Bearer sk-ot-user-token" \
    -d "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"$i\"}]}" &
done
wait

# 3. 恢复正常
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate": 0.0}'
```

### 9.4 BYOK 场景 (租户自建渠道)

租户在租户后台创建 BYOK 渠道指向 mock, billing_mode=BYPASS:

```bash
curl -X POST http://localhost:8080/v1/channels \
  -H "X-Tenant-ID: tenant_demo" \
  -H "X-User-ID: u1" \
  -d '{
    "name": "我的本地 Ollama",
    "type": "ollama",
    "baseUrl": "http://localhost:9999/ollama",
    "apiKey": "ollama",
    "supportedModelsCsv": "llama3:8b",
    "ownerType": "TENANT",
    "billingMode": "BYPASS"
  }'
```

调用时 relay-service 跳过 billing Saga,直接转发。

---

## 10. 模块结构

```
backend/onetoken4j-mock/
├── pom.xml
├── src/main/java/fun/commons/onetoken4j/mock/
│   ├── MockApplication.java                    ← @SpringBootApplication
│   ├── config/
│   │   ├── MockProperties.java                 ← @ConfigurationProperties
│   │   ├── VendorConfig.java
│   │   ├── ModelConfig.java                     ← modality 字段: chat/embed/tts/stt/image-gen/image-vision/video-gen
│   │   ├── DeploymentConfig.java                ← 仅 Azure
│   │   ├── FaultConfig.java                     ← 运行时可改
│   │   ├── RateLimitConfig.java                 ← qps / tokens-per-second / burst
│   │   └── OpenApiConfig.java                   ← springdoc 分组 (含 audio/image/video)
│   ├── registry/
│   │   ├── VendorRegistry.java                  ← 启动时加载 + 运行时查询
│   │   ├── StatsCollector.java                  ← 请求计数 / 延迟分布
│   │   └── VideoJobStore.java                   ← 视频任务状态机 (内存)
│   ├── handler/
│   │   ├── ProtocolHandler.java                 ← 接口
│   │   ├── openai/OpenAIProtocolHandler.java     ← 含 chat/embed/image-gen/image-vision
│   │   ├── openai/AudioSpeechHandler.java        ← /v1/audio/speech
│   │   ├── openai/AudioTranscriptionHandler.java ← multipart /v1/audio/transcriptions
│   │   ├── openai/ImagesHandler.java             ← /v1/images/generations
│   │   ├── video/VideoJobHandler.java            ← /v1/videos 异步任务
│   │   ├── anthropic/AnthropicProtocolHandler.java
│   │   ├── gemini/GeminiProtocolHandler.java     ← 含 inline_data vision
│   │   ├── azure/AzureProtocolHandler.java
│   │   ├── bedrock/BedrockProtocolHandler.java
│   │   └── ollama/OllamaProtocolHandler.java
│   ├── core/
│   │   ├── ResponseGenerator.java               ← 模板/回显/随机
│   │   ├── TokenEstimator.java                  ← 字数/4 估算
│   │   ├── EmbeddingGenerator.java              ← 确定性 hash 向量
│   │   ├── SseChunker.java                      ← 文本切块
│   │   ├── FaultInjector.java                   ← 延迟 / 失败率 / forceNextN
│   │   ├── VendorRateLimiter.java               ← 双令牌桶 (QPS + TPS)
│   │   ├── RateLimitResult.java                 ← 限流结果 (含 limit_type / retry_after)
│   │   ├── AudioPlaceholder.java                ← 返回内置 silence-1s.mp3
│   │   ├── ImagePlaceholder.java                ← 返回内置 256x256.png
│   │   ├── VideoPlaceholder.java                ← 返回内置 5s.mp4
│   │   └── ModalityRouter.java                  ← 按 ModelConfig.modality 分发
│   ├── web/
│   │   ├── MockRouterConfig.java                ← 启动时注册所有路由
│   │   ├── AdminAuthFilter.java                 ← X-Mock-Admin-Token 校验
│   │   ├── FileServingConfig.java               ← /mock-files/** 提供占位文件下载
│   │   └── admin/
│   │       ├── AdminVendorController.java
│   │       └── AdminStatsController.java
│   └── exception/
│       ├── MockException.java
│       └── MockExceptionHandler.java
├── src/main/resources/
│   ├── application.yml                          ← 默认厂商配置
│   ├── application-demo.yml                     ← Demo 用配置 (更低延迟)
│   └── mock/
│       ├── silence-1s.mp3                       ← TTS 占位音频 (~8KB)
│       ├── placeholder-256x256.png              ← 图片生成占位 (~300B)
│       └── placeholder-5s.mp4                   ← 视频生成占位 (~24KB)
└── src/test/java/
    └── fun/commons/onetoken4j/mock/
        ├── handler/openai/OpenAIProtocolHandlerTest.java
        ├── handler/openai/AudioSpeechHandlerTest.java
        ├── handler/openai/AudioTranscriptionHandlerTest.java
        ├── handler/openai/ImagesHandlerTest.java
        ├── handler/video/VideoJobHandlerTest.java
        ├── handler/anthropic/AnthropicProtocolHandlerTest.java
        ├── handler/gemini/GeminiProtocolHandlerTest.java
        ├── core/ResponseGeneratorTest.java
        ├── core/TokenEstimatorTest.java
        ├── core/EmbeddingGeneratorTest.java
        ├── core/SseChunkerTest.java
        ├── core/FaultInjectorTest.java
        ├── registry/VendorRegistryTest.java
        ├── registry/StatsCollectorTest.java
        └── web/MockEndToEndIT.java               ← MockMvc 完整流程
```

---

## 11. 技术栈

| 维度 | 选型 | 理由 |
|------|------|------|
| 框架 | Spring Boot 3.5.x (与主项目一致) | 复用依赖管理,避免版本漂移 |
| Web | spring-boot-starter-web (Tomcat) | 同步阻塞,足够简单 |
| 文档 | springdoc-openapi-starter-webmvc-ui 2.6.0 | OpenAPI 3 + Swagger UI |
| 配置 | @ConfigurationProperties | 厂商配置可读性强 |
| 路由 | `RouterFunction` 动态注册 | 启动时按 vendor 数量动态注册 |
| SSE | `SseEmitter` (Spring 内置) | OpenAI 流式协议 |
| **限流** | **Guava `RateLimiter` (双令牌桶)** | **QPS + Tokens/s + 突发容量,无需自己实现桶** |
| **文件上传** | **Spring multipart (`MultipartFile`)** | **STT 接收音频二进制** |
| **二进制响应** | **`ResponseEntity<byte[]>` + `Resource`** | **TTS/图片/视频下载** |
| JSON | Jackson (Spring 自带) | - |
| 测试 | JUnit 5 + MockMvc + AssertJ | 与主项目一致 |
| 父 POM | onetoken4j-parent | 复用 lombok / spotless / jacoco |

> 不依赖 PG / Redis / Kafka / MyBatis / framework4j (除 web 模块的 ApiResponse)。
> Guava 由 spring-boot-dependencies BOM 管理版本,无需显式声明。

---

## 12. 与主项目的边界

```
┌──────────────────────────────────────────────────────────────┐
│                     onetoken4j 主项目                          │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  channel-service (生产者)                              │  │
│  │     │ baseUrl=http://localhost:9999/{vendor-slug}      │  │
│  │     │ apiKey=sk-mock-{vendor}-xxx                      │  │
│  │     ▼                                                   │  │
│  │  relay-service 转发请求                                 │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
└──────────────────────────┼────────────────────────────────────┘
                           │ HTTP localhost:9999
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                  onetoken4j-mock (本项目)                     │
│  接收 → 验 key → 故障注入 → 生成响应 → 返回                   │
└──────────────────────────────────────────────────────────────┘
```

**mock 不依赖任何主项目模块** (除 `framework4j-web` 用 `ApiResponse`,可选)。主项目通过 HTTP 指向它。

---

## 13. 实现路线

按 TDD 节奏分 5 步,每步独立可交付。

### Step 1: 骨架 + 单协议 (OpenAI 非流式)

- 项目结构,pom.xml,application.yml
- `MockApplication` / `MockProperties` / `VendorRegistry`
- `ProtocolHandler` 接口 + `OpenAIProtocolHandler` (chat 非流式)
- `ResponseGenerator` + `TokenEstimator`
- 单元测试
- **交付物**: 能启动,`POST /openai/v1/chat/completions` 返回 mock 响应

### Step 2: 6 个协议 + 流式 + 工具调用 + Embeddings

- `AnthropicProtocolHandler` / `GeminiProtocolHandler` / `AzureProtocolHandler` / `BedrockProtocolHandler` / `OllamaProtocolHandler`
- `SseChunker` (流式输出)
- `EmbeddingGenerator` (确定性 hash 向量)
- 工具调用响应
- **交付物**: 6 个协议完整工作,流式 + 非流式 + 工具 + 向量

### Step 2.5: 多模态 — 声音 / 图片 / 视频

- `AudioSpeechHandler` (`/v1/audio/speech` 返回内置 MP3)
- `AudioTranscriptionHandler` (multipart 接收音频,返回回显文本)
- `ImagesHandler` (`/v1/images/generations` 返回 URL 或 b64 占位图)
- 视觉理解 (chat 协议中识别 `image_url`,回显图片大小)
- `VideoJobHandler` (异步任务模型 + 内存状态机 + 二进制下载)
- `AudioPlaceholder` / `ImagePlaceholder` / `VideoPlaceholder` (内置资源)
- `ModalityRouter` (按 `ModelConfig.modality` 路由到对应 handler)
- OpenAPI 文档新增 audio / image / video 分组
- **交付物**: 6 种模态全部工作,Swagger UI 多模态分组可调

### Step 3: 故障注入 + 限流 + 统计 + 管理接口

- `FaultInjector` (延迟 / 失败率 / forceNextN)
- `VendorRateLimiter` (双令牌桶 QPS + TPS,Guava RateLimiter)
- `RateLimitResult` + 429 响应 + `Retry-After` 头
- `/admin/vendors/{slug}/rate-limit` 动态调整接口
- `StatsCollector` (请求计数 / 延迟分布 / 限流次数,按模态分桶)
- `/admin/vendors/**` 接口
- `AdminAuthFilter`
- **交付物**: 可动态调故障率和限流,可看实时统计

### Step 4: OpenAPI 文档 + 接入文档

- springdoc 集成 + 分组
- Swagger UI 验证
- `documents/integration/mock-service-guide.md` (接入指南)
- README.md (使用说明)
- 端到端 IT 测试
- **交付物**: 完整文档 + 可交付 Demo

---

## 14. 测试矩阵

| 场景 | 测试 | 验证点 |
|------|------|--------|
| OpenAI 非流式 | `OpenAIProtocolHandlerTest` | 响应 schema / usage / 回显 |
| OpenAI 流式 | `OpenAIProtocolHandlerStreamTest` | SSE 分块正确,[DONE] 结尾 |
| OpenAI 工具调用 | 同上 | `tool_calls` 字段,`finish_reason` |
| OpenAI Embeddings | `OpenAIEmbeddingsTest` | 1536 维向量,相同输入→相同输出 |
| OpenAI 错误 key | `OpenAIAuthTest` | 401 + 错误体 |
| Anthropic 流式 | `AnthropicProtocolHandlerTest` | event-stream 协议 |
| Gemini 路径参数 | `GeminiProtocolHandlerTest` | `:generateContent` 解析正确 |
| Azure deployment 路由 | `AzureProtocolHandlerTest` | deployment→model 映射 |
| **TTS 音频生成** | `AudioSpeechHandlerTest` | 二进制 MP3 返回,Content-Type 正确 |
| **STT 音频转写** | `AudioTranscriptionHandlerTest` | multipart 解析,回显文本 |
| **图片生成 URL** | `ImagesHandlerTest` | 返回 url 字段,占位 PNG 可下载 |
| **图片生成 b64** | `ImagesHandlerB64Test` | 返回 b64_json 字段,base64 合法 |
| **视觉理解** | `VisionChatHandlerTest` | 识别 image_url,回显图片尺寸 |
| **视频提交** | `VideoJobHandlerTest` | 返回 job_id + queued 状态 |
| **视频状态机** | `VideoJobStateTest` | queued → in_progress → completed |
| **视频下载** | `VideoContentHandlerTest` | 二进制 MP4 返回 |
| 故障注入 | `FaultInjectorTest` | 30% 比例,forceNextN |
| **QPS 限流** | `RateLimiterQpsTest` | 100 并发,QPS=50 → 后续 429 + `Retry-After` |
| **Token 限流** | `RateLimiterTokensTest` | 大 prompt 触发 TPS 耗尽 → 429 `limit_type=tokens` |
| **限流突发** | `RateLimiterBurstTest` | 突发后等待桶恢复 |
| 统计 | `StatsCollectorTest` | 计数正确,p50/p99 计算 (按模态分桶) |
| 端到端 | `MockEndToEndIT` | 模拟 relay-service 调用流程 (含多模态) |
| OpenAPI | `OpenApiDocIT` | /v3/api-docs 返回合法 OpenAPI 3 (含 audio/image/video 分组) |

---

## 15. 验收标准

- [ ] `mvn -pl onetoken4j-mock verify` 通过,JaCoCo 100% line coverage
- [ ] `java -jar onetoken4j-mock.jar` 启动成功,无外部依赖
- [ ] `curl http://localhost:9999/openai/v1/chat/completions` 返回 mock 响应
- [ ] 6 个协议都能正常响应 (openai/anthropic/gemini/azure/bedrock/ollama)
- [ ] 流式响应正确 (`stream:true`)
- [ ] 工具调用响应正确 (`tools` 字段触发)
- [ ] Embeddings 返回固定维度向量
- [ ] **TTS: `POST /openai/v1/audio/speech` 返回二进制 MP3,Content-Type=audio/mpeg**
- [ ] **STT: `POST /openai/v1/audio/transcriptions` multipart 接收,返回 JSON**
- [ ] **图片生成: `POST /openai/v1/images/generations` 返回 url 或 b64_json**
- [ ] **视觉理解: chat 请求含 image_url,返回文本描述**
- [ ] **视频生成: `POST /sora/v1/videos` 返回 job_id;`GET /sora/v1/videos/{id}` 状态机正常**
- [ ] **视频下载: `GET /sora/v1/videos/{id}/content` 返回二进制 MP4**
- [ ] 故障注入可动态调整并生效
- [ ] **QPS / Tokens-per-second 限流可配置且生效,429 含 `Retry-After` 头**
- [ ] 统计接口返回真实数据
- [ ] Swagger UI 可访问,所有端点有文档,多模态分组可切换
- [ ] 接入文档完整,按步骤可把 onetoken4j channel 接入

---

## 16. 后续可扩展

- **录制回放**: 真实 LLM 响应录制成 fixture,mock 重放
- **多租户 key**: 每个租户独立 key,用于平台多租户场景测试
- **WebSocket**: 支持 Realtime API (OpenAI gpt-4o-realtime)
- **图像生成**: mock `/v1/images/generations`
- **更多协议**: Cohere / Together AI / Replicate
- **Mock 即 Code**: 用 YAML 描述 mock 场景,版本控制
