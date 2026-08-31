package fun.commons.tokenmock.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mockOpenApi() {
        return new OpenAPI().info(new Info()
                .title("token-mock API")
                .version("1.0.0")
                .description("多厂商 LLM API Mock — OpenAI / Anthropic / Gemini / Azure / Bedrock / Ollama + 多模态 (TTS/STT/图片/视频)")
                .contact(new Contact().name("token-mock").url("https://github.com/funcommons/token-mock")));
    }

    @Bean
    public GroupedOpenApi openaiGroup() {
        return GroupedOpenApi.builder()
                .group("openai")
                .pathsToMatch("/openai/**", "/deepseek/**", "/moonshot/**", "/zhipu/**",
                        "/tongyi/**", "/minimax/**", "/mistral/**")
                .build();
    }

    @Bean
    public GroupedOpenApi anthropicGroup() {
        return GroupedOpenApi.builder()
                .group("anthropic")
                .pathsToMatch("/anthropic/**")
                .build();
    }

    @Bean
    public GroupedOpenApi geminiGroup() {
        return GroupedOpenApi.builder()
                .group("gemini")
                .pathsToMatch("/gemini/**")
                .build();
    }

    @Bean
    public GroupedOpenApi azureGroup() {
        return GroupedOpenApi.builder()
                .group("azure")
                .pathsToMatch("/azure/**")
                .build();
    }

    @Bean
    public GroupedOpenApi bedrockGroup() {
        return GroupedOpenApi.builder()
                .group("bedrock")
                .pathsToMatch("/bedrock/**")
                .build();
    }

    @Bean
    public GroupedOpenApi ollamaGroup() {
        return GroupedOpenApi.builder()
                .group("ollama")
                .pathsToMatch("/ollama/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminGroup() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/admin/**")
                .build();
    }
}
