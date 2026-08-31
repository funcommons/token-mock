package fun.commons.tokenmock.handler.openai;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.PlaceholderResources;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.handler.MockRequest;
import fun.commons.tokenmock.handler.video.VideoJobHandler;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AudioImageHandlerTest {

    private OpenAIProtocolHandler handler;

    @BeforeEach
    void setup() {
        ModelConfig m = new ModelConfig();
        m.setCode("gpt-4o");
        VendorConfig v = new VendorConfig();
        v.setSlug("openai");
        v.setProtocol("openai");
        v.setKey("sk-openai-xxx");
        v.setModels(List.of(m));

        MockProperties props = new MockProperties();
        props.setAdminToken("admin-secret");
        props.setVendors(List.of(v));
        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        TokenEstimator estimator = new TokenEstimator();
        PlaceholderResources ph = new PlaceholderResources();
        handler = new OpenAIProtocolHandler(
                registry,
                new ResponseGenerator(estimator),
                new EmbeddingGenerator(),
                new AudioImageHandler(ph),
                new VideoJobHandler(ph)
        );
    }

    @Test
    void audio_speech_returns_mp3_bytes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "tts-1");
        body.put("input", "hello");
        body.put("voice", "alloy");

        Object resp = handler.handle(MockRequest.of(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/audio/speech", body
        ));
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        assertThat(re.getStatusCode().value()).isEqualTo(200);
        assertThat(re.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("audio/mpeg"));
        assertThat((byte[]) re.getBody()).isNotEmpty();
    }

    @Test
    void audio_speech_returns_wav_when_format_is_wav() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", "hi");
        body.put("response_format", "wav");

        Object resp = handler.handle(MockRequest.of(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/audio/speech", body
        ));
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        assertThat(re.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("audio/wav"));
    }

    @Test
    void audio_transcription_returns_text() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", "zh");
        body.put("__file_size__", 12345);

        Object resp = handler.handle(MockRequest.of(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/audio/transcriptions", body
        ));
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        assertThat(re.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) re.getBody();
        assertThat((String) b.get("text")).contains("12345").contains("zh");
        assertThat(b.get("language")).isEqualTo("zh");
    }

    @Test
    void images_generations_returns_url_by_default() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", "a cat");
        body.put("n", 2);

        Object resp = handler.handle(MockRequest.of(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/images/generations", body
        ));
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) re.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) b.get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0)).containsKey("url");
        assertThat((String) data.get(0).get("revised_prompt")).contains("a cat");
    }

    @Test
    void images_generations_returns_b64_when_format_b64() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", "x");
        body.put("response_format", "b64_json");

        Object resp = handler.handle(MockRequest.of(
                "openai", "Bearer sk-openai-xxx", "/openai/v1/images/generations", body
        ));
        ResponseEntity<?> re = (ResponseEntity<?>) resp;
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) re.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) b.get("data");
        assertThat(data.get(0)).containsKey("b64_json");
    }
}
