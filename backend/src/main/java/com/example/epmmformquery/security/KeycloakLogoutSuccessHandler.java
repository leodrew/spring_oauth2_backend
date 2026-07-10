package com.example.epmmformquery.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RP-initiated logout done right: delegates end_session URL construction to
 * Spring's OidcClientInitiatedLogoutSuccessHandler, which appends
 * id_token_hint + client_id so Keycloak >=18 skips its logout-confirmation
 * page and honours post_logout_redirect_uri. Also clears the silent-auth
 * hint cookie (scoped to the context prefix) so the next visit is
 * interactive, as the user expects after an explicit logout.
 */
@Component
public class KeycloakLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

    private final String hintCookieName;
    private final String cookiePath;
    private final boolean cookieSecure;

    public KeycloakLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${app.post-logout-redirect-uri}") String postLogoutRedirectUri,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName,
            @Value("${app.context-prefix:/}") String cookiePath,
            @Value("${app.silent-auth.cookie-secure:true}") boolean cookieSecure) {
        super(clientRegistrationRepository);
        setPostLogoutRedirectUri(postLogoutRedirectUri);
        this.hintCookieName = hintCookieName;
        this.cookiePath = cookiePath;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication)
            throws IOException, ServletException {
        Cookie clear = new Cookie(hintCookieName, "");
        clear.setMaxAge(0);
        clear.setPath(cookiePath);
        clear.setHttpOnly(true);
        clear.setSecure(cookieSecure);
        clear.setAttribute("SameSite", "Lax");
        response.addCookie(clear);

        super.onLogoutSuccess(request, response, authentication);
    }
}
