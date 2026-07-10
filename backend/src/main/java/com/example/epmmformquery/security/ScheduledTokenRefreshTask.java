package com.example.epmmformquery.security;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes access tokens for all logged-in users whose tokens
 * are about to expire. Catches the case where a user is logged in but idle —
 * no requests = neither TokenRefreshFilter nor reactive refresh fires, so
 * without this task the next user action would briefly stutter while the
 * just-expired token is refreshed.
 *
 * Iterates SessionRegistry's principals (populated because SecurityConfig
 * sets sessionManagement().maximumSessions(-1).sessionRegistry(...)).
 *
 * Disable via app.token-refresh.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "app.token-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTokenRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTokenRefreshTask.class);
    private static final String REGISTRATION_ID = "keycloak";

    private final SessionRegistry sessionRegistry;
    private final OAuth2AuthorizedClientService clientService;
    private final OAuth2AuthorizedClientManager clientManager;

    @Value("${app.token-refresh.skew-seconds:60}")
    private long skewSeconds;

    public ScheduledTokenRefreshTask(SessionRegistry sessionRegistry,
                                     OAuth2AuthorizedClientService clientService,
                                     OAuth2AuthorizedClientManager clientManager) {
        this.sessionRegistry = sessionRegistry;
        this.clientService = clientService;
        this.clientManager = clientManager;
    }

    @Scheduled(fixedRateString = "${app.token-refresh.schedule-rate-ms:60000}")
    public void refreshExpiringTokens() {
        int checked = 0, refreshed = 0, failed = 0;

        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof OAuth2User user)) {
                continue;
            }
            // Skip if no live (non-expired) sessions for this principal
            if (sessionRegistry.getAllSessions(principal, false).isEmpty()) {
                continue;
            }

            String name = user.getName();
            OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(REGISTRATION_ID, name);
            if (client == null || client.getAccessToken() == null) {
                continue;
            }
            checked++;

            Instant expiresAt = client.getAccessToken().getExpiresAt();
            if (expiresAt == null) {
                continue;
            }

            // Skip if not yet within skew window
            if (Instant.now().plus(Duration.ofSeconds(skewSeconds)).isBefore(expiresAt)) {
                continue;
            }

            try {
                Authentication synthetic = new UsernamePasswordAuthenticationToken(
                        name, null, user.getAuthorities());

                OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
                        .withAuthorizedClient(client)
                        .principal(synthetic)
                        .build();

                OAuth2AuthorizedClient updated = clientManager.authorize(req);
                if (updated != null) {
                    refreshed++;
                    log.debug("Background-refreshed token for {}", name);
                }
            } catch (ClientAuthorizationException ex) {
                failed++;
                if (OAuth2ErrorCodes.INVALID_GRANT.equals(ex.getError().getErrorCode())) {
                    log.warn("Refresh token invalid for {} (invalid_grant); removing authorized client and expiring sessions — user will silently re-auth.", name);
                    clientService.removeAuthorizedClient(REGISTRATION_ID, name);
                    // F10: the IdP has revoked — don't leave an authenticated
                    // zombie session behind for up to 8h. Expiring here means
                    // revocation propagates in <= one tick (~2 min) and is the
                    // compensating control for skipping back-channel logout (D2).
                    sessionRegistry.getAllSessions(principal, false)
                            .forEach(SessionInformation::expireNow);
                } else {
                    log.warn("OAuth2 error refreshing for {} ({}); keeping tokens for retry next tick.",
                            name, ex.getError().getErrorCode());
                }
            } catch (Exception ex) {
                failed++;
                log.warn("Transient failure refreshing for {}: {}; keeping tokens for retry next tick.",
                        name, ex.getMessage());
            }
        }

        if (checked > 0) {
            log.info("Token refresh scan: checked={} refreshed={} failed={}", checked, refreshed, failed);
        }
    }
}
