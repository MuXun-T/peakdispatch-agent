package com.peakdispatch.api.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = acceptedOrGenerated(request.getHeader("X-Request-Id"));
        String traceId = UUID.randomUUID().toString();
        String previousRequestId = MDC.get("requestId");
        String previousTraceId = MDC.get("traceId");
        MDC.put("requestId", requestId);
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            restore("requestId", previousRequestId);
            restore("traceId", previousTraceId);
        }
    }

    private static String acceptedOrGenerated(String value) {
        return value != null && SAFE_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
