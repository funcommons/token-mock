# 配置参考

全部配置在 `mock.*` 前缀下(`src/main/resources/application.yml`),支持 Spring 标准的覆盖方式:环境变量、命令行参数、外置配置文件。

## 顶层配置

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `mock.admin-token` | string | `mock-admin-secret` | 管理接口令牌;环境变量 `MOCK_ADMIN_TOKEN` |
| `mock.vendors` | list | 内置 12 家 | 厂商清单,至少 1 家 |
| `mock.server-port` | int | 9999 | 服务端口(也可用 `SERVER_PORT` / `--server.port`) |

::: warning mock profile
所有业务与管理端点都挂在 `@Profile("mock")` 下(嵌入单体应用时防止端点泄漏)。`application.yml` 已设置 `spring.profiles.default: mock` —— **未显式设置任何 profile 时**(裸 `java -jar` / `docker run`)自动激活;若你的部署显式指定了其它 profile,请记得额外带上 `mock`。
:::

## 厂商配置 (`mock.vendors[]`)

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `slug` | string | 必填 | 厂商标识 = 路径前缀,如 `openai` → `/openai/**` |
| `protocol` | string | 必填 | `openai` / `anthropic` / `gemini` / `azure` / `bedrock` / `ollama` |
| `key` | string | 必填 | 该厂商的 API key |
| `latency-ms` | int | 0 | 基础响应延迟(毫秒),模拟厂商耗时 |
| `failure-rate` | double | 0.0 | 静态故障比例(见[故障注入](/guide/fault-injection)) |
| `models` | list | - | 模型清单(OpenAI / Anthropic / Gemini / Bedrock / Ollama 用) |
| `deployments` | list | - | deployment 映射(仅 Azure 用) |
| `rate-limit` | object | 关闭 | 见[限流](/guide/rate-limit) |
| `fault` | object | 全零 | 见[故障注入](/guide/fault-injection) |

## 模型配置 (`models[]`)

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `code` | string | 必填 | 模型名,如 `gpt-4o` |
| `modality` | string | `chat` | `chat` / `embed` / `tts` / `stt` / `image-gen` / `image-vision` / `video-gen` |
| `context` | int | 8192 | 上下文窗口(仅用于 /models 展示) |
| `dimensions` | int | 1536 | 向量维度(仅 `embed` 模型) |

## Deployment 配置 (仅 Azure,`deployments[]`)

| 配置项 | 类型 | 说明 |
|-------|------|------|
| `deployment` | string | deployment 名,出现在 URL 里 `/azure/openai/deployments/{deployment}/…` |
| `model` | string | 映射到的真实模型名 |

## 新增一家厂商

OpenAI 协议族下新增"厂商"只需几行 YAML —— 协议处理器是共享的:

```yaml
mock:
  vendors:
    - slug: my-llm            # → http://localhost:9999/my-llm/v1/chat/completions
      protocol: openai
      key: "sk-mock-my-llm-001"
      latency-ms: 150
      models:
        - code: my-llm-pro
          modality: chat
        - code: my-llm-embed
          modality: embed
          dimensions: 1024
```

```bash
curl http://localhost:9999/my-llm/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-my-llm-001" \
  -H "Content-Type: application/json" \
  -d '{"model":"my-llm-pro","messages":[{"role":"user","content":"hi"}]}'
```

## 完整厂商示例

```yaml
mock:
  admin-token: ${MOCK_ADMIN_TOKEN:mock-admin-secret}
  vendors:
    - slug: openai
      protocol: openai
      key: "sk-mock-openai-1234567890abcdef"
      latency-ms: 200
      rate-limit:
        enabled: true
        qps: 50
        burst-requests: 20
        tokens-per-second: 200000
        burst-tokens: 100000
      fault:
        failure-rate: 0.0
        status-code: 500
        force-next-n-failures: 0
        extra-latency-ms: 0
      models:
        - code: gpt-4o
          modality: chat
          context: 128000
        - code: text-embedding-3-small
          modality: embed
          dimensions: 1536

    - slug: azure
      protocol: azure
      key: "mock-azure-key-1234567890abcdef"
      latency-ms: 300
      deployments:
        - deployment: gpt-4o-deployment
          model: gpt-4o
```

## Spring 配置覆盖方式

```bash
# 环境变量
export MOCK_ADMIN_TOKEN=prod-secret
export SERVER_PORT=8080

# 命令行参数
java -jar token-mock.jar --mock.admin-token=prod-secret --server.port=8080

# 外置配置文件
java -jar token-mock.jar --spring.config.additional-location=file:/etc/token-mock/application.yml

# Docker
docker run -d -p 9999:9999 \
  -e MOCK_ADMIN_TOKEN=my-secret \
  -v $(pwd)/application.yml:/app/application.yml \
  ghcr.io/funcommons/token-mock:latest \
  --spring.config.additional-location=file:/app/application.yml
```

::: warning 重启即清空
统计计数、运行时故障/限流调整、视频任务状态全部保存在内存,**重启后恢复为配置文件里的静态值**。需要复现的压测场景建议把 `POST /admin/**` 的调整脚本化。
:::
