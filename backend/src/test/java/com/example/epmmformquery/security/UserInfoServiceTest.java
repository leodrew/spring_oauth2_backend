package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import com.example.epmmformquery.model.UserInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F15: the principal name is the stable OIDC sub (token-store key), but the
 * business-facing username must remain the preferred_username claim — the
 * X-Acting-User header and SPA display depend on it.
 */
class UserInfoServiceTest {

    UserInfoService service = new UserInfoService(mock(OAuth2AuthorizedClientService.class));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OAuth2AuthenticationToken subKeyedAuth() {
        OidcIdToken idToken = new OidcIdToken("id-token",
                Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "u-1",
                       "preferred_username", "leo",
                       "email", "leo@example.com",
                       "realm_access", Map.of("roles", List.of("epmm-user"))));
        // name attribute = sub, mirroring user-name-attribute: sub in yml
        DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
    }

    @Test
    void usernameIsPreferredUsernameWhilePrincipalNameIsSub() {
        SecurityContextHolder.getContext().setAuthentication(subKeyedAuth());

        UserInfo me = service.currentOrThrow();

        assertThat(me.username()).isEqualTo("leo");
        assertThat(me.subject()).isEqualTo("u-1");
        assertThat(me.email()).isEqualTo("leo@example.com");
        assertThat(me.roles()).contains("epmm-user");
    }

    @Test
    void usernameFallsBackToPrincipalNameWithoutTheClaim() {
        OidcIdToken idToken = new OidcIdToken("id-token",
                Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "u-2"));
        DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "sub");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));

        assertThat(service.currentOrThrow().username()).isEqualTo("u-2");
    }

    @Test
    void unauthenticatedIsEmpty() {
        assertThat(service.current()).isEmpty();
    }
}
