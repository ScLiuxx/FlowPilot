package com.flowpilot.common.config;

import com.flowpilot.common.result.RequestIdHolder;
import com.flowpilot.common.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a request id and a trace id to every incoming request.
 *
 * <p>The trace id is reused by the execution that the request starts, so a task can be followed
 * from the console down to the individual node.
 */
@Slf4j
@Component
@Order(1)
public class TraceContextFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req-" + UUID.randomUUID();
        }
        RequestIdHolder.set(requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || !traceId.matches("^[0-9a-f]{32}$")) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.setTraceId(traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdHolder.clear();
            TraceContext.clear();
        }
    }
}
