package fun.commons.tokenmock.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.VendorConfig;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.exception.MockExceptionHandler;
import fun.commons.tokenmock.registry.StatsCollector;
import fun.commons.tokenmock.registry.VendorRegistry;
import fun.commons.tokenmock.core.FaultInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminVendorControllerTest {

    private MockMvc mvc;
    private MockProperties props;
    private VendorRegistry registry;

    @BeforeEach
    void setup() {
        ModelConfig gpt = new ModelConfig();
        gpt.setCode("gpt-4o");
        VendorConfig openai = new VendorConfig();
        openai.setSlug("openai");
        openai.setProtocol("openai");
        openai.setKey("sk-openai-xxx");
        openai.setModels(List.of(gpt));

        props = new MockProperties();
        props.setAdminToken("admin-secret");
        props.setVendors(List.of(openai));
        registry = new VendorRegistry(props);
        registry.init();

        StatsCollector stats = new StatsCollector();
        FaultInjector fault = new FaultInjector();

        AdminVendorController controller = new AdminVendorController(props, registry, stats);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MockExceptionHandler())
                .build();
    }

    @Test
    void list_vendors_returns_array() throws Exception {
        mvc.perform(get("/admin/vendors").header("X-Mock-Admin-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("openai"));
    }

    @Test
    void list_vendors_returns_401_without_token() throws Exception {
        mvc.perform(get("/admin/vendors")).andExpect(status().isUnauthorized());
    }

    @Test
    void get_vendor_returns_detail() throws Exception {
        mvc.perform(get("/admin/vendors/openai").header("X-Mock-Admin-Token", "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("openai"));
    }

    @Test
    void get_vendor_returns_404_for_unknown() throws Exception {
        mvc.perform(get("/admin/vendors/ghost").header("X-Mock-Admin-Token", "admin-secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void set_faults_updates_failure_rate() throws Exception {
        String body = """
                {"failureRate":0.5,"statusCode":503}
                """;
        mvc.perform(post("/admin/vendors/openai/faults")
                        .header("X-Mock-Admin-Token", "admin-secret")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertThat(registry.findBySlug("openai").getFault().getFailureRate()).isEqualTo(0.5);
        assertThat(registry.findBySlug("openai").getFault().getStatusCode()).isEqualTo(503);
    }

    @Test
    void set_faults_sets_force_next_n_failures() throws Exception {
        String body = """
                {"forceNextNFailures":5}
                """;
        mvc.perform(post("/admin/vendors/openai/faults")
                        .header("X-Mock-Admin-Token", "admin-secret")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
        assertThat(registry.findBySlug("openai").getFault().getForceNextNFailures()).isEqualTo(5);
    }

    @Test
    void set_rate_limit_updates_config() throws Exception {
        String body = """
                {"enabled":true,"qps":50,"tokensPerSecond":200000}
                """;
        mvc.perform(post("/admin/vendors/openai/rate-limit")
                        .header("X-Mock-Admin-Token", "admin-secret")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
        assertThat(registry.findBySlug("openai").getRateLimit().getQps()).isEqualTo(50.0);
        assertThat(registry.findBySlug("openai").getRateLimit().isEnabled()).isTrue();
    }

    @Test
    void global_stats_returns_empty_initially() throws Exception {
        mvc.perform(get("/admin/stats").header("X-Mock-Admin-Token", "admin-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void reset_clears_stats() throws Exception {
        mvc.perform(post("/admin/reset").header("X-Mock-Admin-Token", "admin-secret"))
                .andExpect(status().isOk());
    }
}
