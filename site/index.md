---
layout: home
title: token-mock
hero:
  name: token-mock
  text: 多厂商 LLM API Mock 服务
  tagline: 一个 Spring Boot fat jar,模拟 OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama 六大协议、12 家厂商、全模态响应。为集成测试、故障演练和 Demo 而生,不烧一分钱 token。
  image:
    src: /logo.svg
    alt: token-mock
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/quickstart
    - theme: alt
      text: 在 GitHub 上查看
      link: https://github.com/funcommons/token-mock
    - theme: alt
      text: API 参考
      link: /guide/api-reference
features:
  - icon: 🔌
    title: 6 种协议,12 家厂商
    details: OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama。OpenAI 协议族下同时挂 DeepSeek、Moonshot、Zhipu、Tongyi 等多家厂商,每家独立 key 与模型清单,按路径前缀路由。
  - icon: 🧬
    title: 协议级仿真
    details: 返回结构与真实厂商一致 —— chat.completion 对象、SSE 分块 + [DONE]、tool_calls、Anthropic usage 块、429 + Retry-After。上游代码不改一行即可切换。
  - icon: 🎭
    title: 全模态
    details: 文本对话(非流式/流式/工具调用)、Embeddings(确定性向量)、TTS(二进制 MP3)、STT(multipart 上传)、图片生成(URL / b64_json)、视频异步生成(任务轮询 + 下载)。
  - icon: 🔥
    title: 故障注入
    details: 运行时给任意厂商注入失败率、固定 5xx、强制接下来 N 次必失败、额外延迟 —— 专测你的重试、降级、熔断逻辑。
  - icon: 🚦
    title: 限流仿真
    details: QPS + Tokens-per-second 双维度令牌桶,burst 突发容量,429 响应带 Retry-After 与 X-RateLimit-Remaining 头,与真实厂商行为对齐。
  - icon: 📊
    title: 可观测 + Swagger
    details: 按厂商/模型统计请求数、平均延迟、p50/p99 分位、限流与错误分布;springdoc-openapi 的 Swagger UI 按协议分组,在线 curl 调试。
  - icon: 🐳
    title: 零外部依赖
    details: 不需要 PG / Redis / Kafka,不需要 GPU。docker run 一条命令起服务,CI 里随手拉起、随手销毁。
  - icon: ⚙️
    title: 全部可配置
    details: 厂商清单、模型、延迟、故障、限流全部走 application.yml(或环境变量),加一家"新厂商"只是加几行 YAML。
---
