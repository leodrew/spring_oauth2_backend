# Spring OAuth2/OIDC Review Checklist

Authority abbreviations: **RFC 9700** = Best Current Practice for OAuth 2.0 Security (BCP 240). **BBA** = draft-ietf-oauth-browser-based-apps (pin the current revision at review time — fetch it). **OIDC** = OpenID Connect Core / RP-Initiated Logout / Back-Channel Logout. **Spring** = Spring Security 6.x reference. Verify §-numbers against the published text before citing; do not trust memory.

Each row: requirement → how to check in Spring → common failure modes.

## Lane 1 — Authorization request, PKCE, redirects

| Requirement (authority, level) | How to check | Failure modes |
|---|---|---|
| Authorization-code grant; no implicit/ROPC (RFC 9700 §2.1.2, SHOULD NOT) | `authorization-grant-type` in registration; grep `password`/`implicit` | Legacy ROPC for "service" logins |
| PKCE on all auth-code flows (BBA MUST; RFC 9700 §2.1.1 REC for confidential, MUST for public) | Grep `withPkce`. For confidential clients Spring does NOT enable it by default. **Check custom `OAuth2AuthorizationRequestResolver` wrappers** — the customizer must go on the wrapped `DefaultOAuth2AuthorizationRequestResolver` | Assuming Spring auto-PKCEs; customizer applied to wrapper but not delegate; IdP not enforcing S256 |
| One-time `state` bound to user agent (RFC 9700 §2.1 MUST) | Present unless the default resolver was replaced wholesale | Custom resolver dropping `state`; state stored client-side |
| OIDC `nonce` present for `openid` scope (RFC 9700 §4.5.3.2 — the confidential-client alternative to PKCE) | Spring sends it automatically for OIDC | Removed by custom resolver |
| Exact redirect URI both sides (RFC 9700 §2.1 MUST) | `redirect-uri` template + IdP client registration | Wildcard URIs at IdP; `{baseUrl}` resolving wrong (→ Lane 5) |
| No open redirectors (RFC 9700 §2.1 MUST) | Grep failure/success handlers for redirect targets built from request params | `continue`/`redirect` query params reflected into `Location` |
| `prompt=none` loop guard (project invariant if silent auth used) | Failure handler must clear the trigger state on `login_required`/`interaction_required` | Infinite redirect loop when SSO dead; Secure-cookie clear failing over plain HTTP |

## Lane 2 — Session, cookies, CSRF

| Requirement | How to check | Failure modes |
|---|---|---|
| Session cookie `Secure` + `HttpOnly` (BBA MUST) | `server.servlet.session.cookie.*` — but runtime `Secure` behavior depends on Lane 5 forwarded headers | Config says secure, proxy says http |
| `SameSite` (BBA SHOULD Strict) | **Check the callback first**: if the IdP is on a different registrable domain, Strict withholds the session cookie on the code callback → `authorization_request_not_found`. Lax is often the correct, documented choice | Blindly recommending Strict; recommending nothing |
| `__Host-` prefix (BBA SHOULD) | Feasible when cookie path is `/`, Secure on, no Domain attr: `server.servlet.session.cookie.name: __Host-JSESSIONID` | Recommending it for path-scoped cookies (invalid) |
| **Every hand-built `jakarta.servlet.http.Cookie` audited separately** | Container `same-site` config applies ONLY to the session cookie. Grep `new Cookie(` — check flags at every write AND clear site (`setAttribute("SameSite", ...)` on Servlet 6+) | Hint/affinity cookies missing SameSite; clear-site flags not matching write-site |
| CSRF defense on state-changing requests (BBA MUST) | For SPAs: `CookieCsrfTokenRepository.withHttpOnlyFalse()` + the documented BREACH-safe request handler | CSRF disabled "because SPA"; exemption patterns not matching real paths (unprefixed matcher vs prefixed URLs) |
| CSRF cookie scope | Default `Path=/` is host-wide — sibling apps on one host can clobber it; `setCookiePath(...)` if multi-app host | Shared-gateway hosts |
| Exemption list minimal | Each `ignoringRequestMatchers` entry justified | Blanket `/api/**` exemptions |
| Session fixation protection | Spring default (`changeSessionId`) — flag if overridden to `none` | Explicitly disabled |
| Session lifetime tied to refresh-token validity (BBA §6.1) | **When refresh fails `invalid_grant`, is the session invalidated?** Check scheduler AND per-request refresh paths | Zombie sessions: IdP revoked, app session lives on for hours |

## Lane 3 — Logout

| Requirement | How to check | Failure modes |
|---|---|---|
| RP-initiated logout with `id_token_hint` (OIDC) | `OidcClientInitiatedLogoutSuccessHandler` (or subclass) — not a hand-built URL | Missing `id_token_hint` → IdP confirmation page / rejected redirect |
| `post_logout_redirect_uri` valid per environment | Is the value parameterized (env placeholder), and registered exactly at the IdP? | Hardcoded host from a sample config |
| Logout method + CSRF (Spring SHOULD: POST+CSRF) | GET logout is a forced-logout primitive — and with RP-initiated logout it kills the **whole SSO session**, all apps. But GET may be load-bearing (top-level nav needed to follow the 302 to `end_session`). Present the trade-off honestly | Overselling the fix; ignoring the SSO-wide blast radius |
| Back-channel logout (OIDC Back-Channel; Spring `http.oidcLogout(o -> o.backChannel(...))`) | **Topology precondition:** the logout token arrives server-to-server with NO instance affinity. With instance-local (in-memory) sessions it misfires. Only recommend after session externalization | Recommending it against pod-local sessions |
| Local cleanup complete | Session invalidated, auth cleared, cookies deleted, **authorized client removed** (`removeAuthorizedClient` in a `LogoutHandler`) | Tokens lingering in store after logout; unbounded store growth |

