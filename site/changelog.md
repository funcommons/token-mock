# 更新日志

## v1.5.0 (2026-09-04)

对齐 token-gateway 0.8.0+ OpenAI 协议任务面:

- **Sora 形状 video job** — `POST /v1/videos` 接收 `{model,prompt,seconds,size,notify_url}`,返回 `{object:"video_generation", id:"T<24hex>"}`(与 OneToken 协议 `task_no` 同形状)
- **Video /content 307 重定向** — `GET /v1/videos/{id}/content` 返回 307 Location 到签名代理 URL(mock 内指向 `/mock-files/videos/{id}.mp4`,真实网关指向 OSS / S3 预签 URL);`failed` 状态返 410 Gone
- **`failed` 状态携带 error 信封** — `{error:{code,message}}` 复用 token-gateway 0.8.0 §3.7 错误语义
- **Image background 模式** — `POST /v1/images/generations` 接收 `{background:true}` → `image_generation` 异步 job,`GET /v1/images/generations/{id}` 返回 `{object:"image_generation", status, output:[{content:[{type:"output_image",image_url:{url:"/v1/resources/{id}/{i}"}}]}]}`(OpenAI 官方 background shape)
- **`/v1/images/sync` 同步生图封装** — 三出口语义:成功 `{created,data:[{url}]}` / 502 上游失败已退款 / 60s 超时降级 `{status:"PROCESSING", task_no, poll_url}`;`n>1` 返 400(token 面单图语义)
- **`/mock-files/**` 静态资源** — `placeholder.png` / `videos/{id}.mp4` 占位资源(支持 307 跳转目标),`fallback` catch-all 返 JSON 信封

## v1.4.0 (2026-09-01)

补齐五大厂协议覆盖,token-mock 成为业内唯一完整覆盖 Anthropic + Google Gemini + Azure OpenAI + Bedrock + OpenAI 五大协议的工程级 mock server:

- **Bedrock Converse + ConverseStream** — `POST /model/{modelId}/converse` + `/converse-stream`(SSE),统一 schema,`messages[].content[].{text|image|toolUse|toolResult}` 多态块;LangChain `ChatBedrockConverse` / Strands Agents 默认走它
- **Anthropic Message Batches** — `POST/GET/cancel` + `GET .../results`(jsonl)+ `GET .../batches`(list,`?limit=`),5 端点;与 OpenAI batches 对称
- **Gemini Files API** — `POST /upload/v1beta/files`(resumable,实际收字节)+ `GET/DELETE /v1beta/files/{name}` + list;generateContent 接受 `file_data.file_uri` 引用(`state: ACTIVE`)
- **Azure OpenAI v1 API** — `/openai/v1/{responses,audio/speech,audio/transcriptions,images/generations,batches}` + cancel;与 OpenAI 同 schema,`?api-version=preview` 容错;legacy `/openai/deployments/{dep}/...` 双形态并存

## v1.3.0 (2026-08-31)

OpenAI 2025 后两个主力端点接入,Agent SDK / 离线批处理集成测试覆盖:

- **Responses API** — `POST /v1/responses` + `GET /v1/responses/{id}` + `POST /v1/responses/{id}/cancel`
  - `previous_response_id` 状态延续(支持多轮对话链)
  - `background:true` 异步任务:返回 queued,250ms 后切 completed;客户端轮询语义一致
  - 内部:`ResponseJobHandler`(`ConcurrentHashMap` + 单线程 `ScheduledExecutorService`)
- **Batches API** — `POST /v1/batches` + `GET /v1/batches/{id}` + `POST /v1/batches/{id}/cancel`
  - 状态机 `validating → in_progress → finalizing → completed`,支持 cancel 转 `cancelling`
  - 输出 `output_file_id` 占位 `file_batch_result_xxx`
  - 前置依赖 Files API(v1.2 已做),集成测试完整链路上传→批量→查询

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
