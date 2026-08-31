# Overview

**token-mock** is a standalone multi-vendor LLM API mock server: a single port that mimics six protocols (OpenAI / Anthropic / Gemini / Azure OpenAI / AWS Bedrock / Ollama), with 12 built-in vendors, full-modality responses (text, audio, image, video), runtime fault injection and rate-limit simulation — all inside one Spring Boot fat jar.

## The problem it solves

Integration-testing LLM applications usually means one of two things:

1. **Calling real APIs** — expensive, slow, non-deterministic, and impossible to reproduce failures with;
2. **Hand-writing fake implementations inside your code** — invasive, and blind to protocol details (SSE chunking, tool_calls, 429 semantics…).

token-mock offers a third path: **the fake LLM is a standalone service** that speaks the vendors' real protocols. Point your client's `base_url` at it and change nothing else.

Typical uses:

- **Integration testing** — `docker run` it in CI, tear it down after; assert on protocol structure, not model output
- **Chaos drills** — inject 500s / latency / 429s to verify retries, fallbacks and circuit breakers
- **Demos** — full multi-vendor chat / audio / image / video demos with no network access
- **Load testing** — dial latency and failure rate to a target scenario via the admin API and stress your client

## Goals and non-goals

### Goals

- **Standalone**: one Spring Boot fat jar, no external dependencies (no Postgres / Redis / Kafka)
- **Multi-vendor**: 6 protocols, multiple vendors per protocol (7 on the OpenAI family), each with its own key and model list
- **Multimodal**: text / audio (TTS + STT) / image (generation + vision) / video (async generation)
- **Behavior-complete**: non-streaming / SSE streaming / tool calls / embeddings / multimodal echoes
- **Fault injection**: proportional failures, fixed status codes, forced consecutive failures, latency
- **OpenAPI docs**: browsable Swagger UI with per-protocol groups

### Non-goals

- ❌ No real inference (responses are fixed/echoed; no real model is ever called)
- ❌ No persistence (everything is in-memory; a restart resets state)
- ❌ No user management (a single admin token guards the admin API)
- ❌ No high availability (single instance, for development and testing)
- ❌ No real audio/video encoding (fixed placeholder binaries — the protocol is what's under test)

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                    token-mock (port 9999)                      │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  OpenAI      │  │ Anthropic    │  │  Gemini      │  ...     │
│  │  Protocol    │  │ Protocol     │  │  Protocol    │  (6)     │
│  │  Handler     │  │ Handler      │  │  Handler     │          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘          │
│         └────────┬────────┴────────┬────────┘                   │
│                  ▼                 ▼                            │
│         ┌──────────────────────────────────┐                    │
│         │     Vendor Registry (in-memory)  │                    │
│         │  - slug → protocol / key / models│                    │
│         │  - defined in application.yml    │                    │
│         └────────────────┬─────────────────┘                    │
│         ┌────────────────▼─────────────────┐                    │
│         │   Response Generator             │                    │
│         │  - template responses (echo)     │                    │
│         │  - token estimation (chars/4)    │                    │
│         │  - SSE chunking                  │                    │
│         └────────────────┬─────────────────┘                    │
│         ┌────────────────▼─────────────────┐                    │
│         │   Fault Injector                 │                    │
│         │  - failure rate / latency / code │                    │
│         └────────────────┬─────────────────┘                    │
│         ┌────────────────▼─────────────────┐                    │
│         │   RateLimiter (token bucket)     │                    │
│         │  - QPS / tokens-per-second       │                    │
│         │  - burst capacity                │                    │
│         │  - 429 + Retry-After             │                    │
│         └────────────────┬─────────────────┘                    │
│         ┌────────────────▼─────────────────┐                    │
│         │   Modality Router                │                    │
│         │  - chat / embed / tts / stt      │                    │
│         │  - image-gen / image-vision      │                    │
│         │  - video-gen (async)             │                    │
│         └────────────────┬─────────────────┘                    │
│         ┌────────────────▼─────────────────┐                    │
│         │   Placeholders (bundled binaries)│                    │
│         │  - silence-1s.mp3 (TTS)          │                    │
│         │  - placeholder-256x256.png       │                    │
│         │  - placeholder-5s.mp4            │                    │
│         └──────────────────────────────────┘                    │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐    │
│  │  Admin API (/admin/**, X-Mock-Admin-Token)             │    │
│  │  - GET/POST /admin/vendors/{slug}/faults               │    │
│  │  - GET/POST /admin/vendors/{slug}/rate-limit           │    │
│  │  - GET /admin/stats                                    │    │
│  └────────────────────────────────────────────────────────┘    │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐    │
│  │  springdoc-openapi (Swagger UI @ /swagger-ui.html)     │    │
│  └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
         ▲
         │ HTTP
         │
   ┌─────┴──────────────────────────────┐
   │ Callers:                            │
   │  - any OpenAI-compatible client     │
   │  - Spring AI / LangChain / etc.     │
   │  - gateways (channels point at it)  │
   └────────────────────────────────────┘
```

Request pipeline: **path prefix → vendor lookup → key check → rate limit → fault injection → modality routing → response generation**.

## Tech stack

| Item | Value |
|---|---|
| Language / target | Java 21 (virtual threads enabled) |
| Framework | Spring Boot 3.5 (web / validation / actuator starters) |
| API docs | springdoc-openapi 2.3 (Swagger UI + groups) |
| Build | Maven, artifact `token-mock.jar` (Spring Boot fat jar) |
| Default port | 9999 |
| State | In-memory only, reset on restart |
