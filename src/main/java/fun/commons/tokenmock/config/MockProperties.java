package fun.commons.tokenmock.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "mock")
public class MockProperties {

    @NotBlank
    private String adminToken = "mock-admin-secret";

    @Valid
    @NotEmpty
    private List<VendorConfig> vendors;

    private int serverPort = 9999;
}
