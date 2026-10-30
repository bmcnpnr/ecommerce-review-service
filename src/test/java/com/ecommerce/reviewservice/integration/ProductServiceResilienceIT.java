package com.ecommerce.reviewservice.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.reviewservice.config.CircuitBreakerTransitionLogger;
import com.ecommerce.reviewservice.exception.GlobalExceptionHandler;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the review-service reacts to a product-service that is slow, gone, erroring or flapping, through the real
 * Feign client, circuit breaker, time limiter and exception handler, on PostgreSQL with the production schema.
 *
 * <p>The circuit breaker is the one Spring Cloud builds for the Feign client; these tests prove the settings in
 * application.yaml reach it: the time limiter is above the Feign read timeout (the default 1 s used to cut every
 * slower call to the fallback), the breaker is called {@code product-service} and opens on its configured
 * window, 4xx answers do not count against it, and the HTTP client does not retry on its own (R-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration",
        "management.tracing.sampling.probability=0.0",
        "spring.cloud.openfeign.client.config.default.connect-timeout=1000",
        "spring.cloud.openfeign.client.config.default.read-timeout=3000",
        // the production value is 10s; shortened so the half-open recovery fits in a test
        "resilience4j.circuitbreaker.instances.product-service.wait-duration-in-open-state=2s",
        // the production value is 5s; shortened to fit under the 3 s read timeout used here
        "resilience4j.circuitbreaker.instances.product-service.slow-call-duration-threshold=2s"
})
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ProductServiceResilienceIT extends PostgresIntegrationTestSupport {

    // WireMock serves with 10 threads by default; the tests that put two dozen calls in flight at once need more, or
    // the stub itself would queue them and the delays would add up.
    static final WireMockServer PRODUCT_SERVICE = new WireMockServer(options().dynamicPort().containerThreads(50));

    static {
        PRODUCT_SERVICE.start();
    }

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.product-service.url", PRODUCT_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        PRODUCT_SERVICE.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private ApplicationContext context;

    private final ExecutorService clients = Executors.newFixedThreadPool(30);

    private RestTestClient client;
    private final ListAppender<ILoggingEvent> handlerLog = new ListAppender<>();
    private final ListAppender<ILoggingEvent> breakerLog = new ListAppender<>();
    private Logger handlerLogger;
    private Logger breakerLogger;
    private final AtomicInteger customers = new AtomicInteger();

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE review_votes, reviews RESTART IDENTITY CASCADE");
        PRODUCT_SERVICE.resetAll();
        circuitBreakers.circuitBreaker("product-service").reset();   // every test starts with a closed, empty breaker
        // The JDK client, not the default Apache HttpClient: that one re-sends a POST that was answered 503 (its default
        // strategy retries 429/503 whatever the method), which would put a second request behind every 503 asserted here.
        client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory()).baseUrl("http://localhost:" + port).build();

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        breakerLogger = (Logger) LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);
        handlerLog.start();
        breakerLog.start();
        handlerLogger.addAppender(handlerLog);
        breakerLogger.addAppender(breakerLog);
    }

    @AfterEach
    void tearDown() {
        clients.shutdownNow();
        handlerLogger.detachAppender(handlerLog);
        breakerLogger.detachAppender(breakerLog);
        handlerLog.stop();
        breakerLog.stop();
        handlerLog.list.clear();
        breakerLog.list.clear();
    }

    // ───────────────────────── the configuration reaches the breaker Feign really uses ─────────────────────────

    /**
     * Spring Cloud CircuitBreaker's defaults hand every call to a worker thread behind a 1 s time limiter and a
     * thread-pool bulkhead of one thread per CPU with a 100-call queue (two concurrent calls in a 2-CPU container, the
     * rest rejected). All three are switched off in application.yaml: Feign's timeouts govern, the breaker only counts.
     */
    @Test
    void theBreakerAddsNoWorkerThread_noTimeLimiter_andNoBulkhead() {
        Resilience4JConfigurationProperties properties = context.getBean(Resilience4JConfigurationProperties.class);

        assertThat(properties.isDisableThreadPool()).isTrue();
        assertThat(properties.isDisableTimeLimiter()).isTrue();
        assertThat(context.getBeanProvider(Resilience4jBulkheadProvider.class).getIfAvailable())
                .as("spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false").isNull();
    }

    @Test
    void manyCallsToProductService_runAtTheSameTime() throws Exception {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(okJson(PRODUCT).withFixedDelay(1500)));
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String customer = nextCustomer();
            calls.add(CompletableFuture.supplyAsync(() -> client.post().uri("/api/v1/reviews").header("X-Username", customer)
                    .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                    .exchange().returnResult().getStatus().value(), clients));
        }

        for (CompletableFuture<Integer> call : calls) {
            assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        }

        assertThat(maxOverlap("/api/v1/products/1")).as("product-service calls in flight at once, with 24 clients").isGreaterThanOrEqualTo(16);
    }

    /** The highest number of requests for {@code url} that product-service had in flight at the same time. */
    private static int maxOverlap(String url) {
        List<long[]> spans = new ArrayList<>();
        for (ServeEvent event : PRODUCT_SERVICE.getAllServeEvents()) {
            if (event.getRequest().getUrl().equals(url) && event.getTiming() != null) {
                long start = event.getRequest().getLoggedDate().getTime();
                spans.add(new long[] {start, start + event.getTiming().getTotalTime()});
            }
        }
        int max = 0;
        for (long[] a : spans) {
            int overlapping = 0;
            for (long[] b : spans) {
                if (b[0] <= a[0] && a[0] < b[1]) {
                    overlapping++;
                }
            }
            max = Math.max(max, overlapping);
        }
        return max;
    }

    @Test
    void theFeignBreakerIsTheOneNamedProductService_withTheConfiguredWindow() {
        productExists(1L);
        createReview(1L, nextCustomer());

        assertThat(circuitBreakers.getAllCircuitBreakers()).extracting(CircuitBreaker::getName)
                .as("one breaker for the downstream, not one per client method").containsExactly("product-service");
        CircuitBreaker breaker = circuitBreakers.circuitBreaker("product-service");
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(breaker.getCircuitBreakerConfig().getSlowCallRateThreshold()).isEqualTo(80f);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).as("the call went through this breaker").isEqualTo(1);
    }

    // ───────────────────────── slow ─────────────────────────

    @Test
    void aProductServiceThatTakes2point5s_isWaitedFor_notCutAtTheDefault1sTimeLimit() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(okJson(PRODUCT).withFixedDelay(2500)));
        long start = System.nanoTime();

        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange()
                .expectStatus().isCreated();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(2400));
        assertThat(count("SELECT count(*) FROM reviews")).isEqualTo(1);
    }

    /** Slow is a failure mode too: every call that takes the full read timeout holds a request thread. */
    @Test
    void aProductServiceThatAnswersTooSlowly_opensTheBreaker_andLaterCallsFailFast() throws Exception {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(okJson(PRODUCT).withFixedDelay(2500)));   // over the 2 s slow-call threshold used here
        List<CompletableFuture<Integer>> slow = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String customer = nextCustomer();
            slow.add(CompletableFuture.supplyAsync(() -> client.post().uri("/api/v1/reviews").header("X-Username", customer)
                    .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                    .exchange().returnResult().getStatus().value(), clients));
        }

        // five slow (but successful) answers fill the window, all of them over the threshold
        for (CompletableFuture<Integer> call : slow) {
            assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(circuitBreakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange().expectStatus().isEqualTo(503);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("refused by the open breaker, not after another 2.5 s").isLessThan(Duration.ofSeconds(1));
        PRODUCT_SERVICE.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/1")));
    }

    @Test
    void aProductServiceSlowerThanTheReadTimeout_is503_afterTheTimeout_andIsAskedOnce() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(okJson(PRODUCT).withFixedDelay(6000)));
        long start = System.nanoTime();

        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.message").isEqualTo("Service temporarily unavailable");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(2800), Duration.ofSeconds(5));
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/1")));
        assertThat(count("SELECT count(*) FROM reviews")).isZero();
    }

    /** The product-service call happens before any transaction: a slow peer must not pin a pooled connection. */
    @Test
    void whileProductServiceIsSlow_noDatabaseConnectionIsHeld() throws Exception {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(okJson(PRODUCT).withFixedDelay(2000)));
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        String customer = nextCustomer();

        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> client.post().uri("/api/v1/reviews")
                .header("X-Username", customer).contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange().expectStatus().isCreated());
        int maxActive = 0;
        int samples = 0;
        while (!request.isDone()) {
            maxActive = Math.max(maxActive, hikari.getHikariPoolMXBean().getActiveConnections());
            samples++;
            Thread.sleep(25);
        }
        request.get(10, TimeUnit.SECONDS);

        assertThat(samples).as("sampled during the 2 s call").isGreaterThan(20);
        assertThat(maxActive).as("connections in use while waiting for product-service").isZero();
    }

    // ───────────────────────── gone, erroring, resetting ─────────────────────────

    @Test
    void aConnectionResetByProductService_is503_andNobodyRetriesIt() {
        // Not a connect failure, so FeignConfig.ConnectionFailureRetryer leaves it alone, and the HTTP client no
        // longer re-sends an idempotent GET on a reset by itself (R-6): exactly one request reaches the peer.
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange()
                .expectStatus().isEqualTo(503);

        PRODUCT_SERVICE.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/1")));
    }

    @Test
    void a503FromProductService_is503_isNotRepeatedAfterASecond_andSendsOneRequest() {
        // Apache HttpClient 5's default strategy used to retry a 503 once after 1 s underneath Feign.
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withStatus(503)));
        long start = System.nanoTime();

        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.message").isEqualTo("Service temporarily unavailable");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/1")));
    }

    @Test
    void a500FromProductService_is502_withAFixedMessage() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withStatus(500).withBody("java.lang.NullPointerException at com.ecommerce")));

        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Upstream service error")
                .jsonPath("$.message").value(m -> assertThat((String) m).doesNotContain("NullPointer"));
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/1")));
    }

    @Test
    void anUnknownProduct_is404_notAnOutage() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/999").willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":404,\"error\":\"Not Found\",\"message\":\"Product not found with id: 999\"}")));

        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(999L))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isEqualTo("Product not found with id: 999");
    }

    // ───────────────────────── the breaker ─────────────────────────

    @Test
    void repeatedOutages_openTheBreaker_whichThenRejectsWithoutCallingThePeer_andClosesAgainWhenItRecovers() throws Exception {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withStatus(503)));

        for (int i = 0; i < 5; i++) {
            createReviewExpecting(503);
        }
        CircuitBreaker breaker = circuitBreakers.circuitBreaker("product-service");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        PRODUCT_SERVICE.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/1")));

        // open: rejected locally, instantly, without a request to product-service
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            createReviewExpecting(503);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("20 rejected calls").isLessThan(Duration.ofSeconds(3));
        PRODUCT_SERVICE.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/1")));

        // 25 failed requests: one line for the breaker, at most one for the handler, nothing with a stack trace
        assertThat(breakerLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
        assertThat(breakerLog.list.get(0).getFormattedMessage()).contains("product-service").contains("OPEN");
        assertThat(handlerLog.list).hasSizeLessThanOrEqualTo(1);
        assertThat(handlerLog.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());

        // product-service comes back; after the open period the breaker lets calls through and closes again
        PRODUCT_SERVICE.resetAll();
        productExists(1L);
        Thread.sleep(2300);
        for (int i = 0; i < 3; i++) {   // permittedNumberOfCallsInHalfOpenState
            createReview(1L, nextCustomer());
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakerLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
        assertThat(count("SELECT count(*) FROM reviews")).isEqualTo(3);
    }

    @Test
    void clientErrorsFromProductService_neverOpenTheBreaker() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/999").willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":404,\"error\":\"Not Found\",\"message\":\"x\"}")));

        for (int i = 0; i < 15; i++) {
            client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                    .contentType(MediaType.APPLICATION_JSON).body(reviewBody(999L))
                    .exchange().expectStatus().isNotFound();
        }

        CircuitBreaker breaker = circuitBreakers.circuitBreaker("product-service");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).as("a 404 is an answer, not a failure").isZero();
        PRODUCT_SERVICE.verify(15, getRequestedFor(urlEqualTo("/api/v1/products/999")));
        assertThat(breakerLog.list).isEmpty();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static final String PRODUCT = "{\"id\":1,\"sku\":\"SKU-1\",\"name\":\"Wireless Mouse\",\"price\":49.99,"
            + "\"stockQuantity\":50,\"status\":\"ACTIVE\"}";

    private static void productExists(long id) {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/" + id).willReturn(okJson(PRODUCT.replace("\"id\":1", "\"id\":" + id))));
    }

    private String nextCustomer() {
        return "customer-" + customers.incrementAndGet();
    }

    private static Map<String, Object> reviewBody(long productId) {
        return Map.of("productId", productId, "rating", 5, "title", "Great mouse",
                "body", "Comfortable, precise and the battery lasts for weeks.");
    }

    private void createReview(long productId, String customer) {
        client.post().uri("/api/v1/reviews").header("X-Username", customer)
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(productId))
                .exchange().expectStatus().isCreated();
    }

    private void createReviewExpecting(int status) {
        client.post().uri("/api/v1/reviews").header("X-Username", nextCustomer())
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L))
                .exchange().expectStatus().isEqualTo(status);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
