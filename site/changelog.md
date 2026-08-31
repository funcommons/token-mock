# 更新日志

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
