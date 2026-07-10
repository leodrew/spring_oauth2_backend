package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE infinite-redirect-loop guard (docs/auth-workflow.md §2B, review F12):
 * when Keycloak answers a prompt=none attempt with login_required, the hint
 * cookie MUST be cleared before retrying interactively — otherwise the next
 * authorization request carries prompt=none again, fails again, forever.
 */
class SilentAuthFailureHandlerTest {

    private final SilentAuthFailureHandler handler =
            new SilentAuthFailureHandler("/gui_epmmFormQuery", "ea_login_hint", true);

    private static OAuth2AuthenticationException oauthError(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, "from keycloak", null));
    }

    @Test
    void loginRequiredClearsHintCookieAndRetriesInteractively() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), res,
                oauthError("login_required"));

        Cookie cleared = res.getCookie("ea_login_hint");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.getPath()).isEqualTo("/gui_epmmFormQuery");
        assertThat(cleared.getSecure()).isTrue();
        assertThat(cleared.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(res.getRedirectedUrl())
                .isEqualTo("/gui_epmmFormQuery/oauth2/authorization/keycloak");
    }

    @Test
    void interactionRequiredBehavesLikeLoginRequired() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), res,
                oauthError("interaction_required"));

        assertThat(res.getCookie("ea_login_hint")).isNotNull();
        assertThat(res.getRedirectedUrl())
                .isEqualTo("/gui_epmmFormQuery/oauth2/authorization/keycloak");
    }

    @Test
    void otherOAuthErrorsDelegateToDefaultHandler() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), res,
                oauthError("invalid_request"));

        // default SimpleUrlAuthenticationFailureHandler (no failureUrl) → 401,
        // and crucially: the hint cookie is untouched
        assertThat(res.getCookie("ea_login_hint")).isNull();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getRedirectedUrl()).isNull();
    }
}
