package fun.commons.tokenmock.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class VendorConfig {

    @NotBlank
    private String slug;

    @NotBlank
    private String protocol;

    @NotBlank
    private String key;

    private int latencyMs = 0;

    private double failureRate = 0.0;

    @Valid
    private List<ModelConfig> models;

    /** 仅 Azure: deployment-name → model 映射 */
    private List<DeploymentConfig> deployments;

    @Valid
    @NotNull
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @Valid
    @NotNull
    private FaultConfig fault = new FaultConfig();

    public ModelConfig findModel(String code) {
        if (models == null) return null;
        return models.stream()
                .filter(m -> m.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