## Lane 4 — Token lifecycle, refresh, storage

| Requirement | How to check | Failure modes |
|---|---|---|
| Confidential client; secret only server-side | Env-injected secret; no secret in repo/test fixtures beyond obvious fakes | Secret committed; secret in SPA |
| Tokens never reach the browser (BBA core BFF MUST) | Grep DTOs/records serialized to the frontend for token fields; check for a serialization guard test | `accessToken` field on a user-info DTO |
| Refresh rotation or sender-constraining (RFC 9700 §2.2.2 — MUST for **public** clients only; confidential clients are bound by client auth per RFC 6749) | Count the refreshers first: request filters × WebClient/RestClient hooks × schedulers × replicas. Rotation ON with concurrent refreshers → `invalid_grant` races | Recommending rotation without counting refreshers; conversely, rotation OFF undocumented |
| Refresh-failure classification | Drop tokens only on definitive `invalid_grant`; transient errors must retry | Any-exception → token wipe (mass silent logout on IdP blip) |
| Session invalidation on refresh death | See Lane 2 last row — check it from this side too | |
| Token store keying stable (OIDC Core §5.1: MUST NOT rely on `preferred_username` uniqueness) | What does `Authentication.getName()` return? `user-name-attribute: preferred_username` keying the `OAuth2AuthorizedClientService` = cross-user token confusion on username recycling. Prefer `sub` for keying | Mutable claim as the token-store key |
| Multi-instance behavior | `InMemory*` service + >1 replica needs an affinity mechanism (documented!) or a JDBC/Redis store; N schedulers × 1 user = races | Stickiness undocumented/load-bearing-by-accident |
| Access tokens audience-restricted (RFC 9700 §2.3 SHOULD) | Does every outbound client reuse ONE client registration? A token minted for internal APIs sent to an external partner is replayable across audiences | Same `setDefaultClientRegistrationId` on internal and third-party WebClients; javadoc acknowledging the issue without implementing it |
| No tokens in logs | Grep log statements in refresh/auth paths | Token values at DEBUG |

## Lane 5 — Headers, CORS, proxy

| Requirement | How to check | Failure modes |
|---|---|---|
| **Forwarded-headers strategy whenever TLS terminates upstream** (Spring Boot ref; severity: High in that topology) | `server.forward-headers-strategy` / `server.tomcat.remoteip.*` / `ForwardedHeaderFilter` — search the whole repo AND ask what the deployment mounts at runtime | Missing entirely: breaks `{baseUrl}` redirect URI, `isSecure()`, Secure cookies, HSTS emission. "Login works" ≠ configured — find what's compensating |
| HSTS emitted (OWASP) | Explicit `.headers()` HSTS config; note default HSTS only fires on requests Spring sees as secure (→ previous row) | Assumed-default HSTS never actually sent |
| CSP for server-hosted SPAs (BBA XSS considerations) | `.headers(h -> h.contentSecurityPolicy(...))`; a BFF keeps tokens out of the browser but XSS still rides the session — CSP is the remaining control | No CSP; CSP breaking the bundler (start Report-Only) |
| CORS least privilege | On a same-origin BFF, question whether CORS config should exist at all. Never wildcard-pattern origins + `allowCredentials(true)` | `https://*.domain` + credentials + all headers on `/**` |
| API auth entry point for XHR | Expired-session XHR must get 401, not a 302 to the IdP ("CORS on 302" — SPA can't handle it). `DelegatingAuthenticationEntryPoint` / `HttpStatusEntryPoint` on API paths | Default `LoginUrlAuthenticationEntryPoint` for everything |
| Outbound proxying restricted (BBA MUST) | WebClient/RestClient base URLs fixed via config, never from request data | Request-driven target URLs (SSRF) |

## Lane 6 — Build, authorization, tests

| Requirement | How to check | Failure modes |
|---|---|---|
| App-level authorization beyond `authenticated()` (least privilege) | In a shared IdP realm, `authenticated()` = every realm user. Are roles/authorities actually enforced (`hasRole`, method security, or IdP-side client restriction)? | Roles extracted but never checked; toggle-able authz filter that is a pass-through stub |
| Supported framework versions; known CVEs | Resolve the actual Spring Security version from the Boot parent; check spring.io/security for the affected ranges. **Verify fix versions against the advisory, not memory** | Boot patch lag; wrong fix version cited |
| Dependency scanning in build/CI (OWASP SSDLC) | dependency-check-maven / Dependabot / Renovate / Snyk | None configured |
| Minimal dependency surface | e.g. full `starter-webflux` carried only for `WebClient` (Security 6.4+: `RestClient` + `OAuth2ClientHttpRequestInterceptor`) | Unused reactive stack |
| Security components under test | Custom resolvers, failure handlers (loop guards!), refresh filters, logout handlers, CORS, headers | The loop guard protecting a documented hazard has zero tests |
| No deprecated security APIs | `AntPathRequestMatcher` (removed in Security 7) → `PathPatternRequestMatcher` | Blocking the next major upgrade |

## Deviations register template

| Deviation | Authority deviated from | Rationale | Compensating controls | Revisit trigger |
|---|---|---|---|---|
| (one row per documented deliberate trade-off — no rotation, Lax cookies, in-memory state + affinity, …) | | | | |

A deviation without all four columns filled is a finding, not a deviation.
