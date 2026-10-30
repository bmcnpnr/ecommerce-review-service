package com.ecommerce.reviewservice.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTransitionLoggerTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(null);
    }

    private static CircuitBreakerRegistry registry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
    }

    @Test
    void opening_isOneWarn_closing_isOneInfo() {
        CircuitBreakerRegistry registry = registry();
        new CircuitBreakerTransitionLogger(registry);
        CircuitBreaker breaker = registry.circuitBreaker("product-service");

        for (int i = 0; i < 4; i++) {
            breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("down"));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("product-service").contains("OPEN");
        assertThat(logs.list.get(1).getFormattedMessage()).contains("product-service").contains("closed again");
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    @Test
    void callsRejectedWhileOpen_logNothing() {
        CircuitBreakerRegistry registry = registry();
        new CircuitBreakerTransitionLogger(registry);
        CircuitBreaker breaker = registry.circuitBreaker("product-service");
        breaker.transitionToOpenState();
        logs.list.clear();

        for (int i = 0; i < 1000; i++) {
            assertThat(breaker.tryAcquirePermission()).isFalse();
        }

        assertThat(logs.list).as("1000 rejected calls, no log lines").isEmpty();
    }

    @Test
    void forcedOpen_isAWarn() {
        CircuitBreakerRegistry registry = registry();
        new CircuitBreakerTransitionLogger(registry);

        registry.circuitBreaker("product-service").transitionToForcedOpenState();

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void breakersThatAlreadyExist_areCovered_notOnlyLaterOnes() {
        CircuitBreakerRegistry registry = registry();
        CircuitBreaker early = registry.circuitBreaker("early");

        new CircuitBreakerTransitionLogger(registry);
        early.transitionToOpenState();

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("early");
    }
}
