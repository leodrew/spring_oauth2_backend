package com.example.epmmformquery.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Updated from v4 baseline: now clears the "ea_login_hint" cookie before
 * redirecting to Keycloak's end_session_endpoint.
 *
 * Why both: explicit logout means "I want to fully log out". If we left the
 * hint cookie, the next visit would attempt silent re-auth with prompt=none,
 * which (assuming Keycloak SSO is dead because we just hit end_session) would
 * fail with login_required and bounce through the failure handler. Functional
 * but wasteful. Clearing the cookie makes the next login go straight to the
 * interactive flow as the user expects.
 */
@Component
public class CustomLogoutSuccessHandler implements LogoutSuccessHandler {

    private final String keycloakLogoutUrl;
    private final String postLogoutRedirectUri;
    private final String hintCookieName;

    public CustomLogoutSuccessHandler(
            @Value("${app.keycloak.logout-url}") String keycloakLogoutUrl,
            @Value("${app.post-logout-redirect-uri}") String postLogoutRedirectUri,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName) {
        this.keycloakLogoutUrl = keycloakLogoutUrl;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
        this.hintCookieName = hintCookieName;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication)
            throws IOException, ServletException {

        Cookie clear = new Cookie(hintCookieName, "");
        clear.setMaxAge(0);
        clear.setPath("/");
        clear.setHttpOnly(true);
        clear.setSecure(true);
        response.addCookie(clear);

        String redirect = keycloakLogoutUrl
                + "?post_logout_redirect_uri="
                + URLEncoder.encode(postLogoutRedirectUri, StandardCharsets.UTF_8);
        response.sendRedirect(redirect);
    }
}
