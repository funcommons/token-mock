# token-mock

> 多厂商 LLM API Mock 服务 — 一个 Spring Boot fat jar 模拟 OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama 六大协议、12 家厂商、文本/语音/图片/视频全模态,专为**集成测试、故障演练、Demo 演示**而生。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![Release](https://img.shields.io/github/v/release/funcommons/token-mock)](https://github.com/funcommons/token-mock/releases)
[![Docs](https://img.shields.io/badge/Docs-%E5%9C%A8%E7%BA%BF%E6%96%87%E6%A1%A3-blue.svg)](https://funcommons.github.io/token-mock/)
[![Docker](https://img.shields.io/badge/ghcr.io-funcommons%2Ftoken--mock-2496ED.svg)](https://github.com/funcommons/token-mock/pkgs/container/token-mock)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](./LICENSE)

## 为什么需要 token-mock

给 LLM 应用写集成测试,要么烧真金白银调真实 API,要么自己手写一堆 if-else 假实现。token-mock 把「假 LLM」做成**独立服务**:

- **协议级仿真** — 返回结构与真实厂商一致(chat.completion 对象、SSE 分块 + `[DONE]`、`tool_calls`、`Retry-After` 头),上游代码**不改一行**即可切换
- **零外部依赖** — 不需要 PG / Redis / Kafka,不需要 GPU,`docker run` 一条命令起服务
- **故障演练** — 运行时注入失败率、固定 5xx、强制接下来 N 次必失败、额外延迟,测你的重试/降级/熔断逻辑
- **限流仿真** — 令牌桶 QPS + Tokens/s 双维度限流,429 行为与真实厂商对齐
- **全链路可观测** — 内置请求计数、延迟分位 (p50/p99)、按模型统计,Swagger UI 在线调试

## 功能总览

| 能力 | 说明 |
|---|---|
| **6 种协议** | OpenAI / Anthropic / Gemini / Azure OpenAI / AWS Bedrock / Ollama |
| **12 家厂商** | OpenAI 协议族下同时挂 OpenAI、DeepSeek、Moonshot、Zhipu、Tongyi、Minimax、Mistral…每家独立 key 与模型清单 |
| **6 种模态** | 文本对话 / Embeddings / TTS / STT / 图片生成与理解 / 视频异步生成 |
| **流式** | SSE 分块推送 + `[DONE]` 终止符,chunk 节奏按 token 数模拟打字延迟 |
| **工具调用** | 请求带 `tools` 时返回结构化 `tool_calls`,`finish_reason: tool_calls` |
| **确定性 Embedding** | 相同输入 → 相同向量,适合做缓存/相似度断言 |
| **故障注入** | failureRate / forceNextNFailures / extraLatencyMs / statusCode,运行时动态调整 |
| **限流** | QPS + Tokens-per-second 令牌桶,burst 突发容量,429 + `Retry-After` |
| **管理 API** | `/admin/**` 查厂商/统计/改故障/改限流,`X-Mock-Admin-Token` 保护 |
| **OpenAPI** | springdoc-openapi,Swagger UI 按协议分组,在线 curl 调试 |

## 快速开始

### 方式一:Docker(推荐)

```bash
docker run -d --name token-mock -p 9999:9999 ghcr.io/funcommons/token-mock:latest

# 验证
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}'
```

### 方式二:源码运行

要求 JDK 21+。

```bash
git clone https://github.com/funcommons/token-mock.git
cd token-mock
mvn spring-boot:run
```

### 方式三:Release jar

从 [Releases](https://github.com/funcommons/token-mock/releases) 下载 `token-mock.jar`:

```bash
java -jar token-mock.jar
```

启动后:

- Swagger UI:<http://localhost:9999/swagger-ui.html>(顶部下拉切换协议分组)
- Health:<http://localhost:9999/actuator/health>

## 支持的协议与端点

单端口 **9999**,按路径前缀路由协议与厂商:

| 协议 | 端点示例 | 说明 |
|------|---------|------|
| OpenAI | `POST /{slug}/v1/chat/completions` | 非流式 / SSE / tool_calls,`{slug}` = openai、deepseek、moonshot… |
| OpenAI | `POST /{slug}/v1/embeddings` | 确定性向量(相同输入 → 相同输出) |
| OpenAI | `POST /{slug}/v1/audio/speech` | TTS,返回 MP3 占位音频 |
| OpenAI | `POST /{slug}/v1/audio/transcriptions` | STT,multipart 上传 |
| OpenAI | `POST /{slug}/v1/images/generations` | 文生图,URL 或 b64_json |
| OpenAI | `POST /{slug}/v1/videos` | 视频异步任务 + 状态轮询 + MP4 下载 |
| Anthropic | `POST /anthropic/v1/messages` | Messages API,支持 stream |
| Gemini | `POST /gemini/v1/models/{model}:generateContent` | 非流式 + `:streamGenerateContent` |
| Azure | `POST /azure/openai/deployments/{dep}/chat/completions` | deployment 风格 URL |
| Bedrock | `POST /bedrock/model/{vendor}/{model}/invoke` | 简化 SigV4(跳过签名校验) |
| Ollama | `POST /ollama/api/chat` | Ollama 格式 |

默认厂商清单见 [配置参考](https://funcommons.github.io/token-mock/guide/configuration),可在 `application.yml` 的 `mock.vendors` 下增删。

## 故障注入与限流

```bash
# OpenAI 渠道 30% 概率返回 500
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"failureRate":0.3,"statusCode":500}'

# 强制接下来 5 次必失败(测重试逻辑)
curl -X POST http://localhost:9999/admin/vendors/openai/faults \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"forceNextNFailures":5}'

# 启用 QPS=5 限流,超限返回 429 + Retry-After
curl -X POST http://localhost:9999/admin/vendors/openai/rate-limit \
  -H "X-Mock-Admin-Token: mock-admin-secret" \
  -d '{"enabled":true,"qps":5,"burstRequests":5}'

# 查看统计(请求计数 / p50 / p99 / 限流次数)
curl http://localhost:9999/admin/vendors/openai/stats \
  -H "X-Mock-Admin-Token: mock-admin-secret"
```

## 文档

**📖 [在线文档站](https://funcommons.github.io/token-mock/)**(中英双语,由 GitHub Pages 部署)

| 章节 | 说明 |
|---|---|
| [概述](https://funcommons.github.io/token-mock/guide/overview) | 定位、目标与非目标、整体架构 |
| [快速开始](https://funcommons.github.io/token-mock/guide/quickstart) | Docker / jar / 源码三种启动方式 |
| [协议与厂商路由](https://funcommons.github.io/token-mock/guide/protocols) | 路径前缀路由规则、12 家厂商清单 |
| [API 参考](https://funcommons.github.io/token-mock/guide/api-reference) | 全部端点 + 请求/响应示例 |
| [多模态](https://funcommons.github.io/token-mock/guide/modalities) | TTS / STT / 图片 / 视频 |
| [故障注入](https://funcommons.github.io/token-mock/guide/fault-injection) | 故障类型与演练场景 |
| [限流](https://funcommons.github.io/token-mock/guide/rate-limit) | QPS / Tokens/s 令牌桶 |
| [管理与统计](https://funcommons.github.io/token-mock/guide/admin-api) | `/admin/**` 接口一览 |
| [配置参考](https://funcommons.github.io/token-mock/guide/configuration) | `mock.*` 全部配置项 |
| [架构与设计](https://funcommons.github.io/token-mock/guide/design) | 内部设计与关键取舍 |

仓库内文档:[docs/mock-service-design.md](./docs/mock-service-design.md)(详细设计)、[docs/mock-service-guide.md](./docs/mock-service-guide.md)(接入指南)。

## 平台集成

[onetoken4j](https://github.com/funcommons) 等网关/中转平台把渠道 `baseUrl` 指向 mock 即可接入:

```json
{
  "name": "Mock OpenAI",
  "type": "openai",
  "baseUrl": "http://localhost:9999/openai",
  "apiKey": "sk-mock-openai-1234567890abcdef",
  "supportedModelsCsv": "gpt-4o,gpt-4o-mini"
}
```

任何兼容 OpenAI 协议的客户端(LLangChain、Spring AI、openai-python…)把 `base_url` 指到 `http://localhost:9999/openai/v1` 同样直接可用。

## 构建

```bash
mvn test          # 单元测试
mvn verify        # 含 JaCoCo 覆盖率检查
mvn -DskipTests package        # 产出 target/token-mock.jar (Spring Boot fat jar)

docker build -t token-mock .   # 本地构建镜像(先 package,镜像直接 COPY jar)
```

## License

[Apache License 2.0](./LICENSE)
