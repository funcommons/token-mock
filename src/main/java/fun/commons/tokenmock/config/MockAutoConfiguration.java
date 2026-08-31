package fun.commons.tokenmock.config;

import fun.commons.tokenmock.registry.VendorRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Mock vendor auto-configuration. Only active under the {@code mock} profile
 * (dev/IT). Production must NOT activate it.
 */
@Profile("mock")
@Configuration
@EnableConfigurationProperties(MockProperties.class)
public class MockAutoConfiguration {

    @Bean
    public VendorRegistry vendorRegistry(MockProperties properties) {
        VendorRegistry registry = new VendorRegistry(properties);
        registry.init();
        return registry;
    }
}