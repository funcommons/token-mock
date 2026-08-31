# 架构与设计

本文说明 mock 的内部设计与关键取舍。完整设计文档见仓库 [docs/mock-service-design.md](https://github.com/funcommons/token-mock/blob/main/docs/mock-service-design.md)。

## 分层结构

```
fun.commons.tokenmock
├── web/        MockDispatchController  单一入口:/{slug}/** 路由分发
│               AdminVendorController    /admin/** 管理接口
├── handler/    ProtocolHandler SPI      6 个协议实现,每协议一个
│   ├── openai/    OpenAIProtocolHandler + AudioImageHandler
│   ├── anthropic/ AnthropicProtocolHandler
│   ├── gemini/    GeminiProtocolHandler
│   ├── azure/     AzureProtocolHandler
│   ├── bedrock/   BedrockProtocolHandler
│   ├── ollama/    OllamaProtocolHandler
│   └── video/     VideoJobHandler        异步视频任务状态机
├── core/       ResponseGenerator        模板响应 + token 估算 + SSE 分块
│               SseChunker               chunk 切分与节奏
│               TokenEstimator           估算 token 数(字数/4)
│               FaultInjector            失败率/强制失败/延迟
│               VendorRateLimiter        双维度令牌桶
│               EmbeddingGenerator       确定性向量
│               PlaceholderResources     内置 MP3/PNG/MP4 占位文件
├── registry/   VendorRegistry           slug → 厂商配置(内存注册表)
│               StatsCollector           请求计数/延迟分位统计
├── config/     MockProperties + VendorConfig/FaultConfig/RateLimitConfig…
└── exception/  MockExceptionHandler     统一错误 → 各协议错误格式
```

## 关键设计取舍

### 1. 单入口路由,协议处理器共享

只有一个 `MockDispatchController` 接住 `/{slug}/**`,按 slug 查 `VendorRegistry` 得到协议,再分发到对应 `ProtocolHandler`。于是 **OpenAI 协议族 7 家厂商共用同一套处理代码** —— 新增厂商只是配置问题,不是代码问题。

### 2. 响应是"模板 + 回显",不是随机文本

响应内容 = `[mock]` 前缀 + 用户输入回显。这样做的价值:

- **可断言** —— 测试能验证"发出去的参数确实被处理了"(模型名、消息内容、工具名都体现在响应里)
- **可区分** —— 一眼看出响应来自 mock,不会误当真实结果用
- **确定性** —— 相同输入恒定输出,测试可重放

### 3. SSE 分块模拟真实节奏

`SseChunker` 按估算 token 数把回显文本切成若干 chunk,逐块推送并按厂商 `latency-ms` 模拟打字间隔,最后补 `finish_reason: stop` 与 `data: [DONE]`。客户端的超时、逐帧解析、断流重连逻辑因此都能被真实覆盖。

### 4. 故障注入在限流之后、响应生成之前

请求处理链固定为:**鉴权 → 限流 → 故障注入 → 模态路由 → 响应生成**。因此可以组合出"先被限流还是先失败"这类精确场景;故障参数全部走内存态 + 管理接口,注入/复位零重启。

### 5. 双维度令牌桶限流

`VendorRateLimiter` 对 请求数(QPS)与 token 数(Tokens/s)各维护一个令牌桶,burst 即桶容量,取 token 与扣减在同一临界区内原子完成。429 响应附 `Retry-After`(按回填速率计算)与 `X-RateLimit-*` 配额头,与真实厂商对齐。被 QPS 拒与被 token 拒分开计数。

### 6. 确定性 Embedding

`EmbeddingGenerator` 对输入文本做哈希派生,相同输入恒定得到相同向量。测试里可以做"两次一致 / 不同输入不同"的断言,甚至用于验证缓存命中。

### 7. 全内存、无持久化

统计、故障参数、限流参数、视频任务状态都在内存。换来的是**零依赖、秒级启动、CI 友好**;代价是重启回到配置文件静态值 —— 对"mock 服务"这个定位是正确的取舍。

## 测试

单元测试覆盖核心组件(响应生成、SSE 分块、故障注入、限流器、各协议处理器、管理接口),`mvn verify` 附 JaCoCo 覆盖率检查。压测/演练场景的手工验收清单见 [docs/mock-service-guide.md](https://github.com/funcommons/token-mock/blob/main/docs/mock-service-guide.md) 的测试矩阵。
