# 多模态

除文本对话外,mock 还覆盖 声音(TTS / STT)、图片(生成 / 理解)、视频(异步生成)四类模态。所有端点都遵守对应厂商的真实协议,响应里的**二进制内容是占位文件**(校验协议与链路,而非内容)。

| 模态 | modality 值 | 说明 |
|------|------------|------|
| 文本对话 | `chat` | 非流式 / SSE / 工具调用 |
| Embeddings | `embed` | 确定性向量(相同输入 → 相同输出) |
| 文本转语音 | `tts` | 返回 8KB 静音 MP3 占位文件 |
| 语音转文本 | `stt` | multipart 上传,回显字节数与语言 |
| 图片生成 | `image-gen` | URL 或 b64_json 占位 PNG |
| 图片理解 | `image-vision` | 视觉模型请求回显描述 |
| 视频生成 | `video-gen` | 异步任务:提交 → 轮询 → 下载 MP4 |

## TTS — 文本转语音

```bash
curl http://localhost:9999/openai/v1/audio/speech \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -H "Content-Type: application/json" \
  -d '{"model":"tts-1","input":"你好","voice":"alloy"}' \
  --output speech.mp3

# 返回: 8KB 静音 MP3 占位文件,Content-Type: audio/mpeg
```

## STT — 语音转文本

```bash
curl http://localhost:9999/openai/v1/audio/transcriptions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -F "file=@audio.mp3" \
  -F "model=whisper-1" \
  -F "language=zh"

# 返回: {"text":"[mock] 识别到一段 48123 字节的音频,语言 zh", ...}
```

## 图片生成

```bash
# URL 模式 — 返回 mock 内可下载的占位图 URL
curl http://localhost:9999/openai/v1/images/generations \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"dall-e-3","prompt":"a cat","n":2}'

# 返回: {"data":[{"url":"http://localhost:9999/mock-files/xxx.png",
#                 "revised_prompt":"[mock] a cat"}, ...]}

# b64_json 模式
curl http://localhost:9999/openai/v1/images/generations \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{"model":"dall-e-3","prompt":"a cat","response_format":"b64_json"}'
```

生成的图片 URL 指向 mock 自带的 `/mock-files/**` 静态端点,可直接 `GET` 下载 —— 客户端"拿 URL 再下载"的两段式链路也能被完整测试。

## 图片理解(Vision)

```bash
curl http://localhost:9999/openai/v1/chat/completions \
  -H "Authorization: Bearer sk-mock-openai-1234567890abcdef" \
  -d '{
    "model":"gpt-4o",
    "messages":[{"role":"user","content":[
      {"type":"text","text":"这张图里有什么?"},
      {"type":"image_url","image_url":{"url":"https://example.com/cat.png"}}
    ]}]
  }'

# 返回文本: [mock] 识别到 1 张图片 (image_url),内容为占位描述...
```

## 视频生成(异步任务)

视频走**任务模型**:提交 → (等待/强制完成) → 查状态 → 下载。

```bash
AUTH="Authorization: Bearer sk-mock-openai-1234567890abcdef"

# 1. 提交任务
JOB=$(curl -s http://localhost:9999/openai/v1/videos \
  -H "$AUTH" \
  -d '{"model":"sora-2","prompt":"a cat playing"}' | jq -r .id)

# 2. 查询状态 (queued → in_progress → completed)
curl http://localhost:9999/openai/v1/videos/$JOB -H "$AUTH"

# 3. 下载视频 (completed 后可用)
curl http://localhost:9999/openai/v1/videos/$JOB/content -H "$AUTH" --output video.mp4
```

不想等真实倒计时?用管理接口把故障/任务参数调快,或者直接多查几次状态 —— 状态机 `queued → in_progress → completed` 的流转路径与真实厂商一致,适合测轮询逻辑(见[故障注入](/guide/fault-injection))。

## 多模态与厂商配置

模态由 `mock.vendors[].models[].modality` 声明,同一个厂商可以混合挂多种模态的模型:

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

请求的模型 + 端点组合会路由到对应模态的处理器;模型没配对应模态时返回 404 / 400。
