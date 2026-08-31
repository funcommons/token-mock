package fun.commons.tokenmock.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ModelConfig {

    @NotBlank
    private String code;

    /** chat (default) / embed / tts / stt / image-gen / image-vision / video-gen. */
    private String modality = "chat";

    private int context = 8192;

    /** 仅 embed 模型: 向量维度。 */
    private int dimensions = 1536;
}
