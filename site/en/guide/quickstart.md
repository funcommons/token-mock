# Quickstart

Pick any of the three startup options below — everything is served at `http://localhost:9999`.

## Option 1: Docker (recommended)

```bash
docker run -d --name token-mock -p 9999:9999 ghcr.io/funcommons/token-mock:latest
```

Custom admin token and port:

```bash
docker run -d --name token-mock -p 8080:8080 \
  -e MOCK_ADMIN_TOKEN=my-secret \
  -e SERVER_PORT=8080 \
  ghcr.io/funcommons/token-mock:latest
```

## Option 2: Release jar

Grab `token-mock.jar` from [GitHub Releases](https://github.com/funcommons/token-mock/releases). Requires JRE 21+:

```bash
java -jar token-mock.jar
```

## Option 3: From source

Requires JDK 21+ and Maven:

```bash
git clone https://github.com/funcommons/token-mock.git
cd token-mock
mvn spring-boot:run
```

## Verify

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hello"}]}'
```

The response shape matches OpenAI's:

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

## Open the Swagger UI

Visit <http://localhost:9999/swagger-ui.html>:

- Switch protocol groups from the dropdown at the top: `openai` / `anthropic` / `gemini` / `azure` / `bedrock` / `ollama` / `admin`
- OpenAPI JSON: <http://localhost:9999/v3/api-docs>

## Point your app at it

Any OpenAI-compatible client works — just change the base URL. openai-python:

```python
from openai import OpenAI

client = OpenAI(
    api_key="sk-mock-openai-1234567890abcdef",
    base_url="http://localhost:9999/openai/v1",
)
resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "hello"}],
)
print(resp.choices[0].message.content)
```

Spring AI:

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:9999/openai
      api-key: sk-mock-openai-1234567890abcdef
```

::: tip Default vendor keys
Every built-in vendor uses a public mock key (see [Protocols & Vendor Routing](/en/guide/protocols)) — meant for local development and CI only. **Never** configure real keys into the mock, and don't expose the mock to the public internet.
:::
