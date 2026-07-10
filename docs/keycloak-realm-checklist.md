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
| Client: Proof Key for Code Exchange Code Challenge Method | S256 | Backend sends PKCE on every authorization request (review F1); S256 makes Keycloak enforce it |
| Client: Valid Redirect URIs | https://<host>/gui_epmmFormQuery/login/oauth2/code/keycloak | Exact match — wrong URI is the usual cause of a 4xx hop in the silent-auth chain. Must be **https** (the app resolves `{baseUrl}` to https via forward-headers) |
| Client: Valid Post Logout Redirect URIs | https://<host>/gui_epmmFormQuery/page/logged-out | Required for RP-initiated logout with id_token_hint. **Per environment**: the app derives the value from `{baseUrl}` at runtime (override: `POST_LOGOUT_REDIRECT_URI` env var), so every environment's public host must be registered here |
| Client: Backchannel Logout URL | (empty — intentional) | Back-channel logout misfires against pod-local sessions (review D2); compensated by session kill on invalid_grant within ~2 min. Revisit only after session externalization |
| Token mapper | realm_access.roles present | UserInfoService reads roles from this claim |

Deliberate design note: while any servlet session is live, the background
refresher resets SSO Idle every ~5 min — SSO Idle is NOT an idle-logout
control here; SSO Session Max is the only hard stop.
