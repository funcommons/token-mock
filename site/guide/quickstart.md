# 快速开始

三种启动方式任选其一,启动后全部端点在 `http://localhost:9999`。

## 方式一:Docker(推荐)

```bash
docker run -d --name token-mock -p 9999:9999 ghcr.io/funcommons/token-mock:latest
```

自定义管理 token 与端口:

```bash
docker run -d --name token-mock -p 8080:8080 \
  -e MOCK_ADMIN_TOKEN=my-secret \
  -e SERVER_PORT=8080 \
  ghcr.io/funcommons/token-mock:latest
```

## 方式二:Release jar

从 [GitHub Releases](https://github.com/funcommons/token-mock/releases) 下载 `token-mock.jar`,要求 JRE 21+:

```bash
java -jar token-mock.jar
```

## 方式三:源码运行

要求 JDK 21+ 与 Maven:

```bash
git clone https://github.com/funcommons/token-mock.git
cd token-mock
mvn spring-boot:run
```

## 验证

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}'
```

返回结构与 OpenAI 一致:

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

## 打开 Swagger UI

启动后访问 <http://localhost:9999/swagger-ui.html>:

- 顶部下拉切换协议分组:`openai` / `anthropic` / `gemini` / `azure` / `bedrock` / `ollama` / `admin`
- OpenAPI JSON:<http://localhost:9999/v3/api-docs>

## 接入你的应用

任何 OpenAI 兼容客户端把 `base_url` 指向 mock 即可,以 openai-python 为例:

```python
from openai import OpenAI

client = OpenAI(
    api_key="sk-mock-openai-1234567890abcdef",
    base_url="http://localhost:9999/openai/v1",
)
resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "你好"}],
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

::: tip 默认厂商 key
所有默认厂商的 key 都是公开的 mock key(见[协议与厂商路由](/guide/protocols)),仅用于本地 / CI 环境。**不要**把真实 key 配进 mock,也不要把 mock 暴露到公网。
:::
