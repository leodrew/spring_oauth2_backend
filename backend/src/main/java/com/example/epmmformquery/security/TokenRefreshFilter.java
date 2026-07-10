package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Runs after SecurityContextHolderFilter on every request. If the user is
 * OAuth2-authenticated, asks the AuthorizedClientManager to ensure the access
 * token is fresh. The manager is a no-op when the token is still valid; when
 * within the configured clock skew, it transparently calls Keycloak's token
 * endpoint with the refresh_token grant.
 *
 * NOTE: We use AuthorizedClientServiceOAuth2AuthorizedClientManager (configured
 * in TokenRefreshConfig), which per Spring Security docs is "designed to be
 * used outside the context of a HttpServletRequest." HttpServletRequest is
 * therefore NOT passed as a context attribute — that manager doesn't use it.
 *
 * Failure handling (spec §5.3 + review F10):
 *   - invalid_grant (refresh token dead — revocation, SSO Session Max): the
 *     current session is invalidated and the SecurityContext cleared, so the
 *     request re-enters the auth flow — silent re-auth while Keycloak SSO is
 *     alive, 401 for /rs/** XHRs (F2 entry point).
 *   - anything else (network blip, Keycloak 5xx): log and let the request
 *     proceed with the existing (possibly stale) token; retry next request.
 */
public class TokenRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshFilter.class);

    private final OAuth2AuthorizedClientManager manager;

    public TokenRefreshFilter(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof OAuth2AuthenticationToken oauth) {
            try {
                OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                        .withClientRegistrationId(oauth.getAuthorizedClientRegistrationId())
                        .principal(oauth)
                        .build();

                OAuth2AuthorizedClient client = manager.authorize(authorizeRequest);
                if (client != null && log.isTraceEnabled()) {
                    log.trace("Token state for {}: expires_at={}",
                            oauth.getName(), client.getAccessToken().getExpiresAt());
                }
            } catch (ClientAuthorizationException ex) {
                if (OAuth2ErrorCodes.INVALID_GRANT.equals(ex.getError().getErrorCode())) {
                    // F10: the refresh token is dead (IdP-side revocation or
                    // SSO Session Max). Don't let this session live on as an
                    // authenticated zombie — invalidate it and let the request
                    // re-enter the auth flow: silent re-auth for pages, 401 for
                    // /rs/** (F2 entry point). The manager's failure handler
                    // has already removed the stored authorized client.
                    log.warn("Refresh token dead for {} (invalid_grant); invalidating session for re-auth.",
                            oauth.getName());
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    SecurityContextHolder.clearContext();
                } else {
                    log.warn("Token refresh failed for {} ({}); keeping session, will retry.",
                            oauth.getName(), ex.getError().getErrorCode());
                }
            } catch (Exception ex) {
                log.warn("Token refresh failed for {}: {}", oauth.getName(), ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
