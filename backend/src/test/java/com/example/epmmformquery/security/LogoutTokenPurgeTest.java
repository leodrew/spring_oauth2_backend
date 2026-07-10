package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F16: logout must also purge the principal's tokens from the
 * authorized-client store — otherwise a still-valid access token lingers in
 * the JVM map and logged-out users' entries accumulate until pod restart.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogoutTokenPurgeTest {

    @Autowired MockMvc mockMvc;
    @Autowired OAuth2AuthorizedClientService authorizedClientService;
    @Autowired ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void logoutRemovesTheAuthorizedClient() throws Exception {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                Map.of("sub", "u-logout-test", "preferred_username", "leo"), "sub");
        OAuth2AuthenticationToken auth =
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");

        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(
                clientRegistrationRepository.findByRegistrationId("keycloak"),
                auth.getName(),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok",
                        Instant.now(), Instant.now().plusSeconds(300)));
        authorizedClientService.saveAuthorizedClient(client, auth);
        assertThat((OAuth2AuthorizedClient) authorizedClientService
                .loadAuthorizedClient("keycloak", "u-logout-test")).isNotNull();

        mockMvc.perform(get("/gui_epmmFormQuery/logout").with(authentication(auth)))
                .andExpect(status().is3xxRedirection());

        assertThat((OAuth2AuthorizedClient) authorizedClientService
                .loadAuthorizedClient("keycloak", "u-logout-test")).isNull();
    }
}
