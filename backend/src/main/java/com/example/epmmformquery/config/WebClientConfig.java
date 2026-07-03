package com.example.epmmformquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Two WebClient beans, both wired through the same OAuth2AuthorizedClientManager
 * so they share token storage and refresh logic.
 *
 *   downstreamWebClient  - calls a Resource Server YOU own (validates JWT itself)
 *   thirdPartyWebClient  - calls a 3rd-party API that requires Keycloak's access token
 *
 * Both inject "Authorization: Bearer <access_token>" automatically. Both will
 * trigger a refresh-token grant transparently when the token is within the skew
 * window — this is the same mechanism TokenRefreshFilter uses for the page flow.
 *
 * Token retrieval happens at request time, not bean creation time, so a token
 * refreshed by the scheduler 30 seconds ago will be picked up here.
 *
 * Why two clients instead of one: base URLs and timeouts often differ between
 * your own services and external partners. The second client also typically
 * needs different scope or even a different ClientRegistration if the 3rd-party
 * API requires different audience claims. Keeping them separate makes those
 * differences explicit and prevents one team's URL change from affecting the
 * other client's calls.
 */
@Configuration
public class WebClientConfig {

    public static final String CLIENT_REGISTRATION_ID = "keycloak";

    @Value("${app.downstream.base-url:}")
    private String downstreamBaseUrl;

    @Value("${app.third-party.base-url:}")
    private String thirdPartyBaseUrl;

    /**
     * For calling internal resource servers. The exchange filter pulls the
     * access_token from OAuth2AuthorizedClientManager — same manager that
     * TokenRefreshFilter and ScheduledTokenRefreshTask use, so all three
     * see the same token state.
     */
    @Bean
    public WebClient downstreamWebClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId(CLIENT_REGISTRATION_ID);

        WebClient.Builder builder = WebClient.builder().apply(oauth2.oauth2Configuration());
        if (downstreamBaseUrl != null && !downstreamBaseUrl.isEmpty()) {
            builder.baseUrl(downstreamBaseUrl);
        }
        return builder.build();
    }

    /**
     * For calling 3rd-party APIs that accept Keycloak access tokens.
     * Same access token is reused (same registrationId). If the 3rd party
     * requires a different audience or scope, register a separate
     * ClientRegistration in application.yml and call
     * setDefaultClientRegistrationId("third-party") here instead.
     */
    @Bean
    public WebClient thirdPartyWebClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId(CLIENT_REGISTRATION_ID);

        WebClient.Builder builder = WebClient.builder().apply(oauth2.oauth2Configuration());
        if (thirdPartyBaseUrl != null && !thirdPartyBaseUrl.isEmpty()) {
            builder.baseUrl(thirdPartyBaseUrl);
        }
        return builder.build();
    }
}
