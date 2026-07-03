package com.example.epmmformquery.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.epmmformquery.model.UserInfo;
import com.example.epmmformquery.security.UserInfoService;

/**
 * Demonstrates the typical pattern for calling a downstream API:
 *
 *   1. Get the current user's info from UserInfoService.
 *   2. Make the WebClient call. Authorization header is injected
 *      AUTOMATICALLY by ServletOAuth2AuthorizedClientExchangeFilterFunction —
 *      you don't add it yourself.
 *   3. Pass user-identifying info via business headers (X-Acting-User)
 *      or query parameters as the downstream API requires.
 *
 * What you should NOT do:
 *   - Manually call .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
 *     — the WebClient is already configured to do this.
 *   - Read the access token in your controller and try to attach it
 *     yourself — adds bugs (stale tokens, missing refresh) and bypasses
 *     all the work TokenRefreshConfig set up.
 */
@Component
public class DownstreamApiClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamApiClient.class);

    private final WebClient downstreamWebClient;
    private final UserInfoService userInfoService;

    public DownstreamApiClient(@Qualifier("downstreamWebClient") WebClient downstreamWebClient,
                               UserInfoService userInfoService) {
        this.downstreamWebClient = downstreamWebClient;
        this.userInfoService = userInfoService;
    }

    /**
     * Calls GET /internal/profile on the downstream service.
     * Authorization header is added automatically. We also pass
     * X-Acting-User as a business-level identifier in case the
     * downstream service logs by username rather than by sub.
     */
    public String fetchProfile() {
        UserInfo me = userInfoService.currentOrThrow();
        log.debug("Fetching profile for {} (sub={})", me.username(), me.subject());

        return downstreamWebClient
                .get()
                .uri("/internal/profile")
                .header("X-Acting-User", me.username())
                .retrieve()
                .bodyToMono(String.class)
                .block();   // .block() OK in servlet context — see WebClient notes in markdown
    }

    /**
     * Example of a write call. Same auth pattern.
     */
    public void recordEvent(String eventType, String payload) {
        UserInfo me = userInfoService.currentOrThrow();

        downstreamWebClient
                .post()
                .uri("/internal/events")
                .header("X-Acting-User", me.username())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"type\":\"" + eventType + "\",\"payload\":" + payload + "}")
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
