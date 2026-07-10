# Design: Hardening pmc-epmmformquerygui for multi-pod "never expire" operation

**Date:** 2026-07-04
**Status:** Approved
**Scope:** Production audit + hardening design for the Keycloak Spring Boot BFF backend described in `pmc-epmmformquerygui-COMPLETE.md` (Spring Boot 3.4.x, Spring Security 6.4.x, Java 17, server-side `oauth2Login`, SPA served from jar).
**Goals:** (1) Users never see the Keycloak login form except when genuinely required. (2) The pattern stays valid long-term. (3) The findings double as teaching material for other developers.

---

## 1. Audit verdict

**The architecture pattern is best practice.** Server-side `oauth2Login` with tokens confined to the backend is the BFF pattern, which current (2026) industry consensus recommends over keycloak-js public clients: no tokens in the browser, session cookie only, centralized auth logic. Serving the frontend bundle from the jar (jar-surgery Dockerfile + `WebAppConfig` SPA fallback) is a legitimate single-artifact trade-off; nginx/CDN hosting only wins when static traffic must scale independently.

**The implementation is NOT correct for the actual deployment (multiple pods).** Every stateful component is pod-local. Without ingress sticky sessions the system produces intermittent bare 401 pages during login and broken API calls on pod hops. The observed "new tab doesn't re-login" does not prove stickiness — the hint-cookie silent re-auth path masks its absence.

**Update 2026-07-04 (revision 2, amended):** the user verified that the app's Service has its own Istio `DestinationRule` with `trafficPolicy.loadBalancer.consistentHash.useSourceIp: true` — requests for one user reach a consistent pod *as long as client source IPs survive to the app's sidecar*. `useSourceIp` is the fragile variant (see §4 risk 4); switching it to `httpCookie` is a Task-10 deliverable recommendation. The multi-pod defects in §3 are therefore mitigated at the infrastructure layer, and the DB externalization originally specified in §4 is **deferred** (see §4 and Appendix A). The code-level bug fixes (§5), future-proofing (§6), and realm alignment (§7) are unaffected and remain in scope.

## 2. Login-form matrix (when does the user see the form again?)

Key mechanism: while a servlet session is alive, `ScheduledTokenRefreshTask` (60s) keeps refreshing tokens, and every refresh grant resets Keycloak's SSO Session Idle timer. SSO Idle therefore only starts counting after the servlet session dies (8h idle) or the pod restarts.

| # | Scenario | Code path | Form shown? |
|---|----------|-----------|-------------|
| a | Active use past access-token lifespan (~5 min) | `TokenRefreshFilter` → `manager.authorize()` → refresh grant within 60s skew | **No** |
| b | Idle < 8h (servlet session alive) | Scheduler keeps refreshing; session cookie still valid on return | **No** |
| c | Idle > 8h, Keycloak SSO still alive | Session dead → saved request → `/oauth2/authorization/keycloak` → hint cookie → `prompt=none` → silent code → URL replayed | **No** (302 chain "flash") |
| d | Idle > 8h AND SSO Idle lapsed | `?error=login_required` → `SilentAuthFailureHandler` clears hint cookie → interactive flow | **Yes** |
| e | SSO Session Max reached mid-work | Refresh grants fail `invalid_grant`; scheduler removes client → API calls fail → next full redirect → login form | **Yes** — preceded by a window of broken API calls; unsaved SPA state lost |
| f | Single pod restart / redeploy | In-memory state gone → silent re-auth (row c) if SSO alive | **No** (one blip; in-flight XHRs fail once) |
| g | Multi-pod, no sticky sessions | See §3 | **No form, but intermittent bare 401 pages and broken API calls** — worst UX of all rows |
| h | 2 tabs + scheduler refresh race | Rotation OFF (recommended): shared refresh token, no race. Rotation ON: reuse detection revokes client session → transient API errors | **No** (transient errors if rotation ON) |
| i | Explicit logout | Session invalidated, hint cookie cleared, Keycloak `end_session` | **Yes** on next visit (by design) |
| j | Keycloak restart (non-persisted sessions) | SSO + refresh tokens gone → same as (e) | **Yes** |
| k | Hint cookie present, SSO dead | `prompt=none` → `login_required` → cookie cleared + interactive redirect (no loop) | **Yes** (one extra round trip) |
| l | Refresh-token max lifespan hit while active | Same mechanics as (e) | **Yes** |
| m | New browser / device / cleared cookies | No hint cookie → normal interactive flow | **Yes** (expected) |

