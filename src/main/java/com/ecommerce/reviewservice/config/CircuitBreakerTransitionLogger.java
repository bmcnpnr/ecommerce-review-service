package com.ecommerce.reviewservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Breakers log transitions, not calls (L5): WARN when one opens, INFO when it closes again. While a breaker is open
 * every call is rejected without a network attempt, so a line per call would only be noise; the
 * {@code resilience4j_circuitbreaker_*} metrics and the CircuitBreakerOpen alert do the per-call accounting.
 *
 * <p>Registered on the registry, so it covers the breakers that exist now and any created later (Spring Cloud
 * creates the Feign breaker lazily on the first call).
 */
@Component
public class CircuitBreakerTransitionLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);

    public CircuitBreakerTransitionLogger(CircuitBreakerRegistry registry) {
        registry.getAllCircuitBreakers().forEach(this::listenTo);
        registry.getEventPublisher().onEntryAdded(event -> listenTo(event.getAddedEntry()));
    }

    private void listenTo(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(event -> log(breaker, event));
    }

    private static void log(CircuitBreaker breaker, CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.State to = event.getStateTransition().getToState();
        switch (to) {
            case OPEN, FORCED_OPEN -> log.warn("Circuit breaker '{}' is now {} ({}): calls are rejected without being sent (failure rate {}%)",
                    breaker.getName(), to, event.getStateTransition().getFromState(), breaker.getMetrics().getFailureRate());
            case CLOSED -> log.info("Circuit breaker '{}' is closed again ({})", breaker.getName(), event.getStateTransition().getFromState());
            default -> log.debug("Circuit breaker '{}' moved {} -> {}", breaker.getName(),
                    event.getStateTransition().getFromState(), to);
        }
    }
}
