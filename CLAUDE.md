# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this workspace is

The Maven project now lives in **`backend/`** — a real, compilable Spring Boot source tree (Spring Boot 3.5.x / Spring Security 6.5.x / Java 17 / Keycloak OIDC / React-Vite SPA / Kubernetes) for the **pmc-epmmformquerygui** project. It was extracted from the reference doc's embedded code blocks in Task 1 of `docs/superpowers/plans/2026-07-04-keycloak-backend-hardening.md`; `backend/` — not the markdown — is now the source of truth for code changes.

Documentation responsibilities are split across these docs:
- `pmc-epmmformquerygui-COMPLETE.md` (repo root) — the narrative/teaching reference document: auth-flow diagrams, setup steps, gotchas, and (§17) the full "when does the user see the login form again?" matrix. Read it to understand *why* the code is shaped this way.
- `docs/auth-workflow.md` — the auth-flow walkthrough traced from the current `backend/` code: first login, token refresh, silent re-auth, the "when does the login form appear?" matrix, DestinationRule `useSourceIp` vs `httpCookie`, and the BFF rationale. Supersedes the old root-level `spring_first_login_workflow.md` / `spring_silent_auth_workflow.md` (deleted).
- `docs/oauth2-migration-intro.md` — team-facing intro in Traditional Chinese (deliberately; all other docs are English): OAuth2/Keycloak basics, SPA-vs-BFF trade-offs, and the Java 8 Keycloak-adapter → Java 17 Spring Security `oauth2Login` migration story with Mermaid sequence flows.
- `docs/keycloak-realm-checklist.md` — the concrete realm/client setting values for the production Keycloak realm.
- `docs/istio-stickiness.md` — the Istio mesh dependency that multi-pod correctness relies on (see "Critical constraints" below).

## Commands

- Run locally: `mvn spring-boot:run` (from `backend/`; needs env vars `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_ISSUER_URI`; optional `DOWNSTREAM_API_URL`, `THIRD_PARTY_API_URL`)
- Test: `mvn -B test` (from `backend/`)
- Build: `mvn -B clean package -DskipTests` (from `backend/`), then `docker build -f Dockerfile -t <registry>/pmc-epmmformquerygui:${TAG} .` from repo root (note: the Dockerfile has not been extracted yet — it exists only as the §14 code block in `pmc-epmmformquerygui-COMPLETE.md`; extract it before running this)
- Frontend must be built by Vite with `base: '/gui_epmmFormQuery/web/'`; the Dockerfile injects the SPA bundle into the pre-built jar ("jar surgery"), normalizing any build output folder to `dist`

**Toolchain note:** Java and Maven are portable installs under `C:\Users\leo01\tools\` (JDK 17, Maven 3.9.x). The user-level `JAVA_HOME`/`Path` environment variables already point there, so a normal shell should find `java`/`mvn` without extra steps. `.superpowers/sdd/env.ps1` (and `env.sh`) are session-scratch fallbacks written for earlier task sessions where those user-level vars weren't picked up — only source one of them if `java -version` / `mvn -v` come back not-found.

## Architecture (big picture)

Spring Boot app that hosts a React SPA under `/gui_epmmFormQuery/web/`, authenticates via server-side `oauth2Login` against Keycloak, and keeps users logged in without prompts.

**Deliberate design choice:** `server.servlet.context-path` is NOT set. Every URL carries the `/gui_epmmFormQuery` prefix explicitly, built from `app.context-prefix`. That is why `oauth2Login` sets explicit `authorizationEndpoint.baseUri` and `redirectionEndpoint.baseUri`. Do not "simplify" by introducing a context-path.

**Equally load-bearing:** `server.forward-headers-strategy: framework` in `application.yml`. TLS terminates upstream in the Istio mesh, so without it Tomcat sees plain HTTP — `{baseUrl}` in the redirect-uri resolves to `http://…` (exact-match failure at Keycloak), `isSecure()` is false, and HSTS is never emitted. Do not remove it (review finding F3a).

**Three token-refresh layers share one `OAuth2AuthorizedClientManager`** (config in `TokenRefreshConfig`):
1. Proactive — `TokenRefreshFilter` on each authenticated request
2. Reactive — refresh when a WebClient call needs the token
3. Scheduled — `ScheduledTokenRefreshTask` (~60s) warms idle users' tokens (iterates `SessionRegistry` principals)

