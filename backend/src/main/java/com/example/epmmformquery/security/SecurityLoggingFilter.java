package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * V4 component (kept as-is from your existing project).
 * Logs the resolved authentication state for each request.
 * Inserted after SecurityContextHolderFilter so the SecurityContext is loaded.
 */
@Component
public class SecurityLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (log.isDebugEnabled()) {
            log.debug("path={} auth={} authenticated={}",
                    request.getRequestURI(),
                    auth == null ? "null" : auth.getName(),
                    auth != null && auth.isAuthenticated());
        }
        chain.doFilter(request, response);
    }
}
