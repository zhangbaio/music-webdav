package com.example.musicwebdav.common.logging;

import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_CLIENT_IP = "clientIp";
    private static final String MDC_METHOD = "method";
    private static final String MDC_URI = "uri";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        response.setHeader(HEADER_REQUEST_ID, requestId);
        String clientIp = resolveClientIp(request);

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CLIENT_IP, clientIp);
        MDC.put(MDC_METHOD, request.getMethod());
        MDC.put(MDC_URI, request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            int status = response.getStatus();
            log.info("ACCESS method={} uri={} status={} costMs={} ip={}",
                    request.getMethod(), request.getRequestURI(), status, cost, clientIp);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CLIENT_IP);
            MDC.remove(MDC_METHOD);
            MDC.remove(MDC_URI);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.trim().isEmpty()) {
            int commaIndex = xForwardedFor.indexOf(',');
            return commaIndex > 0 ? xForwardedFor.substring(0, commaIndex).trim() : xForwardedFor.trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.trim().isEmpty()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