**One-line summary for developers:** on healthy infrastructure the form appears only on first visit/new device, explicit logout, idle longer than (servlet 8h + SSO Idle), the SSO Session Max ceiling, or Keycloak losing its sessions. Everything else is silent.

**Deliberate trade-off to document:** the scheduler keeps SSO alive as long as any servlet session exists — SSO Idle is NOT an idle-logout security control in this design; an abandoned open browser stays logged in until SSO Session Max.

## 3. Multi-pod defect analysis (why externalization is required)

All four state stores are pod-local; with round-robin routing:

1. **Tomcat in-memory `HttpSession`** — pod B doesn't recognize pod A's `JSESSIONID` → user is anonymous on every pod hop → full OIDC redirect dance per hop; XHRs to `/rs/**` receive a 302 to Keycloak they cannot follow ("CORS on 302") → intermittent SPA API failures.
2. **`HttpSessionOAuth2AuthorizationRequestRepository` (Spring default)** — the `state`/nonce of an in-flight code flow lives in the session of the pod that initiated the redirect. The Keycloak callback lands on a different pod with probability ≈ (N−1)/N → `authorization_request_not_found` → falls through `SilentAuthFailureHandler` (matches only `login_required`/`interaction_required`) to `SimpleUrlAuthenticationFailureHandler` with no failure URL → **bare HTTP 401 page**. This is the biggest production symptom.
3. **`InMemoryOAuth2AuthorizedClientService`** — each pod holds its own token copy; N schedulers refresh the same user independently (multiplied token-endpoint load; mutual revocation if rotation is ON).
4. **`SessionRegistryImpl`** — per-pod; each scheduler only sees its own pod's users.

## 4. Multi-pod strategy — verified Istio consistentHash stickiness (revision 2)

The production mesh routes each user to a consistent pod via `DestinationRule` → `trafficPolicy.loadBalancer.consistentHash` (cookie/header). This neutralizes all four §3 defects for normal operation: sessions, the in-flight OAuth2 `state`, tokens, and the scheduler's user set all live on the pod the user is pinned to.

**This makes the DestinationRule a load-bearing part of the auth architecture.** Document it as such: removing or weakening it (e.g. switching to `simple: ROUND_ROBIN`) silently reintroduces intermittent login 401s and broken API calls. The reference doc and realm checklist must carry this warning.

**Residual risks accepted with this strategy (documented, not fixed):**
1. **Pod restart / redeploy / scale-down** drops the affected users' sessions and tokens. Recovery is silent (hint-cookie → `prompt=none`) *only while Keycloak SSO is alive*; in-flight XHRs fail once per event.
2. **Scaling up/down reshuffles the consistent-hash ring** — a fraction of users move to a new pod mid-session and take the row-1 blip.
3. **Per-pod schedulers** each refresh their own users' tokens. Correct with refresh-token rotation OFF (§7); would race if rotation is ever enabled.
4. **`useSourceIp` hashing is only as sticky as the source IP.** Traffic arriving via the Istio ingress gateway is hashed on the IP the sidecar sees — commonly the gateway pod's IP: one gateway replica funnels all its users to a single app pod (hotspot), and multiple gateway replicas can send the SAME user to DIFFERENT app pods (stickiness silently broken). Corporate-NAT users share one hash; mobile users change IP mid-session. **Recommendation (Task 10 deliverable): switch the app's DestinationRule to `consistentHash.httpCookie` (Envoy-issued affinity cookie), which is immune to all of these.**

**Trigger conditions for revisiting (see Appendix A):** stickiness must be removed, zero-blip rolling deploys become a requirement, rotation must be enabled, user counts make per-pod refresh load a problem, or OIDC back-channel logout must be adopted (the logout token carries no pod affinity, so it needs externalized sessions first — review deviation D2).

## 5. Bug fixes (live defects — do these first, independent of §4)

