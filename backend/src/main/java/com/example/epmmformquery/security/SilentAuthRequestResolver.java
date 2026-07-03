package com.example.epmmformquery.security;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Customises the OAuth2 authorization request that goes to Keycloak.
 *
 * If the browser carries the "ea_login_hint" cookie (set after a previous
 * successful login by LoginSuccessHandler), append prompt=none so Keycloak
 * attempts SSO without showing UI:
 *   - SSO cookie valid  → 302 back with ?code=...
 *   - SSO cookie absent → 302 back with ?error=login_required
 *                         (handled by SilentAuthFailureHandler, which
 *                         clears the hint cookie and retries interactively)
 *
 * Without the hint cookie (first-time visit, or after explicit logout), the
 * request is left untouched and the normal interactive flow runs.
 */
@Component
public class SilentAuthRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;
    private final String hintCookieName;

    public SilentAuthRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${app.context-prefix:}") String contextPrefix,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName) {

        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                contextPrefix + "/oauth2/authorization");
        this.hintCookieName = hintCookieName;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(delegate.resolve(request, registrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req, HttpServletRequest request) {
        if (req == null || !hasHintCookie(request)) {
            return req;
        }
        Map<String, Object> extra = new HashMap<>(req.getAdditionalParameters());
        extra.put("prompt", "none");
        return OAuth2AuthorizationRequest.from(req)
                .additionalParameters(extra)
                .build();
    }

    private boolean hasHintCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if (hintCookieName.equals(c.getName()) && "1".equals(c.getValue())) {
                return true;
            }
        }
        return false;
    }
}
