# Design: Hardening pmc-epmmformquerygui for multi-pod "never expire" operation

**Date:** 2026-07-04
**Status:** Approved
**Scope:** Production audit + hardening design for the Keycloak Spring Boot BFF backend described in `pmc-epmmformquerygui-COMPLETE.md` (Spring Boot 3.4.x, Spring Security 6.4.x, Java 17, server-side `oauth2Login`, SPA served from jar).
**Goals:** (1) Users never see the Keycloak login form except when genuinely required. (2) The pattern stays valid long-term. (3) The findings double as teaching material for other developers.

---

## 1. Audit verdict

**The architecture pattern is best practice.** Server-side `oauth2Login` with tokens confined to the backend is the BFF pattern, which current (2026) industry consensus recommends over keycloak-js public clients: no tokens in the browser, session cookie only, centralized auth logic. Serving the frontend bundle from the jar (jar-surgery Dockerfile + `WebAppConfig` SPA fallback) is a legitimate single-artifact trade-off; nginx/CDN hosting only wins when static traffic must scale independently.

**The implementation is NOT correct for the actual deployment (multiple pods).** Every stateful component is pod-local. Without ingress sticky sessions the system produces intermittent bare 401 pages during login and broken API calls on pod hops. The observed "new tab doesn't re-login" does not prove stickiness — the hint-cookie silent re-auth path masks its absence.

**Immediate operational action (independent of this design):** check the ingress for a session-affinity annotation (e.g. `nginx.ingress.kubernetes.io/affinity: cookie`). If absent, users are currently experiencing intermittent login failures that retries mask.

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

## 4. Target architecture — externalized state via the shared relational DB

Use only official Spring implementations; one store (the existing RDBMS), no new infrastructure.

| Today (per-pod) | Target (shared) |
|---|---|
| Tomcat in-memory `HttpSession` | `spring-session-jdbc` (`SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` tables) — any pod resolves any `JSESSIONID` |
| `InMemoryOAuth2AuthorizedClientService` | `JdbcOAuth2AuthorizedClientService` (`oauth2_authorized_client` table) — one token set per user shared by all pods; survives pod restarts |
| `HttpSessionOAuth2AuthorizationRequestRepository` | Unchanged — automatically fixed once sessions are in JDBC (callback pod finds the `state` in the shared session) → eliminates the random 401s |
| Per-pod `SessionRegistryImpl` + N schedulers | `SpringSessionBackedSessionRegistry` + scheduler guarded by a DB lock (ShedLock) so exactly one pod runs the refresh scan per tick |

**Changes:**
- `pom.xml`: add `spring-session-jdbc`, ShedLock (`shedlock-spring` + `shedlock-provider-jdbc-template`); remove nothing.
- `application.yml`: `spring.session.store-type` handled by auto-config; `spring.session.jdbc.initialize-schema=never` in prod (schema applied by migration); session timeout moves to `spring.session.timeout`.
- `TokenRefreshConfig`: swap `InMemoryOAuth2AuthorizedClientService` → `JdbcOAuth2AuthorizedClientService(jdbcOperations, clientRegistrationRepository)`; replace `SessionRegistryImpl` with `SpringSessionBackedSessionRegistry` (requires `FindByIndexNameSessionRepository`).
- `ScheduledTokenRefreshTask`: annotate with `@SchedulerLock`; iterate principals via the session-repository index instead of the in-memory registry.
- DB migrations: Spring Session schema + `oauth2_authorized_client` schema + ShedLock table (scripts ship with the respective libraries).

**Effects:** pods become stateless (12-factor), rolling deploys and pod deaths are invisible to users, no sticky-session requirement, "never expire while active" survives infrastructure churn. Trade-off: a DB round-trip per request for session load — negligible against the current failure modes.

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

1. Code changes per §4–§6 in the real source tree (the reference doc's embedded sources are the current source of truth; extraction happens in the implementation plan).
2. Updated reference doc: corrected sources + the §2 login-form matrix added as a teaching section for other developers.
3. This design doc.
4. Verification checklist additions (§9).

## 9. Testing & verification

**Integration tests (Testcontainers: Keycloak + PostgreSQL, two app instances against the shared DB):**
- Authorization-code login initiated on instance A completes on instance B (proves shared session + shared `state`).
- Request with expired access token on instance B refreshes using tokens persisted by instance A.
- Scheduler: `invalid_grant` removes the authorized client; a simulated IO error does not.
- SPA POST to `/rs/**` succeeds with CSRF token, 403 without.
- Logout redirects to Keycloak `end_session` with `id_token_hint` and returns to the logged-out page without a confirmation prompt.

**Manual/cluster verification:**
- Kill one pod mid-session → user continues without login form and without API errors.
- Round-robin two pods with stickiness removed → login succeeds every attempt.
- Stop the ShedLock-holding pod → another pod's scheduler takes over within one tick.
- Full login-form matrix (§2) spot-checked: rows a, c, d, f, i.

## 10. Out of scope

- `PrivilegeCheckFilter` LDAP implementation (empty TODO stub behind `app.privilege.enabled`).
- Moving SPA hosting to nginx/CDN (current jar hosting is acceptable practice).
- Keycloak clustering/session persistence (infra concern; noted as row j of the matrix).
