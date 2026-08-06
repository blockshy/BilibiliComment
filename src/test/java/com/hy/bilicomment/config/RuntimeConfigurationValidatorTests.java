package com.hy.bilicomment.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeConfigurationValidatorTests {

    @Test
    void rejectsAnUnknownEnvironmentInsteadOfSkippingBoundaryValidation() {
        AppProperties properties = validProperties("deev");

        assertThatThrownBy(() -> validator(properties, new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENV");
    }

    @Test
    void rejectsADevelopmentRuntimeConnectedToTheWrongDatabase() {
        AppProperties properties = validProperties("dev");
        MockEnvironment environment = validSharedEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/bilibili_comment_prod");

        assertThatThrownBy(() -> validator(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database");
    }

    @Test
    void rejectsADevelopmentRuntimeUsingTheMigratorOrProductionRole() {
        AppProperties properties = validProperties("dev");
        MockEnvironment environment = validSharedEnvironment()
                .withProperty("spring.datasource.username", "bilibili_comment_dev_migrator");

        assertThatThrownBy(() -> validator(properties, environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
    }

    @Test
    void acceptsTheExactDevelopmentDomainDatabaseAndRuntimeRole() {
        assertThatCode(() -> validator(validProperties("dev"), validSharedEnvironment())
                        .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void localModeAllowsAnIsolatedFixtureDatabaseAndInsecureCookie() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/fixture")
                .withProperty("spring.datasource.username", "fixture");

        assertThatCode(() -> validator(validProperties("local"), environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsReusingOneKeyForCredentialsCursorsAndExports() {
        AppProperties properties = validProperties("local");
        properties.getCursor().setSigningKey(properties.getCrypto().getCredentialKey());

        assertThatThrownBy(() -> validator(properties, new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("independently");
    }

    private RuntimeConfigurationValidator validator(
            AppProperties properties, MockEnvironment environment) {
        return new RuntimeConfigurationValidator(properties, environment);
    }

    private AppProperties validProperties(String applicationEnvironment) {
        AppProperties properties = new AppProperties();
        properties.setEnvironment(applicationEnvironment);
        properties.setPublicBaseUrl("dev".equals(applicationEnvironment)
                ? "https://dev.bili-comments.tyukki.com"
                : "http://localhost:5173");
        properties.getCrypto().setCredentialKey(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.getCursor().setSigningKey(
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        properties.getExports().setEncryptionKey(
                "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        return properties;
    }

    private MockEnvironment validSharedEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/bilibili_comment_dev")
                .withProperty("spring.datasource.username", "bilibili_comment_dev_app")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("spring.flyway.enabled", "false");
    }
}
