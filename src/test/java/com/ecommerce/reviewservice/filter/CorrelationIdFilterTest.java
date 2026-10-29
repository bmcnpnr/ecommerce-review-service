package com.ecommerce.reviewservice.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesIncomingHeader_andExposesItInMdcDuringTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/1");
        request.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get("correlationId")));

        assertThat(seenInChain.get()).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void generatesUuidWhenHeaderMissingOrBlank() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), missing, new MockFilterChain());

        MockHttpServletRequest blankRequest = new MockHttpServletRequest("GET", "/x");
        blankRequest.addHeader("X-Correlation-Id", "");
        MockHttpServletResponse blank = new MockHttpServletResponse();
        filter.doFilter(blankRequest, blank, new MockFilterChain());

        assertThat(UUID.fromString(missing.getHeader("X-Correlation-Id"))).isNotNull();
        assertThat(UUID.fromString(blank.getHeader("X-Correlation-Id"))).isNotNull();
        assertThat(missing.getHeader("X-Correlation-Id")).isNotEqualTo(blank.getHeader("X-Correlation-Id"));
    }

    @Test
    void setsASingleResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Correlation-Id", "only-once");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setHeader("X-Other", "1"));

        assertThat(response.getHeaders("X-Correlation-Id")).containsExactly("only-once");
    }

    @Test
    void mdcIsClearedEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Correlation-Id", "boom-1");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("downstream failure");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get("correlationId")).isNull();
    }
}
