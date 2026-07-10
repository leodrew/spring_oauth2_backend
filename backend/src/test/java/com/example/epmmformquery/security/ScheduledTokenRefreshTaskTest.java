package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScheduledTokenRefreshTaskTest {

    SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    OAuth2AuthorizedClientService clientService = mock(OAuth2AuthorizedClientService.class);
    OAuth2AuthorizedClientManager clientManager = mock(OAuth2AuthorizedClientManager.class);

    ScheduledTokenRefreshTask task;
    OAuth2User user;
    OAuth2AuthorizedClient expiringClient;
    SessionInformation liveSession;

    @BeforeEach
    void setUp() {
        task = new ScheduledTokenRefreshTask(sessionRegistry, clientService, clientManager);
        ReflectionTestUtils.setField(task, "skewSeconds", 60L);

        user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_user")),
                Map.of("preferred_username", "leo"), "preferred_username");

        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .clientId("c").clientSecret("s")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/x").authorizationUri("http://kc/a").tokenUri("http://kc/t")
                .build();
        expiringClient = new OAuth2AuthorizedClient(reg, "leo",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok",
                        Instant.now().minusSeconds(600), Instant.now().plusSeconds(10)));

        liveSession = mock(SessionInformation.class);
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(user));
        when(sessionRegistry.getAllSessions(user, false))
                .thenReturn(List.of(liveSession));
        when(clientService.loadAuthorizedClient("keycloak", "leo")).thenReturn(expiringClient);
    }

    @Test
    void invalidGrantRemovesAuthorizedClient() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "refresh token expired", null), "keycloak"));

        task.refreshExpiringTokens();

        verify(clientService).removeAuthorizedClient("keycloak", "leo");
    }

    @Test
    void invalidGrantExpiresTheUsersSessions() {
        // F10: IdP-side revocation must not leave an authenticated zombie
        // session behind — this is the compensating control for D2.
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "refresh token expired", null), "keycloak"));

        task.refreshExpiringTokens();

        verify(liveSession).expireNow();
    }

    @Test
    void transientOAuthErrorKeepsSessionsAlive() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("server_error", "kc 502", null), "keycloak"));

        task.refreshExpiringTokens();

        verify(liveSession, never()).expireNow();
    }

    @Test
    void transientOAuthErrorKeepsTokens() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("server_error", "kc 502", null), "keycloak"));

        task.refreshExpiringTokens();

        verify(clientService, never()).removeAuthorizedClient(any(), any());
    }

    @Test
    void networkBlipKeepsTokens() {
        when(clientManager.authorize(any()))
                .thenThrow(new IllegalStateException("connection reset"));

        task.refreshExpiringTokens();

        verify(clientService, never()).removeAuthorizedClient(any(), any());
    }
}
