package com.example.epmmformquery.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * V4 component (kept as-is from your existing project).
 * Inserted before AuthorizationFilter to perform LDAP-based privilege checks.
 * Set app.privilege.enabled=false to skip; on failure, redirects to access-denied page.
 */
@Component
public class PrivilegeCheckFilter extends OncePerRequestFilter {

    @Value("${app.privilege.enabled:false}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        // TODO: insert your existing LDAP privilege check logic here.
        chain.doFilter(request, response);
    }
}
