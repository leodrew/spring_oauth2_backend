package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
 * Failures here are NOT fatal — we log and let the request proceed. If the
 * refresh token is truly dead, downstream API calls will get 401 and the SPA
 * can react (typically by redirecting to /oauth2/authorization/keycloak,
 * which kicks off Scenario B silent re-auth).
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
            } catch (Exception ex) {
                log.warn("Token refresh failed for {}: {}", oauth.getName(), ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
