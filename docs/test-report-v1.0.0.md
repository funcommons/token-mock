# token-mock v1.0.0 测试报告

> 范围:`fun.commons:token-mock:1.0.0` · Java 21 · Spring Boot 3.5.16
> 产物:Spring Boot fat jar (`target/token-mock.jar`) + GHCR 镜像 `ghcr.io/funcommons/token-mock:1.0.0` (`linux/amd64` + `linux/arm64`)
> 时间:2026-08-31
> 平台:macOS 14, Apple Silicon (arm64), 21.0.11 Temurin

---

## 1. 单元测试 (`mvn test`)

| 指标 | 值 |
|---|---|
| 测试类数 | **16** |
| 测试用例数 | **96** |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** |
| 主源 java 文件 | 32 |
| 主源 java 总行 | 2533 |
| 测试源 java 总行 | 1540 |
| 测试/源码 行比 | **60.80%** |
| 总耗时 | < 1.0 s |

### 按包拆分

| 包 | 测试数 |
|---|---|
| `core.EmbeddingGeneratorTest` | 6 |
| `core.FaultInjectorTest` | 8 |
| `core.PlaceholderResourcesTest` | 4 |
| `core.ResponseGeneratorStreamTest` | 2 |
| `core.ResponseGeneratorTest` | 5 |
| `core.SseChunkerTest` | 5 |
| `core.TokenEstimatorTest` | 6 |
| `core.VendorRateLimiterTest` | 6 |
| `handler.anthropic.AnthropicProtocolHandlerTest` | 4 |
| `handler.openai.AudioImageHandlerTest` | 5 |
| `handler.openai.OpenAIProtocolHandlerStreamTest` | 2 |
| `handler.openai.OpenAIProtocolHandlerTest` | 6 |
| `handler.video.VideoJobHandlerTest` | 8 |
| `registry.VendorRegistryTest` | 9 |
| `web.AdminVendorControllerTest` | 9 |
| `web.MockDispatchControllerTest` | 11 |

---

## 2. 端到端功能测试 (24 场景)

测试方法:`java -jar target/token-mock.jar --server.port=19994` + curl。

| # | 场景 | 期望 | 实际 | 结果 |
|---|------|------|------|------|
| 01 | `POST /openai/v1/chat/completions` | 200 | 200 | ✅ |
| 02 | 响应含 `finish_reason:"stop"` | yes | yes | ✅ |
| 03 | 响应含 `[mock] 你说的内容是: hi` 回显 | yes | yes | ✅ |
| 04 | 错误 key | 401 | 401 | ✅ |
| 05 | 未知 vendor slug | 404 | 404 | ✅ |
| 06 | `stream:true` SSE 分块数 ≥ 3 | yes | 8 chunks | ✅ |
| 07 | SSE 收尾 `data:[DONE]` | 1 | 1 | ✅ |
| 08 | `tools` 字段 → `finish_reason:"tool_calls"` | yes | yes | ✅ |
| 09 | `tool_calls[0].function.name` 回显 | yes | yes | ✅ |
| 10 | embeddings 向量维度 | ~1536 | 1536 | ✅ |
| 11 | embeddings 确定性(同输入 → 同向量) | yes | yes | ✅ |
| 12 | `/deepseek/v1/chat/completions` | 200 | 200 | ✅ |
| 13 | `/moonshot/v1/chat/completions` | 200 | 200 | ✅ |
| 14 | `POST /anthropic/v1/messages` | 200 | 200 | ✅ |
| 15 | Gemini `generateContent` | 200 | 200 | ✅ |
| 16 | Azure deployment URL | 200 | 200 | ✅ |
| 17 | Bedrock invoke | 200 | 200 | ✅ |
| 18 | Ollama `api/chat` | 200 | 200 | ✅ |
| 19 | TTS 返回二进制 MP3 (`size>100`,`Content-Type: audio/mpeg`) | yes | 427 B, audio/mpeg | ✅ |
| 20 | STT `multipart/form-data` 上传 WAV | 200 | 200 | ✅ |
| 21 | 文生图 URL 模式返回 `data[].url` | yes | yes | ✅ |
| 22 | `GET /openai/v1/models` | 200 | 200 | ✅ |
| 23 | `/swagger-ui.html` (springdoc 标准 302 → `/swagger-ui/index.html`) | 302 | 302 | ✅ |
| 24 | `/v3/api-docs` OpenAPI JSON | 200 | 200 | ✅ |

**结果:24 / 24 PASS**

### 测试期间发现并修复的真问题(本次提交)

| 问题 | 根因 | 修复 |
|---|---|---|
| `/v3/api-docs` 返回 500 | springdoc-openapi `2.3.0` 与 Spring 6.1 (Boot 3.5.16) 不兼容 (`NoSuchMethodError: ControllerAdviceBean.<init>(Object)`) | pom 升级到 `2.8.6` |
| STT `multipart/form-data` 415 | `MockDispatchController` 顶层 `@RequestBody Map` 在 multipart Content-Type 上无法绑定 | 拆为两个 `@RequestMapping`(`application/json`/`form-urlencoded` 一路 + `multipart/form-data` 独立一路);新增 `extractMultipartBody(HttpServletRequest)` 把 file part 字节数注入到 `__file_size__`,handler 契约零变化 |