1. **CSRF matcher mismatch** — `csrf.ignoringRequestMatchers(logoutUrl, "/rs/**")` uses an unprefixed pattern while real API paths are `/gui_epmmFormQuery/rs/**`, so CSRF is still enforced on the actual API. Fix: keep CSRF **enabled** for `/rs/**` and have the SPA send the token via `CookieCsrfTokenRepository.withHttpOnlyFalse()`. Fallback if SPA changes are impossible now: correct the pattern to `contextPrefix + "/rs/**"`.
2. **Logout missing `id_token_hint`** — the hand-rolled `CustomLogoutSuccessHandler` sends only `post_logout_redirect_uri`; Keycloak ≥18 then shows a logout-confirmation page and may refuse the redirect. Replace with Spring's `OidcClientInitiatedLogoutSuccessHandler` (wrap or extend to keep the hint-cookie clearing).
3. **Scheduler destroys tokens on any exception** — `catch (Exception)` → `removeAuthorizedClient` means a transient network blip to Keycloak logs the user's tokens out until a full page redirect. Fix: remove the client only on `OAuth2AuthorizationException` with error `invalid_grant`; on transient IO errors keep tokens and retry next tick.
4. **`UserInfo.accessToken` leak risk** — serializing `UserInfo` to JSON hands the bearer token to the browser. Remove the field (server code that needs the token asks `UserInfoService`/the client service explicitly) or, minimally, annotate `@JsonIgnore`.
5. **Minors:**
   - Hint cookie `Path=/` → scope to `/gui_epmmFormQuery` (all three write/clear sites must match).
   - `/actuator/**` permitAll → narrow to `/actuator/health` (defense against a future widened exposure list).
   - Document the plain-HTTP dev hazard: browsers reject `Secure` cookie clears over HTTP → `SilentAuthFailureHandler` cannot clear the hint cookie → infinite `prompt=none` redirect loop in local dev. Mitigate with a profile-controlled `secure` flag.

## 6. Future-proofing track

- **Spring Boot 3.4.5 → 3.5.x now** (3.4 is past OSS EOL as of June 2025); plan the 4.x / Spring Security 7 migration.
- **`AntPathRequestMatcher` → `PathPatternRequestMatcher`** (logout matcher): deprecated in Security 6.5, removed in 7.0.
- Optional later refactor: `WebClient` + WebFlux → `RestClient` + `OAuth2ClientHttpRequestInterceptor` (Security 6.4+), removing the entire WebFlux dependency from a servlet app. Not required; keep separate from this hardening work.

## 7. Keycloak realm alignment

- **SSO Session Idle ≥ servlet session timeout.** Fix the reference doc's contradiction (recommends 2–4h SSO Idle vs 8h servlet session). Target: servlet 8h → SSO Idle 10h.
- **SSO Session Max** is the hard ceiling where an active user WILL see the form mid-work. Size to cover the longest workday (12–14h) and have the SPA autosave state before it hits.
- **Revoke Refresh Token: OFF** (this switch controls rotation; "Refresh Token Max Reuse" applies only when it is ON — correcting the reference doc's terminology). OFF avoids the multi-tab/multi-refresher revocation races.
- Access Token Lifespan ~5 min (unchanged).

## 8. Deliverables

1. Code changes per §5–§6 in the real source tree (the reference doc's embedded sources are the current source of truth; extraction happens in the implementation plan).
2. Updated reference doc: corrected sources + the §2 login-form matrix added as a teaching section for other developers.
3. This design doc.
4. Verification checklist additions (§9).

## 9. Testing & verification

**Unit/slice tests (no Keycloak needed — explicit provider endpoints in the test profile):**
- Scheduler: `invalid_grant` removes the authorized client; a simulated transient error does not.
- SPA CSRF: `XSRF-TOKEN` cookie issued; POST to `/rs/**` passes with token, 403 without.
- Logout handler: redirect to Keycloak `end_session` carries `id_token_hint` + `post_logout_redirect_uri`; hint cookie cleared with `Path=/gui_epmmFormQuery`.
- Hint-cookie scoping and actuator narrowing.

**Manual/cluster verification:**
- Confirm the `DestinationRule` `consistentHash` policy exists in every environment (it is now a documented auth dependency).
- Kill one pod mid-session → affected users recover silently (one blip) while Keycloak SSO is alive.
- Full login-form matrix (§2) spot-checked: rows a, c, d, f, i.

## Appendix A — deferred: DB state externalization

If any §4 trigger condition fires, implement: `spring-session-jdbc` for `HttpSession` (also fixes the OAuth2 `state` handoff automatically), `JdbcOAuth2AuthorizedClientService` for tokens, and a ShedLock-guarded single-pod scheduler iterating the shared token table. This was fully planned in revision 1 (see git history of the accompanying implementation plan, Tasks 10–14, including the cross-instance Testcontainers proof) and can be resurrected as-is.

## 10. Out of scope

- `PrivilegeCheckFilter` LDAP implementation (empty TODO stub behind `app.privilege.enabled`).
- Moving SPA hosting to nginx/CDN (current jar hosting is acceptable practice).
- Keycloak clustering/session persistence (infra concern; noted as row j of the matrix).
