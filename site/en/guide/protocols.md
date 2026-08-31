# Protocols & Vendor Routing

## Routing model

One port, **9999**; the **path prefix** selects both the protocol and the vendor: the first path segment is the vendor slug, which is looked up in an in-memory registry to find its protocol and key, then dispatched to the matching protocol handler.

```
POST http://localhost:9999/deepseek/v1/chat/completions
                              └──┬───┘
                          slug = deepseek
                          → protocol = openai (shared OpenAI handler)
                          → key      = sk-mock-deepseek-abcdef1234567890
```

Vendors on the same protocol differ **only in key, model list and latency** — the protocol handler code is shared. Adding another "vendor" to the OpenAI family is a few lines of YAML (see [Configuration](/en/guide/configuration)).

## Built-in vendors (12)

| Vendor | Path prefix | Default key | Models |
|--------|-------------|-------------|--------|
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
| Ollama | `/ollama` | `ollama` (not validated) | llama3:8b, qwen2:7b |

## Path prefix → protocol

| Path prefix | Protocol | Key check |
|-------------|----------|-----------|
| `/openai/**` and the OpenAI family | OpenAI Chat / Embeddings / Audio / Images / Videos | `Authorization: Bearer <key>` |
| `/anthropic/**` | Anthropic Messages | `x-api-key: <key>` |
| `/gemini/**` | Google Gemini generateContent | `Authorization: Bearer <key>` or `?key=` |
| `/azure/**` | Azure OpenAI (deployment-style URL) | `Authorization: Bearer <key>` |
| `/bedrock/**` | AWS Bedrock (simplified — SigV4 not verified) | `Authorization: AWS4-HMAC-SHA256 ...` (non-empty) |
| `/ollama/**` | Ollama | none |

## Calling examples

### OpenAI family

```bash
# chat (DeepSeek shown; other slugs work the same way)
curl http://localhost:9999/deepseek/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-deepseek-abcdef1234567890" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"hello"}]}'

# streaming (SSE)
curl -N http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"hi"}]}'

# tool calls: pass tools → finish_reason=tool_calls
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

# model list
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

# streaming
curl -X POST "http://localhost:9999/gemini/v1/models/gemini-1.5-pro:streamGenerateContent" \
  -H "Authorization: Bearer AIzaSyMockGeminiKey1234567890" \
  -d '{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}'
```

### Azure OpenAI (deployment URL)

```bash
curl "http://localhost:9999/azure/openai/deployments/gpt-4o-deployment/chat/completions?api-version=2024-02-15-preview" \
  -H "Authorization: Bearer mock-azure-key-1234567890abcdef" \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
```

### AWS Bedrock (simplified SigV4)

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

## Key validation failures

- Missing or wrong key → `401` with the protocol's own error shape (OpenAI-style `error.message`)
- Unknown vendor slug → `404`
- Model not in the vendor's list → `404` / `400` depending on the protocol
