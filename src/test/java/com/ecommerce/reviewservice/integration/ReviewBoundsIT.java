package com.ecommerce.reviewservice.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.reviewservice.exception.GlobalExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public list and summary endpoints on a product with far more reviews than a response should carry
 * (D3), the number of queries behind them, and the pool (D1), on PostgreSQL with the production schema.
 * product-service is not involved: nothing here creates a review.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration",
        "management.tracing.sampling.probability=0.0",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ReviewBoundsIT extends PostgresIntegrationTestSupport {

    @DynamicPropertySource
    static void deadProductService(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.product-service.url", () -> "http://localhost:" + freePort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext context;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ListAppender<ILoggingEvent> handlerLog = new ListAppender<>();
    private Logger handlerLogger;
    /** Every logger, so that Hibernate's, Spring's and Hikari's own lines are counted too. */
    private final ListAppender<ILoggingEvent> everything = new ListAppender<>();
    private Logger rootLogger;
    private Statistics statistics;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE review_votes, reviews RESTART IDENTITY CASCADE");
        // 150 published reviews (ratings 1..5, 30 each) and 10 waiting for moderation, all of product 1
        jdbc.update("INSERT INTO reviews (product_id, customer_id, rating, title, body, status) "
                + "SELECT 1, 'c' || g, (g % 5) + 1, 'Title ' || g, 'Body of review number ' || g, 'APPROVED' FROM generate_series(1, 150) g");
        jdbc.update("INSERT INTO reviews (product_id, customer_id, rating, title, body, status) "
                + "SELECT 1, 'p' || g, 5, 'Pending ' || g, 'Body of pending review ' || g, 'PENDING' FROM generate_series(1, 10) g");
        // review 1: three helpful and two unhelpful votes; review 2: one helpful; review 3: one unhelpful
        jdbc.update("INSERT INTO review_votes (review_id, customer_id, helpful) VALUES "
                + "(1,'v1',true),(1,'v2',true),(1,'v3',true),(1,'v4',false),(1,'v5',false),(2,'v1',true),(3,'v1',false)");

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLog.start();
        handlerLogger.addAppender(handlerLog);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        everything.start();
        rootLogger.addAppender(everything);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(handlerLog);
        handlerLog.stop();
        handlerLog.list.clear();
        rootLogger.detachAppender(everything);
        everything.stop();
        everything.list.clear();
        pool.shutdownNow();
    }

    // ───────────────────────── D3: one page, oldest first ─────────────────────────

    @Test
    void productReviews_defaultToTheFirst100_andPagesCoverTheRest() throws Exception {
        JsonNode first = getJson("/api/v1/reviews/product/1");
        JsonNode second = getJson("/api/v1/reviews/product/1?page=1&size=100");
        JsonNode small = getJson("/api/v1/reviews/product/1?page=2&size=40");

        assertThat(first.size()).as("150 published reviews, a page holds at most 100").isEqualTo(100);
        assertThat(first.get(0).get("id").asLong()).isEqualTo(1);
        assertThat(first.get(99).get("id").asLong()).isEqualTo(100);
        assertThat(second.size()).isEqualTo(50);
        assertThat(second.get(0).get("id").asLong()).isEqualTo(101);
        assertThat(small.size()).isEqualTo(40);
        assertThat(small.get(0).get("id").asLong()).isEqualTo(81);
        for (JsonNode review : first) {
            assertThat(review.get("status").asString()).isEqualTo("APPROVED");
        }
    }

    @Test
    void oversizedOrNonsensePages_areClientErrors() throws Exception {
        for (String query : List.of("size=101", "size=0", "size=-5", "page=-1", "size=2147483647", "page=2147483647&size=100", "size=x")) {
            assertThat(get("/api/v1/reviews/product/1?" + query, "alice").statusCode()).as(query).isEqualTo(400);
            assertThat(get("/api/v1/reviews/customer/alice?" + query, "alice").statusCode()).as(query).isEqualTo(400);
        }
    }

    @Test
    void anAdminSeesEveryStatusOnTheProduct_pageByPage() throws Exception {
        HttpResponse<String> all = http.send(request("/api/v1/reviews/product/1?approvedOnly=false&page=1&size=100", "root", "ADMIN"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(json.readTree(all.body()).size()).as("160 reviews in total: the second page has 60").isEqualTo(60);
    }

    @Test
    void aCustomersListing_isFilteredByTheDatabase_soAPageIsAFullPage() throws Exception {
        jdbc.update("INSERT INTO reviews (product_id, customer_id, rating, title, body, status) "
                + "SELECT g + 1000, 'alice', 4, 'Alice ' || g, 'Alice wrote review number ' || g, CASE WHEN g % 2 = 0 THEN 'APPROVED' ELSE 'PENDING' END "
                + "FROM generate_series(1, 20) g");

        JsonNode asAlice = json.readTree(get("/api/v1/reviews/customer/alice?size=15", "alice").body());
        JsonNode asMallory = json.readTree(get("/api/v1/reviews/customer/alice?size=15", "mallory").body());
        JsonNode asAdmin = json.readTree(http.send(request("/api/v1/reviews/customer/alice?size=15", "root", "ADMIN"),
                HttpResponse.BodyHandlers.ofString()).body());

        assertThat(asAlice.size()).as("her own 20 reviews, first page").isEqualTo(15);
        assertThat(asAdmin.size()).isEqualTo(15);
        assertThat(asMallory.size()).as("the 10 published ones only").isEqualTo(10);
        for (JsonNode review : asMallory) {
            assertThat(review.get("status").asString()).isEqualTo("APPROVED");
        }
    }

    // ───────────────────────── queries behind a request ─────────────────────────

    @Test
    void aPageOf100Reviews_costsAConstantNumberOfQueries_notOnePerReview() throws Exception {
        statistics.clear();

        JsonNode page = getJson("/api/v1/reviews/product/1");

        assertThat(page.size()).isEqualTo(100);
        assertThat(statistics.getPrepareStatementCount())
                .as("one query for the page, one for all their vote counts (it was 1 + 100 lazy loads)")
                .isLessThanOrEqualTo(3);
    }

    @Test
    void voteCountsAreRightForEveryReviewOfThePage() throws Exception {
        JsonNode page = getJson("/api/v1/reviews/product/1");

        assertThat(page.get(0).get("helpfulVotes").asLong()).isEqualTo(3);
        assertThat(page.get(0).get("unhelpfulVotes").asLong()).isEqualTo(2);
        assertThat(page.get(1).get("helpfulVotes").asLong()).isEqualTo(1);
        assertThat(page.get(1).get("unhelpfulVotes").asLong()).isZero();
        assertThat(page.get(2).get("helpfulVotes").asLong()).isZero();
        assertThat(page.get(2).get("unhelpfulVotes").asLong()).isEqualTo(1);
        assertThat(page.get(3).get("helpfulVotes").asLong()).isZero();
        assertThat(getJson("/api/v1/reviews/1").get("helpfulVotes").asLong()).isEqualTo(3);
    }

    @Test
    void theSummaryOf160Reviews_isThreeQueriesAndFiveNumbers() throws Exception {
        statistics.clear();

        JsonNode summary = getJson("/api/v1/reviews/product/1/summary");

        assertThat(summary.get("averageRating").asDouble()).isEqualTo(3.0);
        assertThat(summary.get("approvedReviews").asLong()).isEqualTo(150);
        assertThat(summary.get("totalReviews").asLong()).isEqualTo(160);
        for (int rating = 1; rating <= 5; rating++) {
            assertThat(summary.get("ratingDistribution").get(String.valueOf(rating)).asLong()).as("rating %d", rating).isEqualTo(30);
        }
        assertThat(statistics.getPrepareStatementCount()).as("grouped counts, approved count, total count").isLessThanOrEqualTo(3);
    }

    @Test
    void anUnknownProduct_hasAnEmptySummary() throws Exception {
        JsonNode summary = getJson("/api/v1/reviews/product/424242/summary");

        assertThat(summary.get("averageRating").asDouble()).isZero();
        assertThat(summary.get("totalReviews").asLong()).isZero();
        assertThat(summary.get("ratingDistribution").size()).isEqualTo(5);
    }

    // ───────────────────────── D1: the connection pool ─────────────────────────

    @Test
    void reads_doNotUseOpenEntityManagerInView() {
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class))
                .as("a read outside a transaction would keep its connection until the response was written").isEmpty();
    }

    @Test
    void connectionWaitIsBoundedAt5s_notTheHikariDefaultOf30s() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(5000);
    }

    /**
     * pgjdbc has no socket timeout by default: a database that stops answering without closing the connection
     * (dropped packets, a frozen host, a failover without a reset) would hold the request thread for minutes, long
     * after Hikari's 5 s, which only bounds the wait for a connection.
     */
    @Test
    void theDriverBoundsConnectAndRead_soAFrozenDatabaseCannotHoldAThreadForMinutes() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        Properties driver = pool.getDataSourceProperties();
        assertThat(driver.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(driver.getProperty("socketTimeout")).isEqualTo("30");
        assertThat(driver.getProperty("tcpKeepAlive")).isEqualTo("true");
        // and the driver really applied it: JDBC reports the socket read timeout of the live connection
        try (Connection connection = pool.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(30_000);
        }
    }

    @Test
    void whenEveryConnectionIsTaken_requestsFailWith503Within5s_logOneLine_andRecover() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> taken = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                taken.add(hikari.getConnection());
            }

            int clients = 20;
            long start = System.nanoTime();
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                responses.add(pool.submit(() -> get("/api/v1/reviews/1", "alice")));
            }
            for (Future<HttpResponse<String>> response : responses) {
                HttpResponse<String> r = response.get(30, TimeUnit.SECONDS);
                assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
                assertThat(r.body()).contains("\"message\":\"Service temporarily unavailable\"").doesNotContain("Hikari");
            }

            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("5 s connection timeout, not 30 s")
                    .isBetween(Duration.ofMillis(4000), Duration.ofSeconds(12));
            assertThat(handlerLog.list).as("%d failed requests, bounded log output", clients).hasSizeLessThanOrEqualTo(1)
                    .allSatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getThrowableProxy()).isNull();
                        assertThat(e.getFormattedMessage()).as("the line names the reason, not just the wrapper").contains("request timed out");
                    });
            // Hibernate (two lines per failed JDBC call) and Spring's rollback-override trace are off: only our own line is left
            List<ILoggingEvent> loud = everything.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
            assertThat(loud).as("WARN/ERROR lines from every logger: %s", loud.stream().map(e -> e.getLoggerName() + ": " + e.getFormattedMessage()).toList())
                    .noneMatch(e -> e.getLoggerName().startsWith("org.hibernate"))
                    .noneMatch(e -> e.getLoggerName().equals("org.springframework.transaction.interceptor.TransactionInterceptor"))
                    .hasSizeLessThanOrEqualTo(2);
        } finally {
            for (Connection c : taken) {
                c.close();
            }
        }

        assertThat(get("/api/v1/reviews/1", "alice").statusCode()).as("recovers as soon as connections are free").isEqualTo(200);
    }

    // ───────────────────────── helpers ─────────────────────────

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path, "alice");
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String username) throws Exception {
        return http.send(request(path, username, "CUSTOMER"), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String username, String role) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET()
                .header("X-Username", username).header("X-User-Role", role).build();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
