package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two behaviors the authorization request must carry:
 *   - PKCE (S256) on EVERY request — interactive and silent (review F1).
 *   - prompt=none only when the ea_login_hint cookie is present with value "1"
 *     (the silent re-auth trigger; wrong value or absence = interactive flow).
 */
class SilentAuthRequestResolverTest {

    private static final String AUTH_URI = "/gui_epmmFormQuery/oauth2/authorization/keycloak";

    private SilentAuthRequestResolver resolver() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .clientId("test-client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/gui_epmmFormQuery/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("http://kc:9999/auth")
                .tokenUri("http://kc:9999/token")
                .build();
        return new SilentAuthRequestResolver(
                new InMemoryClientRegistrationRepository(reg),
                "/gui_epmmFormQuery", "ea_login_hint");
    }

    private MockHttpServletRequest authorizationRequest() {
        return new MockHttpServletRequest("GET", AUTH_URI);
    }

    @Test
    void interactiveRequestCarriesPkceS256AndNoPrompt() {
        OAuth2AuthorizationRequest req = resolver().resolve(authorizationRequest());

        assertThat(req).isNotNull();
        assertThat(req.getAdditionalParameters())
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256")
                .containsKey(PkceParameterNames.CODE_CHALLENGE)
                .doesNotContainKey("prompt");
        assertThat(req.getAttributes()).containsKey(PkceParameterNames.CODE_VERIFIER);
        assertThat(req.getAuthorizationRequestUri())
                .contains("code_challenge=")
                .contains("code_challenge_method=S256");
    }

    @Test
    void hintCookieAddsPromptNoneAndKeepsPkce() {
        MockHttpServletRequest request = authorizationRequest();
        request.setCookies(new Cookie("ea_login_hint", "1"));

        OAuth2AuthorizationRequest req = resolver().resolve(request);

        assertThat(req).isNotNull();
        assertThat(req.getAdditionalParameters())
                .containsEntry("prompt", "none")
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256")
                .containsKey(PkceParameterNames.CODE_CHALLENGE);
        // the customize() rebuild must not lose the verifier the token
        // exchange needs, nor drop PKCE from the redirect URL itself
        assertThat(req.getAttributes()).containsKey(PkceParameterNames.CODE_VERIFIER);
        assertThat(req.getAuthorizationRequestUri())
                .contains("prompt=none")
                .contains("code_challenge=");
    }

    @Test
    void wrongCookieValueStaysInteractive() {
        MockHttpServletRequest request = authorizationRequest();
        request.setCookies(new Cookie("ea_login_hint", "0"));

        OAuth2AuthorizationRequest req = resolver().resolve(request);

        assertThat(req).isNotNull();
        assertThat(req.getAdditionalParameters()).doesNotContainKey("prompt");
    }

    @Test
    void nonAuthorizationEndpointResolvesToNull() {
        OAuth2AuthorizationRequest req = resolver()
                .resolve(new MockHttpServletRequest("GET", "/gui_epmmFormQuery/web/"));

        assertThat(req).isNull();
    }
}
