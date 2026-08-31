# 协议与厂商路由

## 路由模型

单端口 **9999**,通过 **路径前缀** 同时区分协议与厂商:第一段路径就是厂商 slug,服务按 slug 在内存注册表里查到它的协议与 key,再交给对应的协议处理器。

```
POST http://localhost:9999/deepseek/v1/chat/completions
                              └──┬───┘
                          slug = deepseek
                          → protocol = openai(共用 OpenAI 处理器)
                          → key     = sk-mock-deepseek-abcdef1234567890
```

同一协议下的多家厂商**仅 key、模型清单、延迟参数不同**,协议处理代码完全共用 —— 所以给 OpenAI 协议族新增一家"国产大模型厂商"只需要几行 YAML(见[配置参考](/guide/configuration))。

## 已内置厂商(12 家)

| 厂商 | 路径前缀 | 默认 Key | 模型清单 |
|------|---------|---------|---------|
| OpenAI | `/openai` | `sk-mock-openai-1234567890abcdef` | gpt-4o, gpt-4o-mini, text-embedding-3-small |
| DeepSeek | `/deepseek` | `sk-mock-deepseek-abcdef1234567890` | deepseek-chat, deepseek-reasoner |
| Moonshot | `/moonshot` | `sk-mock-moonshot-xyz1234567890` | moonshot-v1-8k / 32k / 128k |
| Zhipu (GLM) | `/zhipu` | `sk-mock-zhipu-glmabcdef` | glm-4-plus, glm-4-flash, glm-4-air |
| Tongyi | `/tongyi` | `sk-mock-tongyi-qwen` | qwen-max, qwen-turbo |
| Minimax | `/minimax` | `sk-mock-minimax-abcd` | abab6.5-chat |
| Mistral | `/mistral` | `sk-mock-mistral-xyz` | mistral-large-latest, mistral-embed |
| Anthropic | `/anthropic` | `sk-ant-mock-anthropic-xxx` | claude-3-5-sonnet / haiku / 3-opus |
| Gemini | `/gemini` | `AIzaSyMockGeminiKey1234567890` | gemini-1.5-pro, gemini-1.5-flash |
| Azure OpenAI | `/azure` | `mock-azure-key-1234567890abcdef` | gpt-4o-deployment, gpt-35-turbo-deployment |
| AWS Bedrock | `/bedrock` | `mock-bedrock-aws4-key` | anthropic.claude-3-5-sonnet, amazon.titan-text-express |
| Ollama | `/ollama` | `ollama`(不校验) | llama3:8b, qwen2:7b |

## 路径前缀 → 协议

| 路径前缀 | 协议 | key 校验方式 |
|---------|------|------------|
| `/openai/**` 等 OpenAI 协议族 | OpenAI Chat / Embeddings / Audio / Images / Videos | `Authorization: Bearer <key>` |
| `/anthropic/**` | Anthropic Messages | `x-api-key: <key>` |
| `/gemini/**` | Google Gemini generateContent | `Authorization: Bearer <key>` 或 `?key=` |
| `/azure/**` | Azure OpenAI(deployment 风格 URL) | `Authorization: Bearer <key>` |
| `/bedrock/**` | AWS Bedrock(简化,跳过 SigV4 校验) | `Authorization: AWS4-HMAC-SHA256 ...`(只要求非空) |
| `/ollama/**` | Ollama | 不校验 |

## 各协议调用示例

### OpenAI 协议族

```bash
# chat(以 DeepSeek 为例,其余 slug 同理)
curl http://localhost:9999/deepseek/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-deepseek-abcdef1234567890" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}]}'

# 流式(SSE)
curl -N http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"hi"}]}'

# 工具调用:请求带 tools → 返回 finish_reason=tool_calls
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{
    "model":"gpt-4o",
    "messages":[{"role":"user","content":"weather"}],
    "tools":[{"type":"function","function":{"name":"get_weather","parameters":{}}}]
  }'

# embeddings
curl http://localhost:9999/openai/v1/embeddings \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"text-embedding-3-small","input":"hello"}'

# 模型清单
curl http://localhost:9999/openai/v1/models \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef"
```

### Anthropic Messages

```bash
curl http://localhost:9999/anthropic/v1/messages \
  -H "x-api-key: sk-ant-mock-anthropic-xxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-3-5-sonnet-20241022","max_tokens":1024,
       "messages":[{"role":"user","content":"hi"}]}'
```

### Gemini generateContent

```bash
curl -X POST "http://localhost:9999/gemini/v1/models/gemini-1.5-pro:generateContent" \
  -H "Authorization: Bearer AIzaSyMockGeminiKey1234567890" \
  -d '{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}'

# 流式
curl -X POST "http://localhost:9999/gemini/v1/models/gemini-1.5-pro:streamGenerateContent" \
  -H "Authorization: Bearer AIzaSyMockGeminiKey1234567890" \
  -d '{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}'
```

### Azure OpenAI(deployment URL)

```bash
curl "http://localhost:9999/azure/openai/deployments/gpt-4o-deployment/chat/completions?api-version=2024-02-15-preview" \
  -H "Authorization: Bearer mock-azure-key-1234567890abcdef" \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
```

### AWS Bedrock(简化 SigV4)

```bash
curl http://localhost:9999/bedrock/model/anthropic.claude-3-5-sonnet/invoke \
  -H "Authorization: AWS4-HMAC-SHA256 Credential=mock-bedrock-aws4-key/..." \
  -d '{"prompt":"hi"}'
```

### Ollama

```bash
curl http://localhost:9999/ollama/api/chat \
  -d '{"model":"llama3:8b","messages":[{"role":"user","content":"hi"}]}'
```

## key 校验失败行为

- key 缺失或不匹配 → `401`,响应体为对应协议的错误格式(OpenAI 为 `error.message` 风格)
- 厂商 slug 不存在 → `404`
- 模型不在该厂商清单里 → `404` / 400(协议各自风格)
