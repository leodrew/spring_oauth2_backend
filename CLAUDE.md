# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this workspace is

The Maven project now lives in **`backend/`** — a real, compilable Spring Boot source tree (Spring Boot 3.5.x / Spring Security 6.5.x / Java 17 / Keycloak OIDC / React-Vite SPA / Kubernetes) for the **pmc-epmmformquerygui** project. It was extracted from the reference doc's embedded code blocks in Task 1 of `docs/superpowers/plans/2026-07-04-keycloak-backend-hardening.md`; `backend/` — not the markdown — is now the source of truth for code changes.

Documentation responsibilities are split three ways:
- `pmc-epmmformquerygui-COMPLETE.md` (repo root) — the narrative/teaching reference document: auth-flow diagrams, setup steps, gotchas, and (§17) the full "when does the user see the login form again?" matrix. Read it to understand *why* the code is shaped this way.
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

**Three token-refresh layers share one `OAuth2AuthorizedClientManager`** (config in `TokenRefreshConfig`):
1. Proactive — `TokenRefreshFilter` on each authenticated request
2. Reactive — refresh when a WebClient call needs the token
3. Scheduled — `ScheduledTokenRefreshTask` (~60s) warms idle users' tokens (iterates `SessionRegistry` principals)

Token storage is a **principal-keyed `InMemoryOAuth2AuthorizedClientService`** (not Spring's default session-scoped repository) so the scheduler and multiple tabs share one token set. In-memory means pod restart = silent re-auth; multi-pod correctness depends on Istio stickiness (see "Critical constraints" below), not on this service becoming distributed.

**Silent re-authentication** (servlet session dead, Keycloak SSO alive): `LoginSuccessHandler` sets an `ea_login_hint` cookie; `SilentAuthRequestResolver` sees it and adds `prompt=none`; on `login_required` failure `SilentAuthFailureHandler` MUST clear the cookie (this is the infinite-redirect-loop guard) and retries interactively. `KeycloakLogoutSuccessHandler` (extends Spring's `OidcClientInitiatedLogoutSuccessHandler`, so `end_session` correctly carries `id_token_hint` + `client_id`) clears the cookie and redirects to Keycloak's `end_session` endpoint.

**Outgoing calls:** two `WebClient` beans (`downstreamWebClient`, `thirdPartyWebClient`) — always inject with `@Qualifier`. They attach `Authorization: Bearer` automatically via the shared manager; never set the Authorization header manually (bypasses refresh, causes sporadic 401s).

**`UserInfoService`** reads claims from the already-validated `OidcUser` in the SecurityContext — no extra HTTP call to Keycloak. Roles come from authorities (stripped of `ROLE_`) plus Keycloak's `realm_access.roles` claim. The `UserInfo` record carries only claims (username, subject, email, fullName, givenName, familyName, roles) — it has no access-token field, so serializing it to the SPA can never leak a bearer token.

## Critical constraints

- **Multi-pod correctness depends on the Istio `consistentHash` DestinationRule** (see `docs/istio-stickiness.md`). Sessions (`HttpSession`), the in-flight OAuth2 `state`, and authorized-client tokens are all in-memory **per pod**. The mesh's `DestinationRule` → `trafficPolicy.loadBalancer.consistentHash` policy is what pins a user to one pod and makes that safe. **Do not weaken or remove this DestinationRule** (e.g. switching to `simple: ROUND_ROBIN`) without first implementing the DB-externalization design in spec Appendix A (`spring-session-jdbc` + `JdbcOAuth2AuthorizedClientService` + a ShedLock-guarded scheduler) — removing it silently reintroduces intermittent login 401s (`authorization_request_not_found`) and broken API calls.
- **Auth model tension:** the backend is server-side `oauth2Login`. If the frontend adopts keycloak-js (client-side tokens), protected `/rs/**` XHRs get 302→Keycloak that XHR can't follow ("CORS on 302"). In that case convert the backend to a resource server instead — don't mix the two models.
- **`HttpSessionRequestCache` must stay a `@Bean`:** it is shared between the filter chain (writes saved request) and `LoginSuccessHandler` (reads it). Inlining it as a local variable breaks startup with `UnsatisfiedDependencyException`.
- **`ExternalPageController` is Fortify-hardened:** page name is only ever a map key into an allow-list; filenames read from disk are constants; reads are confined to `baseDir`. Request data must never reach a file path or be reflected into the response body.
- **CSRF is cookie-based for the SPA:** CSRF protection is enabled on `/rs/**` (only the logout URL is exempted). `SecurityConfig` uses `CookieCsrfTokenRepository.withHttpOnlyFalse()` plus a `SpaCsrfTokenRequestHandler` so the token is readable from JS; the SPA reads the `XSRF-TOKEN` cookie and sends it back as a header on state-changing requests.
- **Keycloak timeouts rule:** SSO Session Idle MUST be >= servlet session timeout (8h) — use 10h — or "idle then refresh" stops being silent (see `docs/keycloak-realm-checklist.md`). **Revoke Refresh Token: OFF** (this switch controls rotation; "Refresh Token Max Reuse" only applies when it is ON) to avoid concurrent-refresh races.
- **Scheduler only drops tokens on `invalid_grant`:** `ScheduledTokenRefreshTask` catches `ClientAuthorizationException` and removes the authorized client only when the error code is `invalid_grant`; any other OAuth2 error or transient exception (e.g. a network blip to Keycloak) keeps the tokens and retries on the next tick.
- **`AntPathRequestMatcher` is gone.** The logout matcher (and any other request matcher) uses `PathPatternRequestMatcher`; do not reintroduce `AntPathRequestMatcher` — it's deprecated in Spring Security 6.5 and removed in 7.0.
- Feature toggles: `app.token-refresh.enabled`, `app.silent-auth.enabled`, `app.privilege.enabled` (PrivilegeCheckFilter is a stub awaiting LDAP logic).
