# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this workspace is

There is **no source tree here**. The only content is `pmc-epmmformquerygui-COMPLETE.md` — a single-file reference containing the complete source, configuration, auth-flow diagrams, setup steps, and gotchas for the **pmc-epmmformquerygui** project (Spring Boot 3.4.x / Spring Security 6.4.x / Java 17 / Keycloak OIDC / React-Vite SPA / Kubernetes). Every Java file, `pom.xml`, `application.yml`, and the Dockerfile live as fenced code blocks inside that markdown. Treat it as the source of truth; edits to the project mean editing the code blocks in that file (or extracting them into a real source tree if asked).

## Commands (once extracted into a real project)

- Run locally: `./mvnw spring-boot:run` (needs env vars `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_ISSUER_URI`; optional `DOWNSTREAM_API_URL`, `THIRD_PARTY_API_URL`)
- Build: `mvn -B clean package -DskipTests` (from `backend/`), then `docker build -f Dockerfile -t <registry>/pmc-epmmformquerygui:${TAG} .` from repo root
- Frontend must be built by Vite with `base: '/gui_epmmFormQuery/web/'`; the Dockerfile injects the SPA bundle into the pre-built jar ("jar surgery"), normalizing any build output folder to `dist`

## Architecture (big picture)

Spring Boot app that hosts a React SPA under `/gui_epmmFormQuery/web/`, authenticates via server-side `oauth2Login` against Keycloak, and keeps users logged in without prompts.

**Deliberate design choice:** `server.servlet.context-path` is NOT set. Every URL carries the `/gui_epmmFormQuery` prefix explicitly, built from `app.context-prefix`. That is why `oauth2Login` sets explicit `authorizationEndpoint.baseUri` and `redirectionEndpoint.baseUri`. Do not "simplify" by introducing a context-path.

**Three token-refresh layers share one `OAuth2AuthorizedClientManager`** (config in `TokenRefreshConfig`):
1. Proactive — `TokenRefreshFilter` on each authenticated request
2. Reactive — refresh when a WebClient call needs the token
3. Scheduled — `ScheduledTokenRefreshTask` (~60s) warms idle users' tokens (iterates `SessionRegistry` principals)

Token storage is a **principal-keyed `InMemoryOAuth2AuthorizedClientService`** (not Spring's default session-scoped repository) so the scheduler and multiple tabs share one token set. In-memory means pod restart = silent re-auth; multi-pod needs sticky sessions or `JdbcOAuth2AuthorizedClientService`.

**Silent re-authentication** (servlet session dead, Keycloak SSO alive): `LoginSuccessHandler` sets an `ea_login_hint` cookie; `SilentAuthRequestResolver` sees it and adds `prompt=none`; on `login_required` failure `SilentAuthFailureHandler` MUST clear the cookie (this is the infinite-redirect-loop guard) and retries interactively. `CustomLogoutSuccessHandler` clears the cookie and hits Keycloak's end_session endpoint.

**Outgoing calls:** two `WebClient` beans (`downstreamWebClient`, `thirdPartyWebClient`) — always inject with `@Qualifier`. They attach `Authorization: Bearer` automatically via the shared manager; never set the Authorization header manually (bypasses refresh, causes sporadic 401s).

**`UserInfoService`** reads claims from the already-validated `OidcUser` in the SecurityContext — no extra HTTP call to Keycloak. Roles come from authorities (stripped of `ROLE_`) plus Keycloak's `realm_access.roles` claim.

## Critical constraints (from §15 of the reference)

- **Auth model tension:** the backend is server-side `oauth2Login`. If the frontend adopts keycloak-js (client-side tokens), protected `/rs/**` XHRs get 302→Keycloak that XHR can't follow ("CORS on 302"). In that case convert the backend to a resource server instead — don't mix the two models.
- **`HttpSessionRequestCache` must stay a `@Bean`:** it is shared between the filter chain (writes saved request) and `LoginSuccessHandler` (reads it). Inlining it as a local variable breaks startup with `UnsatisfiedDependencyException`.
- **`ExternalPageController` is Fortify-hardened:** page name is only ever a map key into an allow-list; filenames read from disk are constants; reads are confined to `baseDir`. Request data must never reach a file path or be reflected into the response body.
- **Keycloak timeouts rule:** Keycloak SSO Session Idle ≥ servlet session timeout, or idle-then-refresh stops being silent. Refresh Token Max Reuse = 0 (rotation off) to avoid concurrent-refresh races.
- Feature toggles: `app.token-refresh.enabled`, `app.silent-auth.enabled`, `app.privilege.enabled` (PrivilegeCheckFilter is a stub awaiting LDAP logic).
