package com.example.epmmformquery.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.example.epmmformquery.model.UserInfo;

/**
 * Single point of access for OIDC user information from server-side code.
 *
 * Strategy: read everything from the OidcUser already attached to the
 * SecurityContext (was validated and parsed at login by Spring Security's
 * OidcAuthorizationCodeAuthenticationProvider). NO additional HTTP call
 * to Keycloak. This is fast, can't fail, and always reflects what was in
 * the validated id_token.
 *
 * Sample usage in a controller:
 *
 *   @GetMapping("/rs/some-endpoint")
 *   public ResponseEntity<X> handle() {
 *       UserInfo me = userInfoService.current()
 *           .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED));
 *       if (!me.hasRole("admin")) { ... }
 *       webClient.get().uri("/internal/data?actor=" + me.username())...
 *   }
 */
@Service
public class UserInfoService {

    private static final String CLIENT_REGISTRATION_ID = "keycloak";
    private static final String REALM_ROLE_PREFIX = "ROLE_";

    private final OAuth2AuthorizedClientService authorizedClientService;

    public UserInfoService(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * Returns the current user's info, or empty if no one is authenticated
     * (or if authentication is not OAuth2-based — e.g., during tests).
     *
     * Does NOT include the access token — see currentAccessToken() for the
     * rare server-side case that genuinely needs the raw bearer token.
     */
    public Optional<UserInfo> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) {
            return Optional.empty();
        }
        return Optional.of(buildUserInfo(oauth));
    }

    /**
     * Convenience for code paths that should fail loudly when not
     * authenticated. Throws IllegalStateException — typically wrapped
     * by a ControllerAdvice into a 401 response.
     */
    public UserInfo currentOrThrow() {
        return current().orElseThrow(() ->
                new IllegalStateException("No authenticated OAuth2 user in SecurityContext"));
    }

    private UserInfo buildUserInfo(OAuth2AuthenticationToken oauth) {
        OAuth2User principal = oauth.getPrincipal();
        OidcUser oidc = (principal instanceof OidcUser o) ? o : null;

        // getName() is the OIDC sub since the token store keys on it (F15);
        // the friendly business username lives in the preferred_username claim.
        String preferred = principal.getAttribute("preferred_username");
        String username  = preferred != null ? preferred : oauth.getName();
        String subject   = oidc != null ? oidc.getSubject() : null;
        String email    = oidc != null ? oidc.getEmail() : (String) principal.getAttribute("email");
        String name     = oidc != null ? oidc.getFullName() : (String) principal.getAttribute("name");
        String given    = oidc != null ? oidc.getGivenName() : null;
        String family   = oidc != null ? oidc.getFamilyName() : null;

        List<String> roles = extractRoles(principal);

        return new UserInfo(username, subject, email, name, given, family, roles);
    }

    /**
     * Extracts roles from two places, in priority order:
     *   1. Authentication.getAuthorities() — Spring's normalized view, may
     *      already include role mappers configured elsewhere.
     *   2. Keycloak's "realm_access.roles" claim — present in standard
     *      Keycloak token mappers.
     *
     * Strips Spring's "ROLE_" prefix from authorities so callers see plain
     * role names like "admin", "viewer" — matching what they'd see in the
     * Keycloak admin console.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(OAuth2User principal) {
        List<String> roles = new ArrayList<>();

        Collection<? extends GrantedAuthority> authorities = principal.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority a : authorities) {
                String s = a.getAuthority();
                if (s == null) continue;
                if (s.startsWith(REALM_ROLE_PREFIX)) {
                    roles.add(s.substring(REALM_ROLE_PREFIX.length()));
                } else if (!s.startsWith("SCOPE_") && !s.startsWith("OIDC_USER")) {
                    // Skip OIDC_USER / SCOPE_* synthetic authorities
                    roles.add(s);
                }
            }
        }

        // Pull from Keycloak's realm_access.roles claim if not already present
        Object realmAccess = principal.getAttribute("realm_access");
        if (realmAccess instanceof java.util.Map<?, ?> map) {
            Object rolesObj = map.get("roles");
            if (rolesObj instanceof Collection<?> kcRoles) {
                for (Object r : kcRoles) {
                    if (r instanceof String rs && !roles.contains(rs)) {
                        roles.add(rs);
                    }
                }
            }
        }

        return roles.isEmpty() ? Collections.emptyList() : roles;
    }

    /**
     * Raw bearer token for the rare server-side case WebClient can't cover.
     * Never return this to the SPA.
     */
    public Optional<String> currentAccessToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) {
            return Optional.empty();
        }
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                CLIENT_REGISTRATION_ID, oauth.getName());
        return Optional.ofNullable(client)
                .map(OAuth2AuthorizedClient::getAccessToken)
                .map(t -> t.getTokenValue());
    }
}
