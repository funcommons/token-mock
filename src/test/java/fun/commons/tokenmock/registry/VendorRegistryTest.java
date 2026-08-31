package fun.commons.tokenmock.registry;

import fun.commons.tokenmock.config.MockProperties;
import fun.commons.tokenmock.config.ModelConfig;
import fun.commons.tokenmock.config.VendorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VendorRegistryTest {

    private VendorConfig openai;
    private VendorConfig deepseek;
    private VendorRegistry registry;

    @BeforeEach
    void setup() {
        openai = new VendorConfig();
        openai.setSlug("openai");
        openai.setProtocol("openai");
        openai.setKey("sk-openai-xxx");
        openai.setModels(List.of(
                model("gpt-4o", "chat"),
                model("gpt-4o-mini", "chat")
        ));

        deepseek = new VendorConfig();
        deepseek.setSlug("deepseek");
        deepseek.setProtocol("openai");
        deepseek.setKey("sk-deepseek-xxx");
        deepseek.setModels(List.of(model("deepseek-chat", "chat")));

        MockProperties props = new MockProperties();
        props.setAdminToken("admin-secret");
        props.setVendors(List.of(openai, deepseek));

        registry = new VendorRegistry(props);
        registry.init();
    }

    private ModelConfig model(String code, String modality) {
        ModelConfig m = new ModelConfig();
        m.setCode(code);
        m.setModality(modality);
        return m;
    }

    @Test
    void init_loads_all_vendors_indexed_by_slug() {
        assertThat(registry.findBySlug("openai")).isSameAs(openai);
        assertThat(registry.findBySlug("deepseek")).isSameAs(deepseek);
        assertThat(registry.findBySlug("ghost")).isNull();
    }

    @Test
    void all_returns_full_list() {
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void findByProtocol_groups_vendors() {
        assertThat(registry.findByProtocol("openai")).hasSize(2);
        assertThat(registry.findByProtocol("anthropic")).isEmpty();
    }

    @Test
    void require_throws_when_unknown() {
        assertThatThrownBy(() -> registry.require("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vendor");
    }

    @Test
    void init_rejects_duplicate_slug() {
        MockProperties props = new MockProperties();
        props.setVendors(List.of(openai, openai));
        VendorRegistry r = new VendorRegistry(props);
        assertThatThrownBy(r::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void authenticate_returns_true_for_matching_key() {
        assertThat(registry.authenticate(openai, "Bearer sk-openai-xxx")).isTrue();
    }

    @Test
    void authenticate_returns_false_for_wrong_key() {
        assertThat(registry.authenticate(openai, "Bearer wrong-key")).isFalse();
    }

    @Test
    void authenticate_returns_false_for_null_header() {
        assertThat(registry.authenticate(openai, null)).isFalse();
    }

    @Test
    void authenticate_returns_false_for_non_bearer_header() {
        assertThat(registry.authenticate(openai, "Basic xxxx")).isFalse();
    }
}
