package com.ecommerce.reviewservice.config;

import java.net.ConnectException;
import java.util.List;

import feign.RetryableException;
import feign.Retryer;
import org.apache.hc.client5.http.ConnectTimeoutException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;

@Configuration(proxyBeanMethods = false)
public class FeignConfig {

    /**
     * Replaces Spring Cloud OpenFeign 5.0.1's {@code FeignHttpMessageConverters}
     * bean (declared {@code @ConditionalOnMissingBean} in FeignClientsConfiguration;
     * the per-client child contexts see this parent bean and skip their own).
     *
     * Why: the library initialises its converter list lazily and without
     * synchronisation — it publishes an empty ArrayList and only then fills it.
     * When the first Feign calls of a fresh JVM arrive concurrently, a second
     * thread sees the empty list and SpringDecoder fails with
     * {@code DecodeException: 'messageConverters' must not be empty} — an HTTP
     * 500 for that caller. First observed as a burst of catalog syncs right after
     * a pod restart; every Feign client on this Spring Cloud line has the same
     * window. The subclass makes the first initialisation mutually exclusive;
     * once built, reads are lock-free.
     */
    @Bean
    public FeignHttpMessageConverters feignHttpMessageConverters(
            ObjectProvider<ClientHttpMessageConvertersCustomizer> customizers,
            ObjectProvider<HttpMessageConverterCustomizer> cloudCustomizers) {
        return new ThreadSafeFeignHttpMessageConverters(customizers, cloudCustomizers);
    }

    /**
     * Retries only *connection* failures — the peer was never reached, so the
     * call is safe to repeat on any method, and the LoadBalancer picks an
     * instance afresh on every attempt. Read timeouts and HTTP errors are not
     * retried: the request may have been processed. This bridges the few
     * seconds between a peer pod vanishing and every registry cache dropping
     * it during a rollout.
     */
    @Bean
    public Retryer feignRetryer() {
        return new ConnectionFailureRetryer(3, 100);
    }

    /**
     * Apache HttpClient 5 quietly re-sends an idempotent request once on a reset connection and again, after a
     * pause, on a 503 (R-6). That is a second retry layer under {@link ConnectionFailureRetryer}, one that
     * contradicts the rule above and doubles the load on a peer that is already struggling. Switched off, so the
     * retryer is the only one.
     */
    @Bean
    public HttpClient5FeignConfiguration.HttpClientBuilderCustomizer noAutomaticHttpClientRetries() {
        return builder -> builder.disableAutomaticRetries();
    }

    /**
     * One circuit breaker per downstream service instead of one per client method (the default names are derived
     * from the interface and method, e.g. {@code ProductServiceClientgetProductByIdLong}). The name is what
     * {@code resilience4j.circuitbreaker.instances.product-service} and
     * {@code resilience4j.timelimiter.instances.product-service} in application.yaml configure; with the default
     * names those blocks applied to nothing and the breaker ran on resilience4j's defaults (100 calls before it
     * could open, and a 1 s time limit that cut every slower call to the fallback).
     */
    @Bean
    public CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return (feignClientName, target, method) -> feignClientName;
    }

    public static class ConnectionFailureRetryer implements Retryer {

        private final int maxAttempts;
        private final long backoffMillis;
        private int attempt = 1;

        public ConnectionFailureRetryer(int maxAttempts, long backoffMillis) {
            this.maxAttempts = maxAttempts;
            this.backoffMillis = backoffMillis;
        }

        /** Connection refused (HttpHostConnectException is a ConnectException) or a connect-phase timeout; a read timeout is a plain SocketTimeoutException and is excluded on purpose. */
        public static boolean isConnectionFailure(Throwable cause) {
            return cause instanceof ConnectException || cause instanceof ConnectTimeoutException;
        }

        @Override
        public void continueOrPropagate(RetryableException e) {
            if (!isConnectionFailure(e.getCause()) || attempt >= maxAttempts) {
                throw e;
            }
            try {
                Thread.sleep(backoffMillis * attempt);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw e;
            }
            attempt++;
        }

        @Override
        public Retryer clone() {
            return new ConnectionFailureRetryer(maxAttempts, backoffMillis);
        }
    }

    public static class ThreadSafeFeignHttpMessageConverters extends FeignHttpMessageConverters {

        private volatile boolean initialised;

        public ThreadSafeFeignHttpMessageConverters(
                ObjectProvider<ClientHttpMessageConvertersCustomizer> customizers,
                ObjectProvider<HttpMessageConverterCustomizer> cloudCustomizers) {
            super(customizers, cloudCustomizers);
        }

        @Override
        public List<HttpMessageConverter<?>> getConverters() {
            if (!initialised) {
                synchronized (this) {
                    if (!initialised) {
                        // The superclass builds the list on first access; holding
                        // the lock for that first access is the whole fix. The
                        // volatile write afterwards publishes the filled list.
                        super.getConverters();
                        initialised = true;
                    }
                }
            }
            return super.getConverters();
        }
    }
}
