package fun.commons.tokenmock.web;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.core.EmbeddingGenerator;
import fun.commons.tokenmock.core.ResponseGenerator;
import fun.commons.tokenmock.core.TokenEstimator;
import fun.commons.tokenmock.exception.MockExceptionHandler;
import fun.commons.tokenmock.handler.anthropic.AnthropicBatchJobHandler;
import fun.commons.tokenmock.handler.anthropic.AnthropicProtocolHandler;
import fun.commons.tokenmock.handler.azure.AzureProtocolHandler;
import fun.commons.tokenmock.handler.bedrock.BedrockProtocolHandler;
import fun.commons.tokenmock.handler.gemini.GeminiFileStore;
import fun.commons.tokenmock.handler.gemini.GeminiProtocolHandler;
import fun.commons.tokenmock.handler.ollama.OllamaProtocolHandler;
import fun.commons.tokenmock.handler.openai.OpenAIProtocolHandler;
import fun.commons.tokenmock.handler.openai.BatchJobHandler;
import fun.commons.tokenmock.handler.openai.ImageJobHandler;
import fun.commons.tokenmock.handler.openai.ResponseJobHandler;
import fun.commons.tokenmock.registry.InMemoryFileStore;
import fun.commons.tokenmock.registry.VendorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MockDispatchControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ModelConfig gpt = new ModelConfig();
        gpt.setCode("gpt-4o");
        gpt.setModality("chat");

        VendorConfig openai = new VendorConfig();
        openai.setSlug("openai");
        openai.setProtocol("openai");
        openai.setKey("sk-openai-xxx");
        openai.setModels(List.of(gpt));

        ModelConfig claude = new ModelConfig();
        claude.setCode("claude-3-5-sonnet");
        claude.setModality("chat");

        VendorConfig anthropic = new VendorConfig();
        anthropic.setSlug("anthropic");
        anthropic.setProtocol("anthropic");
        anthropic.setKey("sk-ant-xxx");
        anthropic.setModels(List.of(claude));

        VendorConfig gemini = new VendorConfig();
        gemini.setSlug("gemini");
        gemini.setProtocol("gemini");
        gemini.setKey("AIzaSyGem");
        gemini.setModels(List.of(gpt));

        VendorConfig azure = new VendorConfig();
        azure.setSlug("azure");
        azure.setProtocol("azure");
        azure.setKey("azure-key");

        VendorConfig bedrock = new VendorConfig();
        bedrock.setSlug("bedrock");
        bedrock.setProtocol("bedrock");
        bedrock.setKey("aws4-key");

        VendorConfig ollama = new VendorConfig();
        ollama.setSlug("ollama");
        ollama.setProtocol("ollama");
        ollama.setKey("ollama");

        MockProperties props = new MockProperties();
        props.setAdminToken("admin-secret");
        props.setVendors(List.of(openai, anthropic, gemini, azure, bedrock, ollama));

        VendorRegistry registry = new VendorRegistry(props);
        registry.init();

        TokenEstimator estimator = new TokenEstimator();
        ResponseGenerator generator = new ResponseGenerator(estimator);
        EmbeddingGenerator embed = new EmbeddingGenerator();
        fun.commons.tokenmock.core.PlaceholderResources ph = new fun.commons.tokenmock.core.PlaceholderResources();
        fun.commons.tokenmock.handler.openai.AudioImageHandler audioImage = new fun.commons.tokenmock.handler.openai.AudioImageHandler(ph);
        fun.commons.tokenmock.handler.video.VideoJobHandler video = new fun.commons.tokenmock.handler.video.VideoJobHandler(ph);
        fun.commons.tokenmock.registry.StatsCollector stats = new fun.commons.tokenmock.registry.StatsCollector();
        fun.commons.tokenmock.core.FaultInjector fault = new fun.commons.tokenmock.core.FaultInjector();

        MockDispatchController controller = new MockDispatchController(
                List.of(
                        new OpenAIProtocolHandler(registry, generator, embed, audioImage, video, new InMemoryFileStore(), new ResponseJobHandler(), new BatchJobHandler(), new ImageJobHandler(ph)),
                        new AnthropicProtocolHandler(registry, estimator, new fun.commons.tokenmock.core.SseChunker(), new InMemoryFileStore(), new AnthropicBatchJobHandler()),
                        new GeminiProtocolHandler(registry, estimator, embed, new GeminiFileStore()),
                        new AzureProtocolHandler(registry, generator, embed, audioImage, video, new ResponseJobHandler(), new BatchJobHandler()),
                        new BedrockProtocolHandler(registry, estimator, new fun.commons.tokenmock.core.SseChunker()),
                        new OllamaProtocolHandler(registry, estimator)
                ),
                props,
                registry,
                stats,
                fault
        );
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MockExceptionHandler())
                .build();
    }

    @Test
    void openai_chat_completion_works() throws Exception {
        String body = """
                {"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}
                """;
        mvc.perform(post("/openai/v1/chat/completions")
                        .header("Authorization", "Bearer sk-openai-xxx")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").exists());
    }

    @Test
    void openai_returns_401_for_wrong_key() throws Exception {
        String body = """
                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}
                """;
        mvc.perform(post("/openai/v1/chat/completions")
                        .header("Authorization", "Bearer wrong")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openai_models_list_works() throws Exception {
        mvc.perform(get("/openai/v1/models").header("Authorization", "Bearer sk-openai-xxx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("gpt-4o"));
    }

    @Test
    void anthropic_messages_works() throws Exception {
        String body = """
                {"model":"claude-3-5-sonnet","messages":[{"role":"user","content":"你好"}]}
                """;
        mvc.perform(post("/anthropic/v1/messages")
                        .header("x-api-key", "sk-ant-xxx")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"));
    }

    @Test
    void gemini_generate_content_works() throws Exception {
        String body = """
                {"contents":[{"role":"user","parts":[{"text":"你好"}]}]}
                """;
        mvc.perform(post("/gemini/v1/models/gemini-1.5-pro:generateContent")
                        .header("Authorization", "Bearer AIzaSyGem")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].content.parts[0].text").exists());
    }

    @Test
    void azure_chat_completion_works() throws Exception {
        String body = """
                {"messages":[{"role":"user","content":"hi"}]}
                """;
        mvc.perform(post("/azure/openai/deployments/gpt-4o-deployment/chat/completions")
                        .header("Authorization", "Bearer azure-key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void bedrock_invoke_works() throws Exception {
        String body = """
                {"prompt":"hello"}
                """;
        mvc.perform(post("/bedrock/model/anthropic.claude-v1/invoke")
                        .header("Authorization", "Bearer aws4-key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void bedrock_accepts_aws4_signature() throws Exception {
        String body = """
                {"prompt":"hello"}
                """;
        mvc.perform(post("/bedrock/model/x/invoke")
                        .header("Authorization", "AWS4-HMAC-SHA256 Credential=...")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void ollama_chat_works() throws Exception {
        String body = """
                {"model":"llama3:8b","messages":[{"role":"user","content":"hi"}]}
                """;
        mvc.perform(post("/ollama/api/chat")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test
    void unknown_vendor_returns_404() throws Exception {
        mvc.perform(post("/ghost/v1/x").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknown_path_returns_400() throws Exception {
        mvc.perform(post("/openai/v1/unknown")
                        .header("Authorization", "Bearer sk-openai-xxx")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
