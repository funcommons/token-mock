# 更新日志

## v1.2.0 (2026-08-31)

Files API — 上传 / 列表 / 详情 / 内容下载 / 删除,OpenAI 与 Anthropic 双协议共用一个内存表(用 `file-` 与 `file_` 前缀做命名空间隔离):

- OpenAI `POST /v1/files` 等 5 端点 — `file-xxx` id,OpenAI 响应字段(`object/bytes/purpose/created_at` 等)
- Anthropic `POST /v1/files` 等 5 端点 — `file_xxx` id,Anthropic 响应字段(`type/filename/mime_type/size_bytes/downloadable` 等)
- 后端:`InMemoryFileStore`(`registry` 包,线程安全,`ConcurrentHashMap`),
  两个 namespace 同 store 不同前缀 — 测试可断言 namespace 隔离
- dispatch controller 增加 GET/DELETE 路由分支 + multipart body 抽出支持

## v1.1.0 (2026-08-31)

新增端点(集成测试覆盖更高频的厂商自带能力):

- **Anthropic `POST /v1/messages/count_tokens`** — Claude SDK 启动 / 长上下文限流校验常用;返回 `{input_tokens:N}`
- **Google Gemini `:countTokens` + `:embedContent` + `:batchEmbedContents`** — 768 维确定性向量,补齐 RAG / 离线批 embedding 场景
- **Azure OpenAI `/openai/deployments/{dep}/embeddings?api-version=...`** — Azure 上 RAG 集成测试必备,shape 与 OpenAI 一致便于 SDK 透明切换

## v1.0.0 (2026-08-31)

首发版本。

- **6 种协议**:OpenAI / Anthropic / Gemini / Azure OpenAI / AWS Bedrock / Ollama,单端口按路径前缀路由
- **12 家内置厂商**:OpenAI 协议族下 OpenAI、DeepSeek、Moonshot、Zhipu、Tongyi、Minimax、Mistral,每家独立 key 与模型清单
- **全模态**:文本(非流式 / SSE / 工具调用)、Embeddings(确定性向量)、TTS(MP3)、STT(multipart)、图片生成(URL / b64_json)、图片理解、视频异步生成(任务轮询 + 下载)
- **故障注入**:failureRate / forceNextNFailures / extraLatencyMs / statusCode,运行时动态调整
- **限流仿真**:QPS + Tokens-per-second 双维度令牌桶,burst 突发,429 + Retry-After
- **管理 API**:`/admin/**` 厂商清单 / 统计(含 p50 / p99)/ 故障 / 限流,`X-Mock-Admin-Token` 保护
- **OpenAPI**:springdoc-openapi,Swagger UI 按协议分组
- **发布形态**:Spring Boot fat jar + Docker 镜像(`ghcr.io/funcommons/token-mock`)
