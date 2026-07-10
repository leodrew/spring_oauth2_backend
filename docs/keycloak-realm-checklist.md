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
| Token mapper | realm_access.roles present | UserInfoService reads roles from this claim; also feeds the ROLE_* authorities mapper behind `app.security.required-role` |
| Realm/client role for this app (e.g. `epmm-user`) + assignment | Create when enabling `app.security.required-role` | Without it, "authenticated" means every SSO user in the shared realm can use the app (review F17) |

## Tracked: third-party token audience (review F18)

`thirdPartyWebClient` currently sends the same Keycloak access token used for
the internal APIs to `THIRD_PARTY_API_URL`. If that target is genuinely
external, it can replay our token against every other audience it is valid
for. Before pointing the code at a separate registration (the switch is
described in `WebClientConfig`'s javadoc), ops must first provision:

1. A dedicated Keycloak client (ideally `client_credentials`, or token
   exchange) for the partner integration.
2. An audience mapper so the issued token is only valid at the partner.

Decision 2026-07-10: deferred until the partner's trust domain is confirmed
(review Appendix A7) — if it turns out to be an internal service, rename the
config instead and drop this item.

Deliberate design note: while any servlet session is live, the background
refresher resets SSO Idle every ~5 min — SSO Idle is NOT an idle-logout
control here; SSO Session Max is the only hard stop.
