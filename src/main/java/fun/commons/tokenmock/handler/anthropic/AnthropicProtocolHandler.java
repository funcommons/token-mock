package fun.commons.tokenmock.handler.anthropic;

import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.SseChunker;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.ProtocolHandler;
import fun.commons.tokenmock.registry.InMemoryFileStore;
import fun.commons.tokenmock.registry.VendorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Messages API.
 * <p>
 * Endpoints (relative to /anthropic):
 *   POST /v1/messages          (non-stream)
 *   POST /v1/messages?stream=true  (SSE)
 *   POST /v1/messages/count_tokens  (input_tokens 预估算)
 *   POST /v1/files                   (multipart upload, returns file_xxx id)
 *   GET  /v1/files
 *   GET  /v1/files/{id}
 *   GET  /v1/files/{id}/content
 *   DELETE /v1/files/{id}
 *   POST /v1/messages/batches        (create msgbatch_xxx, processing_status in_progress)
 *   GET  /v1/messages/batches/{id}
 *   GET  /v1/messages/batches/{id}/results  (jsonl, one entry per request)
 *   POST /v1/messages/batches/{id}/cancel
 *   GET  /v1/messages/batches        (list, ?limit=)
 *   GET  /v1/models
 * <p>
 * Auth: x-api-key header (not Bearer).
 */