Token storage is a **principal-keyed `InMemoryOAuth2AuthorizedClientService`** (not Spring's default session-scoped repository) so the scheduler and multiple tabs share one token set. The principal key is the **OIDC `sub`** (`user-name-attribute: sub` — review F15): OIDC forbids relying on `preferred_username` stability, so don't change the keying back; `UserInfoService` exposes `preferred_username` as the business/display username. In-memory means pod restart = silent re-auth; multi-pod correctness depends on Istio stickiness (see "Critical constraints" below), not on this service becoming distributed.

**Silent re-authentication** (servlet session dead, Keycloak SSO alive): `LoginSuccessHandler` sets an `ea_login_hint` cookie; `SilentAuthRequestResolver` sees it and adds `prompt=none` (and applies **PKCE S256** to every authorization request via the delegate's customizer — review F1); on `login_required` failure `SilentAuthFailureHandler` MUST clear the cookie (this is the infinite-redirect-loop guard) and retries interactively. `KeycloakLogoutSuccessHandler` (extends Spring's `OidcClientInitiatedLogoutSuccessHandler`, so `end_session` correctly carries `id_token_hint` + `client_id`) clears the cookie and redirects to Keycloak's `end_session` endpoint.

**Outgoing calls:** two `WebClient` beans (`downstreamWebClient`, `thirdPartyWebClient`) — always inject with `@Qualifier`. They attach `Authorization: Bearer` automatically via the shared manager; never set the Authorization header manually (bypasses refresh, causes sporadic 401s).

**`UserInfoService`** reads claims from the already-validated `OidcUser` in the SecurityContext — no extra HTTP call to Keycloak. Roles come from authorities (stripped of `ROLE_`) plus Keycloak's `realm_access.roles` claim. The `UserInfo` record carries only claims (username, subject, email, fullName, givenName, familyName, roles) — it has no access-token field, so serializing it to the SPA can never leak a bearer token.

## Critical constraints

- **Multi-pod correctness depends on the Istio `consistentHash` DestinationRule** (see `docs/istio-stickiness.md`). Sessions (`HttpSession`), the in-flight OAuth2 `state`, and authorized-client tokens are all in-memory **per pod**. The mesh's `DestinationRule` → `trafficPolicy.loadBalancer.consistentHash` policy is what pins a user to one pod and makes that safe. **Do not weaken or remove this DestinationRule** (e.g. switching to `simple: ROUND_ROBIN`) without first implementing the DB-externalization design in spec Appendix A (`spring-session-jdbc` + `JdbcOAuth2AuthorizedClientService` + a ShedLock-guarded scheduler) — removing it silently reintroduces intermittent login 401s (`authorization_request_not_found`) and broken API calls.
- **Auth model tension:** the backend is server-side `oauth2Login`. If the frontend adopts keycloak-js (client-side tokens), protected `/rs/**` XHRs get 302→Keycloak that XHR can't follow ("CORS on 302"). In that case convert the backend to a resource server instead — don't mix the two models.
- **`HttpSessionRequestCache` must stay a `@Bean`:** it is shared between the filter chain (writes saved request) and `LoginSuccessHandler` (reads it). Inlining it as a local variable breaks startup with `UnsatisfiedDependencyException`.
- **`ExternalPageController` is Fortify-hardened:** page name is only ever a map key into an allow-list; filenames read from disk are constants; reads are confined to `baseDir`. Request data must never reach a file path or be reflected into the response body.
- **CSRF is cookie-based for the SPA:** CSRF protection is enabled on `/rs/**` (only the logout URL is exempted — GET logout is deviation D7). `SecurityConfig.spaCsrfTokenRepository()` builds the `CookieCsrfTokenRepository` with a single cookie customizer doing `httpOnly(false).sameSite("Lax")` plus `setCookiePath(contextPrefix)`; do NOT "simplify" to `withHttpOnlyFalse()` + a separate `setCookieCustomizer` — the second customizer replaces the first and silently re-enables HttpOnly, breaking the SPA. The SPA reads the `XSRF-TOKEN` cookie and sends it back as a header on state-changing requests.
- **Expired-session XHR contract (review F2):** unauthenticated `/rs/**` requests get **401** via `HttpStatusEntryPoint`; everything else keeps the 302 through the explicit `AnyRequestMatcher` fallback mapping. That fallback is load-bearing: with only one custom entry-point mapping, Spring falls back to the first-registered entry point and every page navigation turns 401. On a 401 the SPA must top-level navigate to its current route (re-enters silent re-auth).
- **Keycloak timeouts rule:** SSO Session Idle MUST be >= servlet session timeout (8h) — use 10h — or "idle then refresh" stops being silent (see `docs/keycloak-realm-checklist.md`). **Revoke Refresh Token: OFF** (this switch controls rotation; "Refresh Token Max Reuse" only applies when it is ON) to avoid concurrent-refresh races.
- **Only `invalid_grant` is fatal to a login (review F10):** on `invalid_grant`, `ScheduledTokenRefreshTask` removes the authorized client **and expires the principal's sessions**, and `TokenRefreshFilter` invalidates the current session + clears the SecurityContext — this ≤~2-min revocation propagation is the compensating control for having no back-channel logout (deviation D2). Any other OAuth2 error or transient exception (e.g. a network blip to Keycloak) keeps tokens AND sessions and retries. Do not blur this classification in either direction.
- **`AntPathRequestMatcher` is gone.** The logout matcher (and any other request matcher) uses `PathPatternRequestMatcher`; do not reintroduce `AntPathRequestMatcher` — it's deprecated in Spring Security 6.5 and removed in 7.0.
- **Session cookie is `__Host-JSESSIONID`** (review F8): the `__Host-` prefix is browser-enforced (Secure, Path=/, no Domain). Plain-HTTP local dev must override the name (`SERVER_SERVLET_SESSION_COOKIE_NAME=JSESSIONID`); Istio affinity is unaffected (Envoy issues its own `epmm-affinity` cookie). `SameSite` stays `lax` — Strict breaks the OAuth callback if Keycloak is cross-site (deviation D3).
- **No CORS config, on purpose** (review F4): the SPA is same-origin (BFF). If a cross-origin consumer appears, add an explicit enumerated-origin config scoped to the paths it needs — never wildcard-with-credentials.
- Feature toggles: `app.token-refresh.enabled`, `app.silent-auth.enabled`, `app.privilege.enabled` (PrivilegeCheckFilter **fails closed** — enabled-but-unimplemented denies everything with 503; review F17), `app.security.required-role` (empty = any authenticated realm user; set = require that Keycloak realm role, mapped to `ROLE_*` by `realmRolesAuthoritiesMapper`).
- **Remediation record:** the 2026-07-09 OAuth2/OIDC review's fix plan was applied 2026-07-10 (branch `remediation/oauth2-review`). Still open: F18 (third-party token audience — needs the partner trust-domain answer + a Keycloak client, see `docs/keycloak-realm-checklist.md`) and the report's Appendix A ops confirmations.
