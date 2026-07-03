package com.example.epmmformquery.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * V4 component (kept as-is from your existing project).
 * Adds a per-request traceId to MDC so all downstream logs can be correlated.
 *
 * Registered with HIGHEST_PRECEDENCE in SecurityConfig.requestTracingFilter()
 * so it runs before the security chain.
 */
public class RequestTracingFilter implements Filter {

    private static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
