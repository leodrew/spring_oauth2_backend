package com.example.epmmformquery.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of the authenticated user's OIDC claims, suitable for passing
 * to controllers, services, and downstream calls.
 *
 * Built from the OidcUser already loaded into Spring's SecurityContext at
 * login. There is NO extra HTTP call to Keycloak's /userinfo endpoint —
 * everything in here came from the validated id_token. If you need attributes
 * that aren't in the id_token (e.g. provider-specific claims), see
 * UserInfoService.fetchFreshFromUserInfoEndpoint() — but in 99% of cases,
 * the claims here are sufficient.
 */
public record UserInfo(
        String username,        // preferred_username (OIDC standard)
        String subject,         // sub claim (Keycloak's stable user id)
        String email,
        String fullName,        // name claim
        String givenName,
        String familyName,
        List<String> roles,
        String accessToken      // bearer string — for passing to downstream APIs only when WebClient isn't suitable
) {
    public UserInfo {
        // Defensive copy + null safety
        roles = roles == null ? Collections.emptyList() : List.copyOf(roles);
    }

    /** True if the user has the given role (Keycloak realm role). */
    public boolean hasRole(String role) {
        return roles.contains(Objects.requireNonNull(role));
    }
}
