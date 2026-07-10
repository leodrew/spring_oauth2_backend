package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the refresh-failure classification of TokenRefreshFilter (spec §5.3 +
 * review F10): only a dead refresh token (invalid_grant) may kill the session;
 * every other failure keeps the user logged in and retries later.
 */
class TokenRefreshFilterTest {

    OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    TokenRefreshFilter filter = new TokenRefreshFilter(manager);

    MockHttpServletRequest request;
    MockHttpSession session;
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        request = new MockHttpServletRequest("GET", "/gui_epmmFormQuery/rs/gui/data");
        request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(oidcAuth());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OAuth2AuthenticationToken oidcAuth() {
        OidcIdToken idToken = new OidcIdToken("id-token",
                Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "u-1", "preferred_username", "leo"));
        DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "preferred_username");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
    }

    @Test
    void invalidGrantInvalidatesSessionAndClearsContext() throws Exception {
        when(manager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "refresh token revoked", null), "keycloak"));

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // request still proceeds — it re-enters the auth flow downstream
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void transientOAuthErrorKeepsSessionAndAuthentication() throws Exception {
        when(manager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("server_error", "kc 502", null), "keycloak"));

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void networkBlipKeepsSessionAndAuthentication() throws Exception {
        when(manager.authorize(any())).thenThrow(new IllegalStateException("connection reset"));

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void anonymousRequestPassesThroughUntouched() throws Exception {
        SecurityContextHolder.clearContext();

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isFalse();
        assertThat(chain.getRequest()).isNotNull();
    }
}