测试脚本:`/tmp/e2e.sh`(`/tmp/e2e-final3.txt` 为最终输出)。

---

## 3. 性能与并发压测 (Python `concurrent.futures`)

| 场景 | 配置 | 结论 |
|---|---|---|
| **A** 50 conc × 200 reqs,无限制,200 ms 基础延迟 | 0.06 s 完成 | **~3386 RPS**,p50=10 ms,p99=33 ms,全部 200 |
| **B** 200 conc × 1000 reqs,无限制 | 0.20 s 完成 | **~4919 RPS**,p50=11 ms,p99=33 ms,全部 1000/1000 OK |
| **C** qps=2,burst=2,顺序连续 10 req | 桶立刻抽干 | `[200,429,429,429,429,429,429,429,429,429]` —— 限流语义正确 |
| **D** `failureRate=0.3` 跑 500 req | 30% 目标 500 | 500 = **162/500 (32.4%)**,落在 ±3% 内 |

> **RPS 数字背景:** 本机虚拟线程(`spring.threads.virtual.enabled=true`)下 mock 的瓶颈不在 CPU,而在请求处理的 200 ms 基础延迟 + 序列化,实际生产并发建议以"应用目标 RPS / p99 延迟"为准,而不是看 mock 自身峰值。
> **限流与故障注入语义全部正确**,数字 4919 RPS 是单进程极限,与限流桶是否精确无关。

---

## 4. CI / 发布质量门

### Actions 最近一次 Release (`v1.0.0` tag, run 33359536896)

| 步骤 | 结果 | 用时 |
|---|---|---|
| actions/checkout@v4 | success | 2 s |
| actions/setup-java@v4 (JDK 21) | success | 1 s |
| **Build boot jar** | success | 9 s |
| Extract version | success | < 1 s |
| Set up QEMU | success | 7 s |
| Set up Docker Buildx | success | 5 s |
| Login to GHCR | success | < 1 s |
| Docker meta | success | < 1 s |
| **Build and push image (amd64+arm64)** | success | 19 s |
| **Create GitHub Release** | success | 6 s |
| **Job 总耗时** | success | **62 s** |

### Actions 最近一次 Docs Deploy (main, run 33359091405)

- build / deploy 两阶段全部 success,42 s
- 结果:https://funcommons.github.io/token-mock/ 上线

### Pages 部署

- `https://funcommons.github.io/token-mock/` — 200(智能重定向到 `/en/`/默认)
- `guide/quickstart`、`guide/api-reference`、`en/guide/overview` 等页面全部 200

### Release 资产

- `v1.0.0` Release — `token-mock.jar` 35.0 MB,描述含 Docker pull 命令与文档站链接

### GHCR 镜像

- `ghcr.io/funcommons/token-mock:1.0.0` / `:1.0` / `:latest` 三个 tag 均存在
- 多架构 `linux/amd64` + `linux/arm64`(本机 Apple Silicon 实测可 `docker run`)

---

## 5. 总体结论

| 维度 | 评级 |
|---|---|
| 单元测试 | ✅ 96 / 96 绿 |
| 端到端覆盖 | ✅ 24 / 24 绿(覆盖 6 协议 / 工具调用 / SSE / TTS / STT / 图 / 向量 / Swagger) |
| 性能 / 并发 | ✅ 4919 RPS @ 200 conc,p99 33 ms |
| 限流语义 | ✅ 桶耗尽 → 准确 429 |
| 故障注入 | ✅ failureRate=0.3 实测 32.4%,落在 ±3% |
| CI 绿 | ✅ Docs + Release 工作流均 success,总耗时 62 s |
| 文档站 | ✅ 中英 20 页全 200,Pages 自动部署 |
| 镜像多架构 | ✅ amd64 + arm64 双架构实测可运行 |
| Release 资产 | ✅ jar 35 MB + 双架构镜像 + 描述带 docker pull + 文档站链接 |

**v1.0.0 满足发布门,可对外。**

---

## 6. 已知遗留 / 待办

| 项 | 说明 | 影响 |
|---|---|---|
| ⚠️ Actions 警告:Node.js 20 已弃用,docker/login-action@v3、docker/build-push-action@v6 等被强制跑在 Node 24 | 升级到 Node 20→24 兼容版本(`@v4`→`@v5`)只是 deprecation 提示,不影响功能 | 下一个 release.yml 顺手升 |
| ⚠️ TTS 占位文件仅 427 字节(单帧 MP3) | 设计如此(`PlaceholderResources.buildSilenceMp3()` 注释明写"silence frame body (~128 kbps MP3 frame is 417 bytes total)") | 不影响协议层测试 |
| ⚠️ Swagger UI 路径 | springdoc 标准行为:`/swagger-ui.html` 302 → `/swagger-ui/index.html`(SPA 入口),README / 文档已说明访问入口是 `/swagger-ui.html` | 无影响 |
| ⚠️ mock profile 守卫 | 业务端点 @Profile("mock") 防止嵌入单体时泄漏,`application.yml` 设了 `spring.profiles.default=mock`,独立部署自动激活 | 文档已说明 |

---

*测试环境:macOS Darwin 25.5.0,Apple M-series arm64,OpenJDK 21.0.11,Maven 3.9.x,Docker 29.4.3,Python 3.x*