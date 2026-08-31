# Multimodal

Beyond chat, the mock covers audio (TTS / STT), image (generation / vision) and video (async generation). Endpoints follow the vendors' real protocols; the **binary payloads are placeholder files** — what's under test is the protocol and the pipeline, not the content.

| Modality | `modality` value | Notes |
|----------|------------------|-------|
| Chat | `chat` | non-streaming / SSE / tool calls |
| Embeddings | `embed` | deterministic vectors (same input → same output) |
| Text-to-speech | `tts` | returns an 8KB silent MP3 placeholder |
| Speech-to-text | `stt` | multipart upload, echoes byte count and language |
| Image generation | `image-gen` | URL or b64_json placeholder PNG |
| Image understanding | `image-vision` | vision-model requests echo a description |
| Video generation | `video-gen` | async job: submit → poll → download MP4 |

## TTS — text to speech

```bash
curl http://localhost:9999/openai/v1/audio/speech \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"tts-1","input":"hello","voice":"alloy"}' \
  --output speech.mp3

# Returns: an 8KB silent MP3 placeholder, Content-Type: audio/mpeg
```

## STT — speech to text

```bash
curl http://localhost:9999/openai/v1/audio/transcriptions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -F "file=@audio.mp3" \
  -F "model=whisper-1" \
  -F "language=en"

# Returns: {"text":"[mock] received a 48123-byte audio, language en", ...}
```

## Image generation

```bash
# URL mode — returns a placeholder image URL served by the mock
curl http://localhost:9999/openai/v1/images/generations \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"dall-e-3","prompt":"a cat","n":2}'

# Returns: {"data":[{"url":"http://localhost:9999/mock-files/xxx.png",
#                 "revised_prompt":"[mock] a cat"}, ...]}

# b64_json mode
curl http://localhost:9999/openai/v1/images/generations \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"dall-e-3","prompt":"a cat","response_format":"b64_json"}'
```

Generated URLs point at the mock's own `/mock-files/**` static endpoint and can be downloaded with a plain `GET` — so the two-step "get URL, then download" flow is fully testable.

## Image understanding (vision)

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{
    "model":"gpt-4o",
    "messages":[{"role":"user","content":[
      {"type":"text","text":"what is in this image?"},
      {"type":"image_url","image_url":{"url":"https://example.com/cat.png"}}
    ]}]
  }'

# Returns text: [mock] detected 1 image (image_url), placeholder description...
```

## Video generation (async jobs)

Video uses the **job model**: submit → (wait or force-complete) → poll status → download.

```bash
AUTH="Authorization: Bearer sk-mock-openai-1234567890abcdef"

# 1. Submit a job
JOB=$(curl -s http://localhost:9999/openai/v1/videos \
  -H "$AUTH" \
  -d '{"model":"sora-2","prompt":"a cat playing"}' | jq -r .id)

# 2. Poll status (queued → in_progress → completed)
curl http://localhost:9999/openai/v1/videos/$JOB -H "$AUTH"

# 3. Download (available once completed)
curl http://localhost:9999/openai/v1/videos/$JOB/content -H "$AUTH" --output video.mp4
```

Don't want to wait out the real countdown? Speed things up via the admin API or simply poll a few more times — the `queued → in_progress → completed` state machine mirrors the real vendors, which is exactly what polling logic needs (see [Fault Injection](/en/guide/fault-injection)).

## Modalities and vendor configuration

Modalities are declared per model via `mock.vendors[].models[].modality`; one vendor can mix modalities:

```yaml
mock:
  vendors:
    - slug: openai
      protocol: openai
      key: "sk-mock-openai-1234567890abcdef"
      models:
        - { code: gpt-4o,  modality: chat }
        - { code: tts-1,   modality: tts }
        - { code: whisper-1, modality: stt }
        - { code: dall-e-3,  modality: image-gen }
        - { code: sora-2,    modality: video-gen }
```

The model + endpoint combination routes to the matching modality handler; a model without that modality returns 404 / 400.
