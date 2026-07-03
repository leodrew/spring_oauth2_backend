package com.example.epmmformquery.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.epmmformquery.model.UserInfo;
import com.example.epmmformquery.security.UserInfoService;

/**
 * Pattern for 3rd-party API calls. Same access token is reused —
 * the 3rd party trusts Keycloak as the issuer.
 *
 * If the 3rd party requires a DIFFERENT scope or audience, the cleaner
 * approach is to register a separate ClientRegistration for it in
 * application.yml (e.g. registration.partner-api), give it a separate
 * scope, and inject a third WebClient configured with that registration
 * id. That keeps tokens for the partner separate from your downstream
 * token, which means a leak of one doesn't affect the other.
 */
@Component
public class ThirdPartyApiClient {

    private static final Logger log = LoggerFactory.getLogger(ThirdPartyApiClient.class);

    private final WebClient thirdPartyWebClient;
    private final UserInfoService userInfoService;

    public ThirdPartyApiClient(@Qualifier("thirdPartyWebClient") WebClient thirdPartyWebClient,
                               UserInfoService userInfoService) {
        this.thirdPartyWebClient = thirdPartyWebClient;
        this.userInfoService = userInfoService;
    }

    public String fetchPartnerData(String resourceId) {
        UserInfo me = userInfoService.currentOrThrow();
        log.debug("Calling partner API on behalf of {}", me.username());

        return thirdPartyWebClient
                .get()
                .uri("/v1/resources/{id}", resourceId)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
