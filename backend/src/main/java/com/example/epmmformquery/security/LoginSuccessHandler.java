package com.example.epmmformquery.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Updated from v4 baseline: now sets the "ea_login_hint" cookie after a
 * successful login. Next time this browser visits without a session, the
 * SilentAuthRequestResolver will see the cookie and add prompt=none to the
 * authorization request, triggering Scenario B silent re-auth.
 *
 * Cookie is HttpOnly + Secure to mitigate XSS / CSRF amplification.
 * Value is just "1" — we don't need to store anything sensitive; the
 * presence of the cookie is the signal.
 */
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final String hintCookieName;
    private final int hintCookieMaxAge;
    @SuppressWarnings("unused")  // retained for future per-user logic (e.g. token introspection)
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;

    public LoginSuccessHandler(RequestCache requestCache,
                               String defaultTargetUrl,
                               OAuth2AuthorizedClientRepository authorizedClientRepository,
                               String hintCookieName,
                               int hintCookieMaxAge) {
        super.setRequestCache(requestCache);
        super.setDefaultTargetUrl(defaultTargetUrl);
        this.authorizedClientRepository = authorizedClientRepository;
        this.hintCookieName = hintCookieName;
        this.hintCookieMaxAge = hintCookieMaxAge;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws ServletException, IOException {

        Cookie hint = new Cookie(hintCookieName, "1");
        hint.setMaxAge(hintCookieMaxAge);
        hint.setPath("/");
        hint.setHttpOnly(true);
        hint.setSecure(true);
        response.addCookie(hint);

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
