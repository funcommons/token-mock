package fun.commons.tokenmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * token-mock 启动入口 (多厂商 LLM API Mock)。
 * <p>
 * 独立可启,无 PG/Redis/Kafka 依赖。仿造多厂商 LLM API,用于集成测试。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}