@Component
public class AnthropicProtocolHandler implements ProtocolHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VendorRegistry registry;
    private final TokenEstimator estimator;
    private final SseChunker sseChunker;
    private final InMemoryFileStore fileStore;
    private final AnthropicBatchJobHandler batchHandler;

    public AnthropicProtocolHandler(VendorRegistry registry, TokenEstimator estimator, SseChunker sseChunker,
                                     InMemoryFileStore fileStore, AnthropicBatchJobHandler batchHandler) {
        this.registry = registry;
        this.estimator = estimator;
        this.sseChunker = sseChunker;
        this.fileStore = fileStore;
        this.batchHandler = batchHandler;
    }

    @Override
    public String protocol() {
        return "anthropic";
    }

    @Override
    public Object handle(MockRequest request) {
        VendorConfig vendor = registry.require(request.getVendorSlug());
        if (!isAuthenticated(vendor, request)) {
            return ResponseEntity.status(401).body(Map.of(
                    "type", "error",
                    "error", Map.of("type", "authentication_error", "message", "invalid x-api-key")
            ));
        }

        String path = request.getPath().replaceFirst("^/[^/]+", "");
        if (path.startsWith("/v1/files")) {
            return handleFiles(path, request);
        }
        if (path.startsWith("/v1/messages/batches")) {
            return handleBatches(path, request);
        }
        return switch (path) {
            case "/v1/messages" -> handleMessages(request);
            case "/v1/messages/count_tokens" -> handleCountTokens(request);
            case "/v1/models" -> handleListModels(vendor);
            default -> throw new IllegalArgumentException("unknown path: " + path);
        };
    }

    /**
     * Anthropic Files API:同 OpenAI 形态但 id 是 {@code file_} 前缀,响应字段不同.
     * <p>Anthropic 返回字段:{@code {id, type:"file", filename, mime_type, size_bytes,
     * created_at, downloadable?}}.我们尽量贴齐.
     */
    private Object handleFiles(String path, MockRequest request) {
        if (path.equals("/v1/files")) {
            return switch (request.getMethod()) {
                case "POST" -> handleFileUpload(request);
                case "GET" -> ResponseEntity.ok(Map.of(
                        "data", fileStore.list(InMemoryFileStore.Namespace.ANTHROPIC).stream()
                                .map(f -> Map.<String, Object>of(
                                        "id", f.id(),
                                        "type", "file",
                                        "filename", f.filename(),
                                        "mime_type", f.contentType(),
                                        "size_bytes", f.size(),
                                        "created_at", f.createdAt().toString(),
                                        "downloadable", true
                                )).toList()
                ));
                default -> throw new IllegalArgumentException(
                        "unsupported method on /v1/files: " + request.getMethod());
            };
        }
        String[] parts = path.split("/");
        String id = parts.length >= 4 ? parts[3] : "";
        var maybe = fileStore.findById(id);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "type", "error", "error", Map.of("type", "not_found_error", "message", "file not found")));
        }
        var entry = maybe.get();
        if (parts.length == 5 && "content".equals(parts[4])) {
            MediaType ct = entry.contentType() != null
                    ? MediaType.parseMediaType(entry.contentType())
                    : MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok().contentType(ct).body(entry.bytes());
        }
        if (parts.length == 4) {
            return switch (request.getMethod()) {
                case "GET" -> ResponseEntity.ok(Map.<String, Object>of(
                        "id", entry.id(),
                        "type", "file",
                        "filename", entry.filename(),
                        "mime_type", entry.contentType(),
                        "size_bytes", entry.size(),
                        "created_at", entry.createdAt().toString(),
                        "downloadable", true
                ));
                case "DELETE" -> {
                    fileStore.delete(id);
                    yield ResponseEntity.ok(Map.of("id", id, "type", "file_deleted"));
                }
                default -> throw new IllegalArgumentException(
                        "unsupported method on /v1/files/{id}: " + request.getMethod());
            };
        }
        throw new IllegalArgumentException("unknown files path: " + path);
    }

    private Object handleFileUpload(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String filename = stringOr(body.get("__file_name__"), "upload.bin");
        String contentType = stringOr(body.get("__file_content_type__"), "application/octet-stream");
        Long size = body.get("__file_size__") instanceof Number n ? n.longValue() : 0L;
        byte[] placeholder = ("[mock] " + filename + " (" + size + " bytes)").getBytes();
        var entry = fileStore.save(InMemoryFileStore.Namespace.ANTHROPIC, filename, "file", contentType, placeholder);
        return ResponseEntity.ok(Map.<String, Object>of(
                "id", entry.id(),
                "type", "file",
                "filename", entry.filename(),
                "mime_type", entry.contentType(),
                "size_bytes", entry.size(),
                "created_at", entry.createdAt().toString(),
                "downloadable", true
        ));
    }

    /**
     * Anthropic Message Batches API.
     * <p>POST 创建 batch(从 requests[] 数出总数);GET 详情;GET results jsonl;
     * POST cancel;GET list. 状态机 in_progress → ended,或 in_progress → canceling.
     */
    private Object handleBatches(String path, MockRequest request) {
        int q = path.indexOf('?');
        String query = q > 0 ? path.substring(q + 1) : "";
        String cleanPath = q > 0 ? path.substring(0, q) : path;

        if (cleanPath.equals("/v1/messages/batches")) {
            return switch (request.getMethod()) {
                case "POST" -> {
                    @SuppressWarnings("unchecked")
                    List<Object> requests = (List<Object>) asMap(request.getBody())
                            .getOrDefault("requests", List.of());
                    var job = batchHandler.create(Math.max(1, requests.size()));
                    yield ResponseEntity.ok(batchHandler.toApiResponse(job));
                }
                case "GET" -> {
                    int limit = parseLimit(query, 20);
                    List<AnthropicBatchJobHandler.BatchJob> all = batchHandler.list(limit);
                    List<Map<String, Object>> data = all.stream()
                            .map(batchHandler::toApiResponse).toList();
                    boolean hasMore = all.size() >= limit;
                    yield ResponseEntity.ok(Map.of(
                            "data", data,
                            "has_more", hasMore,
                            "first_id", data.isEmpty() ? null : data.get(0).get("id"),
                            "last_id", data.isEmpty() ? null : data.get(data.size() - 1).get("id")
                    ));
                }
                default -> throw new IllegalArgumentException(
                        "unsupported method on /v1/messages/batches: " + request.getMethod());
            };
        }

        // /v1/messages/batches/{id}  /v1/messages/batches/{id}/cancel  /v1/messages/batches/{id}/results
        String[] parts = cleanPath.split("/");
        String id = parts.length >= 5 ? parts[4] : "";
        var maybe = batchHandler.get(id);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "type", "error",
                    "error", Map.of("type", "not_found_error", "message", "batch not found: " + id)));
        }
        if (parts.length == 5) {
            return ResponseEntity.ok(batchHandler.toApiResponse(maybe.get()));
        }
        if (parts.length == 6 && "cancel".equals(parts[5])) {
            if (!batchHandler.cancel(id)) {
                return ResponseEntity.status(404).body(Map.of(
                        "type", "error",
                        "error", Map.of("type", "not_found_error", "message", "no cancellable batch: " + id)));
            }
            return ResponseEntity.ok(Map.of("id", id, "type", "message_batch",
                    "processing_status", "canceling"));
        }
        if (parts.length == 6 && "results".equals(parts[5])) {
            // jsonl — one JSON object per line
            StringBuilder body = new StringBuilder();
            for (Map<String, Object> line : maybe.get().results()) {
                body.append(toJson(line)).append('\n');
            }
            return ResponseEntity.ok().header("Content-Type", "application/x-jsonlines")
                    .body(body.toString());
        }
        throw new IllegalArgumentException("unknown batches path: " + path);
    }

    private int parseLimit(String query, int def) {
        for (String kv : query.split("&")) {
            if (kv.startsWith("limit=")) {
                try { return Integer.parseInt(kv.substring(6)); } catch (NumberFormatException ignored) {}
            }
        }
        return def;
    }

    /**
     * {@code POST /v1/messages/count_tokens} — token 预算.
     * <p>Claude SDK 在上下文管理 / 长对话限流校验时会调;返回的 shape 与
     * 真实厂商对齐:{@code {input_tokens:N}}.
     */
    private Object handleCountTokens(MockRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = asMap(request.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        String userText = extractUserText(messages);
        int inputTokens = estimator.estimate(userText);
        return ResponseEntity.ok(Map.of("input_tokens", inputTokens));
    }

    private boolean isAuthenticated(VendorConfig vendor, MockRequest req) {
        String auth = req.getAuthHeader();
        if (auth == null) return false;
        if (auth.startsWith("Bearer ")) {
            return vendor.getKey().equals(auth.substring(7).trim());
        }
        return vendor.getKey().equals(auth.trim());
    }

    @SuppressWarnings("unchecked")
    private Object handleMessages(MockRequest request) {
        Map<String, Object> body = asMap(request.getBody());
        String model = stringOr(body.get("model"), "claude-3-5-sonnet-20241022");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());

        String userText = extractUserText(messages);
        String content = "[mock] 你说的内容是: " + userText;
        int inputTokens = estimator.estimate(userText);
        int outputTokens = estimator.estimate(content);

        if (Boolean.TRUE.equals(body.get("stream"))) {
            return buildStreamEmitter(model, content, inputTokens, outputTokens);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "msg_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.put("type", "message");
        response.put("role", "assistant");
        response.put("model", model);
        response.put("content", List.of(Map.of("type", "text", "text", content)));
        response.put("stop_reason", "end_turn");
        response.put("stop_sequence", null);
        response.put("usage", Map.of(
                "input_tokens", inputTokens,
                "output_tokens", outputTokens
        ));
        return ResponseEntity.ok(response);
    }

    private SseEmitter buildStreamEmitter(String model, String content,
                                           int inputTokens, int outputTokens) {
        SseEmitter emitter = new SseEmitter(60_000L);
        List<Map<String, Object>> frames = buildStreamFrames(model, content, inputTokens, outputTokens);
        // 2026-09-22 挂账池 D8：钉 17 基线（mmagix-token 测试 JVM=17，release 21 class(major 65)
        // 会被测试 JVM 拒载 UnsupportedClassVersionError）——startVirtualThread 为 21 API，
        // mock 并发量级下平台线程语义等价（fire-and-forget 帧泵）。
        new Thread(() -> {
            try {
                for (Map<String, Object> frame : frames) {
                    String type = String.valueOf(frame.get("type"));
                    sendEvent(emitter, type, frame);
                }
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    /**
     * 构造 Anthropic SSE 帧序列(供测试断言;生产代码用 buildStreamEmitter).
     * <p>6 类帧: message_start / content_block_start / content_block_delta×N /
     * content_block_stop / message_delta / message_stop.
     */
    public List<Map<String, Object>> buildStreamFrames(String model, String content,
                                                        int inputTokens, int outputTokens) {
        String messageId = "msg_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        List<Map<String, Object>> frames = new java.util.ArrayList<>();

        Map<String, Object> startUsage = new LinkedHashMap<>();
        startUsage.put("input_tokens", inputTokens);
        startUsage.put("output_tokens", 0);
        startUsage.put("cache_read_input_tokens", 0);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", List.of());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", startUsage);
        Map<String, Object> messageStart = new LinkedHashMap<>();
        messageStart.put("type", "message_start");
        messageStart.put("message", message);
        frames.add(messageStart);

        Map<String, Object> blockStart = new LinkedHashMap<>();
        blockStart.put("type", "content_block_start");
        blockStart.put("index", 0);
        blockStart.put("content_block", Map.of("type", "text", "text", ""));
        frames.add(blockStart);

        for (String piece : sseChunker.chunk(content)) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("type", "content_block_delta");
            delta.put("index", 0);
            delta.put("delta", Map.of("type", "text_delta", "text", piece));
            frames.add(delta);
        }

        Map<String, Object> blockStop = new LinkedHashMap<>();
        blockStop.put("type", "content_block_stop");
        blockStop.put("index", 0);
        frames.add(blockStop);

        Map<String, Object> stopDelta = new LinkedHashMap<>();
        stopDelta.put("stop_reason", "end_turn");
        stopDelta.put("stop_sequence", null);
        Map<String, Object> messageDelta = new LinkedHashMap<>();
        messageDelta.put("type", "message_delta");
        messageDelta.put("delta", stopDelta);
        messageDelta.put("usage", Map.of("output_tokens", outputTokens));
        frames.add(messageDelta);

        frames.add(Map.of("type", "message_stop"));
        return frames;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) throws IOException {
        emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(eventName)
                .data(toJson(payload), MediaType.APPLICATION_JSON));
    }

    private String toJson(Map<String, Object> data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    private Object handleListModels(VendorConfig vendor) {
        List<Map<String, Object>> data = vendor.getModels() == null ? List.of()
                : vendor.getModels().stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getCode(),
                        "display_name", m.getCode(),
                        "type", "model",
                        "created_at", Instant.now().toString()))
                .toList();
        return ResponseEntity.ok(Map.of("data", data));
    }

    private Map<String, Object> asMap(Object body) {
        if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) body;
            return m;
        }
        return new LinkedHashMap<>();
    }

    private String stringOr(Object v, String fb) {
        return v == null || String.valueOf(v).isBlank() ? fb : String.valueOf(v);
    }

    private String extractUserText(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return "(空)";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("user".equals(m.get("role"))) {
                Object c = m.get("content");
                if (c == null) return "(空)";
                if (c instanceof List<?>) {
                    return ((List<?>) c).stream()
                            .filter(x -> x instanceof Map && "text".equals(((Map<?, ?>) x).get("type")))
                            .map(x -> String.valueOf(((Map<?, ?>) x).get("text")))
                            .reduce("", (a, b) -> a + " " + b);
                }
                return String.valueOf(c);
            }
        }
        return "(空)";
    }
}
