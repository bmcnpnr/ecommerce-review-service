package com.ecommerce.reviewservice.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(1024);

    /** A request that does not declare its length, like a chunked upload. */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        request.addHeader("Transfer-Encoding", "chunked");
        request.setCharacterEncoding("UTF-8");
        return request;
    }

    @Test
    void aDeclaredLengthAboveTheLimit_isRefusedWith413_andNothingIsRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"status\":413").contains("Request body too large");
        assertThat(chain.getRequest()).as("the handler never saw it").isNull();
    }

    @Test
    void aRequestWithoutABody_isNotWrapped() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/things");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(get);
    }

    @Test
    void the413_carriesTheSameHardeningHeadersAsEveryOtherApiResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void aDeclaredLengthWithinTheLimit_passesThroughUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things");
        request.setContent(new byte[1024]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aBodyWithoutADeclaredLength_isCutOffOnceItPassesTheLimit() throws Exception {
        MockHttpServletRequest request = chunked(new byte[5000]);
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicReference<Long> read = new AtomicReference<>(0L);
        FilterChain chain = (req, res) -> {
            try (var in = ((HttpServletRequest) req).getInputStream()) {
                byte[] buffer = new byte[256];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    final int chunk = n;
                    read.updateAndGet(v -> v + chunk);
                }
            } catch (IOException e) {
                failure.set(e);
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(failure.get()).hasMessageContaining("exceeds 1024 bytes");
        assertThat(read.get()).as("it stopped soon after the limit, not at 5000").isLessThanOrEqualTo(1024 + 256);
    }

    @Test
    void aBodyWithoutADeclaredLength_withinTheLimit_isReadInFull_asStreamAndAsReader() throws Exception {
        byte[] body = "{\"delta\": -1, \"note\": \"héllo\"}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> viaStream = new AtomicReference<>();
        AtomicReference<String> viaReader = new AtomicReference<>();

        filter.doFilter(chunked(body), new MockHttpServletResponse(), (req, res) -> viaStream.set(req.getInputStream().readAllBytes()));
        filter.doFilter(chunked(body), new MockHttpServletResponse(), (req, res) -> {
            try (BufferedReader reader = req.getReader()) {
                viaReader.set(reader.readLine());
            }
        });

        assertThat(viaStream.get()).isEqualTo(body);
        assertThat(viaReader.get()).isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void theLimitCanBeRaised() {
        assertThatThrownBy(() -> new RequestSizeLimitFilter(1024).doFilter(chunked(new byte[2000]), new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes())).isInstanceOf(IOException.class);

        MockFilterChain chain = new MockFilterChain();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> new RequestSizeLimitFilter(4096)
                .doFilter(chunked(new byte[2000]), new MockHttpServletResponse(), (req, res) -> req.getInputStream().readAllBytes()));
        assertThat(chain.getRequest()).isNull();
    }
}
