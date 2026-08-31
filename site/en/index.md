---
layout: home
title: token-mock
hero:
  name: token-mock
  text: Multi-vendor LLM API mock server
  tagline: One Spring Boot fat jar that mimics six protocols — OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama — across 12 vendors and every modality. Built for integration testing, chaos drills and demos. Zero real tokens burned.
  image:
    src: /logo.svg
    alt: token-mock
  actions:
    - theme: brand
      text: Get Started
      link: /en/guide/quickstart
    - theme: alt
      text: View on GitHub
      link: https://github.com/funcommons/token-mock
    - theme: alt
      text: API Reference
      link: /en/guide/api-reference
features:
  - icon: 🔌
    title: 6 protocols, 12 vendors
    details: OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama. The OpenAI protocol family hosts DeepSeek, Moonshot, Zhipu, Tongyi and more behind one handler — each with its own key and model list, routed by path prefix.
  - icon: 🧬
    title: Protocol-faithful responses
    details: Shapes match the real vendors — chat.completion objects, SSE chunks ending with [DONE], tool_calls, Anthropic usage blocks, 429 + Retry-After. Switch your client over without changing a line of code.
  - icon: 🎭
    title: Every modality
    details: Chat (non-streaming / SSE / tool calls), embeddings (deterministic vectors), TTS (binary MP3), STT (multipart upload), image generation (URL / b64_json) and async video generation (job polling + download).
  - icon: 🔥
    title: Fault injection
    details: Inject failure rates, fixed 5xx, forced next-N failures or extra latency per vendor at runtime — to exercise your retries, fallbacks and circuit breakers.
  - icon: 🚦
    title: Rate-limit simulation
    details: Token buckets on two dimensions (QPS + tokens-per-second) with burst capacity, returning 429 with Retry-After and X-RateLimit-Remaining headers just like the real vendors.
  - icon: 📊
    title: Observable + Swagger
    details: Per-vendor/per-model request counts, average latency, p50/p99, rate-limit and error breakdowns; springdoc-openapi Swagger UI grouped by protocol for live debugging.
  - icon: 🐳
    title: Zero external dependencies
    details: No Postgres, Redis, Kafka or GPU required. One `docker run` brings the service up; CI can spin it up and tear it down per run.
  - icon: ⚙️
    title: Fully configurable
    details: Vendors, models, latency, faults and rate limits all live in application.yml (or environment variables) — adding a "new vendor" is a few lines of YAML.
---
