package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Inserted before AuthorizationFilter to perform LDAP-based privilege checks.
 * Set app.privilege.enabled=false to skip.
 *
 * FAIL CLOSED (review F17): the LDAP logic is not implemented yet. Until it
 * is, enabling the toggle DENIES every request with 503 instead of silently
 * allowing everything — flipping the flag must never create a false sense of
 * enforcement. Replace the denial with the real check when the logic lands.
 */
@Component
public class PrivilegeCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeCheckFilter.class);

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
        // TODO: insert the LDAP privilege check logic here, then allow/deny
        // based on its result instead of denying unconditionally.
        log.error("app.privilege.enabled=true but the LDAP privilege logic is not implemented; "
                + "denying request to {} (503).", request.getRequestURI());
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
}
