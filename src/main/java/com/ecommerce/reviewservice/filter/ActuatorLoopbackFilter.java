package com.ecommerce.reviewservice.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;

/**
 * S-6: {@code /actuator/serviceregistry} can take the instance out of service, so only the
 * Kubernetes {@code preStop} hook (which calls it on localhost) may use it. Any other
 * remote address gets 403.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ActuatorLoopbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (uri.contains("/actuator/serviceregistry") && !isLoopback(request.getRemoteAddr())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
