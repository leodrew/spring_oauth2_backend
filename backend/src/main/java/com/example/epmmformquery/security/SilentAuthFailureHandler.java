package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Catches OAuth2 errors returned by Keycloak. The two we react to:
 *
 *   login_required        - Keycloak SSO cookie was absent/expired and
 *                           prompt=none was set. Clear the hint cookie
 *                           (CRITICAL: prevents an infinite redirect loop)
 *                           and redirect to interactive flow.
 *   interaction_required  - Same intent, less common (account linking etc).
 *
 * Anything else delegates to Spring's default handler, which renders an
 * error page or reuses the configured failureUrl.
 */
@Component
public class SilentAuthFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SilentAuthFailureHandler.class);

    private final AuthenticationFailureHandler defaultHandler =
            new SimpleUrlAuthenticationFailureHandler();
    private final String contextPrefix;
    private final String hintCookieName;
    private final boolean cookieSecure;

    public SilentAuthFailureHandler(
            @Value("${app.context-prefix:}") String contextPrefix,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName,
            @Value("${app.silent-auth.cookie-secure:true}") boolean cookieSecure) {
        this.contextPrefix = contextPrefix;
        this.hintCookieName = hintCookieName;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {

        if (exception instanceof OAuth2AuthenticationException oauthEx) {
            String code = oauthEx.getError().getErrorCode();
            if ("login_required".equals(code) || "interaction_required".equals(code)) {
                log.info("Silent auth failed ({}); falling back to interactive login", code);
                clearHintCookie(response);
                response.sendRedirect(contextPrefix + "/oauth2/authorization/keycloak");
                return;
            }
        }
        defaultHandler.onAuthenticationFailure(request, response, exception);
    }

    private void clearHintCookie(HttpServletResponse response) {
        Cookie c = new Cookie(hintCookieName, "");
        c.setMaxAge(0);
        c.setPath(contextPrefix);
        c.setHttpOnly(true);
        c.setSecure(cookieSecure);
        c.setAttribute("SameSite", "Lax");
        response.addCookie(c);
    }
}
