package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakLogoutSuccessHandlerTest {

    private static final String END_SESSION = "http://kc:9999/realms/test/protocol/openid-connect/logout";

    private KeycloakLogoutSuccessHandler handler() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .clientId("test-client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/gui_epmmFormQuery/login/oauth2/code/{registrationId}")
                .authorizationUri("http://kc:9999/auth")
                .tokenUri("http://kc:9999/token")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", END_SESSION))
                .build();
        return new KeycloakLogoutSuccessHandler(
                new InMemoryClientRegistrationRepository(reg),
                "http://app/gui_epmmFormQuery/page/logged-out",
                "ea_login_hint", "/gui_epmmFormQuery", true);
    }

    private OAuth2AuthenticationToken oidcAuth() {
        OidcIdToken idToken = new OidcIdToken("the-id-token-value",
                Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "u-1", "preferred_username", "leo"));
        DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "preferred_username");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
    }

    @Test
    void redirectsToEndSessionWithIdTokenHint() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler().onLogoutSuccess(req, res, oidcAuth());

        String location = res.getRedirectedUrl();
        assertThat(location).startsWith(END_SESSION);
        assertThat(location).contains("id_token_hint=the-id-token-value");
        assertThat(location).contains("post_logout_redirect_uri=");
    }

    @Test
    void clearsHintCookieScopedToContextPrefix() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler().onLogoutSuccess(new MockHttpServletRequest(), res, oidcAuth());

        Cookie c = res.getCookie("ea_login_hint");
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isZero();
        assertThat(c.getPath()).isEqualTo("/gui_epmmFormQuery");
        assertThat(c.getSecure()).isTrue();
    }
}
