# Configuration

All settings live under the `mock.*` prefix in `src/main/resources/application.yml` and can be overridden the standard Spring ways: environment variables, command-line arguments, or an external config file.

## Top-level settings

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `mock.admin-token` | string | `mock-admin-secret` | Admin API token; env var `MOCK_ADMIN_TOKEN` |
| `mock.vendors` | list | 12 built-ins | Vendor list; at least one required |
| `mock.server-port` | int | 9999 | Server port (also `SERVER_PORT` / `--server.port`) |

::: warning The mock profile
All business and admin endpoints live under `@Profile("mock")` (a guard so they don't leak when the module is embedded in a larger application). `application.yml` sets `spring.profiles.default: mock`, so it activates automatically **when no profile is explicitly set** (bare `java -jar` / `docker run`); if your deployment sets other profiles explicitly, remember to include `mock`.
:::

## Vendor settings (`mock.vendors[]`)

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `slug` | string | required | Vendor id = path prefix, e.g. `openai` → `/openai/**` |
| `protocol` | string | required | `openai` / `anthropic` / `gemini` / `azure` / `bedrock` / `ollama` |
| `key` | string | required | This vendor's API key |
| `latency-ms` | int | 0 | Base response latency in milliseconds |
| `failure-rate` | double | 0.0 | Static failure proportion (see [Fault Injection](/en/guide/fault-injection)) |
| `models` | list | - | Model list (OpenAI / Anthropic / Gemini / Bedrock / Ollama) |
| `deployments` | list | - | Deployment mappings (Azure only) |
| `rate-limit` | object | off | See [Rate Limiting](/en/guide/rate-limit) |
| `fault` | object | zeros | See [Fault Injection](/en/guide/fault-injection) |

## Model settings (`models[]`)

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `code` | string | required | Model name, e.g. `gpt-4o` |
| `modality` | string | `chat` | `chat` / `embed` / `tts` / `stt` / `image-gen` / `image-vision` / `video-gen` |
| `context` | int | 8192 | Context window (used in `/models` output) |
| `dimensions` | int | 1536 | Vector dimensions (embed models only) |

## Deployment settings (Azure only, `deployments[]`)

| Key | Type | Notes |
|-----|------|-------|
| `deployment` | string | Deployment name used in the URL `/azure/openai/deployments/{deployment}/…` |
| `model` | string | The underlying model it maps to |

## Adding a vendor

Within the OpenAI protocol family, a "new vendor" is a few lines of YAML — the protocol handler is shared:

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

## Full vendor example

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

## Overriding Spring configuration

```bash
# Environment variables
export MOCK_ADMIN_TOKEN=prod-secret
export SERVER_PORT=8080

# Command-line arguments
java -jar token-mock.jar --mock.admin-token=prod-secret --server.port=8080

# External config file
java -jar token-mock.jar --spring.config.additional-location=file:/etc/token-mock/application.yml

# Docker
docker run -d -p 9999:9999 \
  -e MOCK_ADMIN_TOKEN=my-secret \
  -v $(pwd)/application.yml:/app/application.yml \
  ghcr.io/funcommons/token-mock:latest \
  --spring.config.additional-location=file:/app/application.yml
```

::: warning State resets on restart
Counters, runtime fault/rate-limit adjustments and video job state are all in-memory — **a restart returns everything to the static values in the config file**. Script your `POST /admin/**` adjustments if a load scenario needs to be reproducible.
:::
