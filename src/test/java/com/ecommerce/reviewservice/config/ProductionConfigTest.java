package com.ecommerce.reviewservice.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience-relevant settings of the packaged {@code application.yaml}, read as the running service reads them
 * (no profile, no override). The ITs run with test overrides on purpose; this pins what production gets.
 */
class ProductionConfigTest {

    private static Binder binder;

    @BeforeAll
    static void load() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        binder = new Binder(ConfigurationPropertySources.from(sources));
    }

    private static <T> T get(String name, Class<T> type) {
        return binder.bind(name, Bindable.of(type)).orElseThrow(() -> new AssertionError("application.yaml has no " + name));
    }

    @Test
    void springCloudCircuitBreaker_runsOnTheCallingThread_withNoTimeLimiterAndNoBulkhead() {
        // the defaults are a worker thread, a 1 s time limiter and a bulkhead of one thread per CPU with a 100-call queue
        assertThat(get("spring.cloud.circuitbreaker.resilience4j.disable-thread-pool", Boolean.class)).isTrue();
        assertThat(get("spring.cloud.circuitbreaker.resilience4j.disable-time-limiter", Boolean.class)).isTrue();
        assertThat(get("spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled", Boolean.class)).isFalse();
        assertThat(get("spring.cloud.openfeign.circuitbreaker.enabled", Boolean.class)).isTrue();
    }

    @Test
    void feignTimeouts_areTheRealTimeLimit() {
        assertThat(get("spring.cloud.openfeign.client.config.default.connect-timeout", Integer.class)).isEqualTo(2000);
        assertThat(get("spring.cloud.openfeign.client.config.default.read-timeout", Integer.class)).isEqualTo(10000);
    }

    @Test
    void theBreaker_opensOnFailuresAndOnSlowness_andIgnoresClientErrors() {
        String p = "resilience4j.circuitbreaker.instances.product-service.";

        assertThat(get(p + "sliding-window-size", Integer.class)).isEqualTo(10);
        assertThat(get(p + "minimum-number-of-calls", Integer.class)).isEqualTo(5);
        assertThat(get(p + "failure-rate-threshold", Integer.class)).isEqualTo(50);
        assertThat(get(p + "wait-duration-in-open-state", Duration.class)).isEqualTo(Duration.ofSeconds(10));
        assertThat(get(p + "slow-call-duration-threshold", Duration.class)).isEqualTo(Duration.ofSeconds(5));
        assertThat(get(p + "slow-call-rate-threshold", Integer.class)).isEqualTo(80);
        assertThat(get(p + "automatic-transition-from-open-to-half-open-enabled", Boolean.class)).isTrue();
        assertThat(binder.bind(p + "ignore-exceptions", Bindable.listOf(String.class)).get())
                .contains("feign.FeignException$NotFound", "feign.FeignException$BadRequest", "feign.FeignException$Conflict");
    }

    @Test
    void database_waitsForAConnectionAtMost5s_andHoldsNoConnectionOutsideATransaction() {
        assertThat(get("spring.datasource.hikari.connection-timeout", Integer.class)).isEqualTo(5000);
        assertThat(get("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    void database_driverBoundsConnectAndRead_soAFrozenDatabaseCannotHoldAThreadForMinutes() {
        Map<String, String> driver = binder.bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class)).get();

        assertThat(driver).containsEntry("connectTimeout", "5")
                .containsEntry("socketTimeout", "30")
                .containsEntry("tcpKeepAlive", "true");
    }

    @Test
    void logging_isInfo_inOneFormat_withTheUnthrottledFrameworkChatterOff() {
        Map<String, String> levels = binder.bind("logging.level", Bindable.mapOf(String.class, String.class)).get();

        assertThat(get("logging.pattern.console", String.class))
                .isEqualTo("%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-}] [%X{traceId:-}] %-5level %logger{36} - %msg%n");
        assertThat(levels).containsEntry("com.ecommerce", "INFO")
                .containsEntry("org.hibernate.orm.jdbc.error", "OFF")
                .containsEntry("org.springframework.transaction.interceptor.TransactionInterceptor", "OFF");
    }
}
