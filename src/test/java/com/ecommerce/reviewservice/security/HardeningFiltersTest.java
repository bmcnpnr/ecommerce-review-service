package com.ecommerce.reviewservice.security;

import com.ecommerce.reviewservice.filter.ActuatorLoopbackFilter;
import com.ecommerce.reviewservice.filter.CorrelationIdFilter;
import com.ecommerce.reviewservice.filter.SecurityHeadersFilter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security")
class HardeningFiltersTest {

    @Test
    void apiResponsesGetNosniffAndNoStore() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/users/me"), response, (req, res) -> { });

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void invalidCorrelationIds_areReplaced_andNeverEchoed() throws Exception {
        for (String bad : new String[]{"a".repeat(65), "has space", "x\r\nInjected: 1", "<script>", "a/b"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            request.addHeader("X-Correlation-Id", bad);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new CorrelationIdFilter().doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader("X-Correlation-Id")).isNotEqualTo(bad)
                    .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }
        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/x");
        ok.addHeader("X-Correlation-Id", "a".repeat(64));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CorrelationIdFilter().doFilter(ok, response, (req, res) -> { });
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("a".repeat(64));
    }

    @Test
    void serviceRegistryIsLoopbackOnly() throws Exception {
        for (String remote : new String[]{"127.0.0.1", "::1", "0:0:0:0:0:0:0:1"}) {
            assertThat(callServiceRegistry(remote)).as(remote).isEqualTo(200);
        }
        for (String remote : new String[]{"10.1.2.3", "192.168.0.5", "172.17.0.1", "8.8.8.8"}) {
            assertThat(callServiceRegistry(remote)).as(remote).isEqualTo(403);
        }
    }

    @Test
    void otherActuatorPathsAreNotAffected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("10.1.2.3");
        AtomicBoolean reached = new AtomicBoolean();

        new ActuatorLoopbackFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
    }

    private int callServiceRegistry(String remote) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/serviceregistry");
        request.setRemoteAddr(remote);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ActuatorLoopbackFilter().doFilter(request, response, (req, res) -> { });
        return response.getStatus();
    }
}
