# Keycloak realm checklist — pmc-epmmformquerygui (production)

Where: Keycloak admin console → realm → Realm settings → Sessions / Tokens,
and Clients → <client> → Settings / Advanced.

**Infrastructure dependency (do not remove):** the Istio `DestinationRule`
`trafficPolicy.loadBalancer.consistentHash` policy is load-bearing for auth —
sessions and tokens are in-memory per pod. Switching it to `simple:
ROUND_ROBIN` (or deleting it) reintroduces intermittent login 401s
(`authorization_request_not_found`) and broken API calls.

| Setting | Value | Why |
|---|---|---|
| SSO Session Idle | 10h | Must be >= servlet session timeout (8h) so "idle then refresh" stays silent |
| SSO Session Max | 12–14h | Hard ceiling: active users WILL see the login form when it lapses — cover the longest workday; SPA should autosave |
| Access Token Lifespan | 5 min | Short-lived; the 3-layer refresh keeps it fresh |
| Revoke Refresh Token | OFF | Controls rotation; OFF avoids multi-tab/multi-refresher revocation races |
| Refresh Token Max Reuse | (ignored while Revoke is OFF) | Only applies when Revoke Refresh Token is ON |
| Client: Standard Flow | ON | Authorization-code flow used by oauth2Login |
| Client: Valid Redirect URIs | https://<host>/gui_epmmFormQuery/login/oauth2/code/keycloak | Exact match — wrong URI is the usual cause of a 4xx hop in the silent-auth chain |
| Client: Valid Post Logout Redirect URIs | https://<host>/gui_epmmFormQuery/page/logged-out | Required for RP-initiated logout with id_token_hint |
| Token mapper | realm_access.roles present | UserInfoService reads roles from this claim |

Deliberate design note: while any servlet session is live, the background
refresher resets SSO Idle every ~5 min — SSO Idle is NOT an idle-logout
control here; SSO Session Max is the only hard stop.
