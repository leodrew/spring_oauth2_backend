# Spring OIDC/OAuth2 Best-Practices Review — pmc-epmmformquerygui

> **Review date:** 2026-07-09 · **Source state:** `master` as cloned 2026-07-09 (Spring Boot 3.5.4, Spring Security 6.5.x, Java 17) · **Reviewer:** Claude Code multi-agent review (2 exploration agents + 1 verification agent + web-verified authorities).
>
> **Baselines (pinned):** [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html) *Best Current Practice for OAuth 2.0 Security* (BCP 240, Jan 2025) · [draft-ietf-oauth-browser-based-apps-**27**](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/) *OAuth 2.0 for Browser-Based Applications* (2026-07-06, in RFC Editor queue — cited below as "BBA-27") · Spring Security 6.5 reference documentation.
>
> Companion docs: `docs/auth-workflow.md` (flow trace), `docs/keycloak-realm-checklist.md` (realm settings), `docs/istio-stickiness.md` (mesh dependency), `docs/superpowers/specs/2026-07-04-keycloak-backend-hardening-design.md` (previous hardening round — "the spec").

---

## Contents

- [§0 — Executive summary](#0--executive-summary)
- [§1 — Scope, method, severity rubric](#1--scope-method-severity-rubric)
- [§2 — Findings](#2--findings)
- [§3 — Accepted-deviations register](#3--accepted-deviations-register)
- [§4 — Prioritized fix plan](#4--prioritized-fix-plan)
- [§5 — Compliance matrix](#5--compliance-matrix)
- [Appendix A — Environment assumptions requiring ops confirmation](#appendix-a--environment-assumptions-requiring-ops-confirmation)

---

## §0 — Executive summary

**Verdict: the architecture is the right one, correctly shaped.** The app is a textbook BFF — confidential client, server-side `oauth2Login`, tokens never reach the browser, cookie session + double-submit CSRF, RP-initiated logout with `id_token_hint` — which is exactly the pattern BBA-27 ranks first for browser-based applications. The 2026-07-04 hardening round already fixed the five live bugs (CSRF matcher, `id_token_hint`, scheduler over-eagerness, `UserInfo` token leak, cookie scoping). What remains after this review is **configuration-level**, plus one deployment-topology risk.

| Severity | Count | Findings |
|---|---|---|
| **High** | 1 | F3a (forwarded headers behind Istio) |
| **Medium** | 9 | F1 (PKCE), F2 (XHR entry point), F10 (zombie session after refresh death), F3b (CSP/HSTS), F4 (CORS), F13 (hardcoded post-logout host), F15 (token store keyed by mutable username), F17 (no app-level authorization — tracked), F18 (unrestricted token audience to third party) |
| **Medium → resolved as deviation** | 1 | F7 (back-channel logout) |
| **Low** | 6 | F5, F6, F8, F11, F12, F16 |
| **Info** | 1 | F14 |
| **Accepted deviation** | see §3 | F9 (no rotation) + F7, F8-partial + 3 pre-documented |

**Top 3 actions:**
1. **F3a (P0):** set `server.forward-headers-strategy` (or confirm the ops-mounted config already does — Appendix A). Until then, `{baseUrl}` redirect-URI resolution, `Secure`-cookie logic, and HSTS emission are all undefined behind the TLS-terminating mesh.
2. **P1 trio:** enable PKCE (`OAuth2AuthorizationRequestCustomizers.withPkce()` — one line, BBA-27 MUST), return 401 instead of 302 to expired-session XHRs, and invalidate the servlet session when the refresh token dies (`invalid_grant`) — the last one also becomes the compensating control that justifies skipping back-channel logout (F7).
3. **F13 quick win:** parameterize `app.post-logout-redirect-uri` with an env placeholder like its sibling properties — as committed, logout redirect only works on `myapp.example.com`.

No code was changed in this review; §4 is the plan for a follow-up effort.

---

## §1 — Scope, method, severity rubric

**Reviewed:** all 20 main + 7 test classes under `backend/src`, `backend/pom.xml`, `application.yml`/`application-test.yml`, all of `docs/`, root `pmc-epmmformquerygui-COMPLETE.md` (§8/§14 embedded config), `CLAUDE.md`.
**Not reviewed (out of repo):** the Istio manifests (DestinationRule/VirtualService/Gateway), the Keycloak server configuration beyond `docs/keycloak-realm-checklist.md`, the React SPA source, the ops-mounted `/config/application.yaml` (see Appendix A), and the not-yet-extracted Dockerfile (exists only as `pmc-epmmformquerygui-COMPLETE.md` §14 code block).

**Method:** parallel exploration agents built a code inventory; every candidate finding was (a) re-verified against the actual source with `file:line` evidence, (b) anchored to a normative authority whose section numbers were checked against the published texts on 2026-07-09, and (c) checked against the project docs to separate *gaps* from *documented deliberate deviations*. Findings without an authority citation were dropped or demoted to Info. An independent skill-less counter-review was additionally run as a blind spot check; its verified novel catches were incorporated as F15–F17 (its unverified claims — e.g. a wrong CVE fix version — were corrected before inclusion). A second, skill-guided validation review contributed F18.

**Severity rubric** (severity = risk; priority = fix order — cheap MUST-fixes outrank expensive Medium risks):

| Tier | Definition |
|---|---|
| **High** | Violates a MUST/BCP requirement or breaks security-relevant runtime behavior in the actual deployment topology; dangerous now |
| **Medium** | Violates a MUST with strong compensating controls, or a SHOULD/RECOMMENDED with realistic impact; fix this cycle |
| **Low** | SHOULD-level hygiene, low exploitability; batch into routine work |
| **Info** | Observation or already-tracked item; document only |
| **Accepted deviation** | Deliberate, documented departure with rationale + compensating controls + revisit trigger (§3) |

---

## §2 — Findings

Format per finding: header table, then **Evidence / Impact / Recommendation / Effort / Interactions**.

### §2.1 F3a — No forwarded-headers strategy behind the TLS-terminating mesh

| Severity | Priority | Authority | Files |
|---|---|---|---|
| **High** | P0 | Spring Boot ref *"Running Behind a Front-end Proxy Server"*; RFC 9700 §2.1 (exact redirect URI correctness); BBA-27 cookie `Secure` MUST | `backend/src/main/resources/application.yml:43-51` |

**Evidence.** The entire `server:` block is `port` + `servlet.session` — no `server.forward-headers-strategy`, no `server.tomcat.remoteip.*`, no `ForwardedHeaderFilter` anywhere in the repo (verified by exhaustive search, including the §14 Dockerfile block and §8 embedded config in `pmc-epmmformquerygui-COMPLETE.md`). Deployment is Kubernetes + Istio with TLS terminated upstream (`docs/auth-workflow.md:3`).

**Impact.** Tomcat ignores `X-Forwarded-Proto`/`X-Forwarded-For` by default, so three things become undefined at once: (1) `{baseUrl}` in the OAuth2 `redirect-uri` (`application.yml:64`) can resolve to `http://…` → mismatch against Keycloak's **exact** Valid Redirect URIs; (2) `request.isSecure()` is false → `Secure` cookie behavior and any channel logic are wrong; (3) Spring Security only emits HSTS on requests it believes are secure → HSTS may never be sent (compounds F3b). Login reportedly works today, which means **something unverified is compensating** (most likely the ops-mounted `/config/application.yaml`, or http URIs registered in Keycloak) — an undocumented load-bearing config either way.

**Recommendation.** Set `server.forward-headers-strategy: framework` (or `native`) in the committed `application.yml`, and confirm with ops what the mounted config currently contains (Appendix A). Document the choice next to the "no context-path" rule — it is equally load-bearing.

**Effort:** S (one property + one ops confirmation). **Interactions:** prerequisite for F3b (HSTS); affects F8 (`Secure`/`__Host-` correctness).

### §2.2 F1 — PKCE not enabled for the confidential client

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium | P1 | RFC 9700 §2.1.1 ("for confidential clients, the use of PKCE is RECOMMENDED"; nonce alternative per §4.5.3.2); BBA-27 §6.1 (BFF **MUST** use PKCE) | `application.yml:59-64`; `backend/src/main/java/com/example/epmmformquery/security/SilentAuthRequestResolver.java` |

**Evidence.** The registration has no PKCE configuration and `SilentAuthRequestResolver` wraps `DefaultOAuth2AuthorizationRequestResolver` adding only `prompt=none` — no `OAuth2AuthorizationRequestCustomizers.withPkce()` anywhere. Spring Security does **not** auto-enable PKCE for clients with a secret.

**Impact.** Medium, not High: Spring's `oauth2Login` always sends the OIDC `nonce` for `openid`-scoped clients, which RFC 9700 §4.5.3.2 accepts as the authorization-code-injection countermeasure for confidential OIDC clients, and `state` + exact redirect URIs + client secret remain intact. But BBA-27 makes PKCE an unconditional MUST for the BFF pattern, and the fix is one line.

**Recommendation.** Apply the customizer to the **underlying** resolver that `SilentAuthRequestResolver` wraps, so silent (`prompt=none`) requests carry PKCE too:

```java
DefaultOAuth2AuthorizationRequestResolver delegate = ...;
delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
```

Pair with Keycloak client setting *Proof Key for Code Exchange Code Challenge Method: S256* and add that row to `docs/keycloak-realm-checklist.md`.

**Effort:** S. **Interactions:** none negative; PKCE also gives RFC 9700 §2.1-sanctioned CSRF protection redundancy.

### §2.3 F2 — Expired-session XHR gets a 302 to Keycloak instead of 401

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium | P1 | BBA-27 BFF pattern (API requests are same-origin XHR; the SPA must be able to detect auth expiry); Spring Security `DelegatingAuthenticationEntryPoint`/`HttpStatusEntryPoint` | `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java:165` |

**Evidence.** `exceptionHandling(e -> e.accessDeniedPage(deniedPageUrl))` — no `authenticationEntryPoint` customization, so the `oauth2Login` default (`LoginUrlAuthenticationEntryPoint`) 302-redirects **every** unauthenticated request, XHR included.

**Impact.** When the session expires mid-use, SPA `fetch()` calls to `/rs/**` follow the 302 to Keycloak and die opaquely ("CORS on 302" — the exact failure mode the spec §3 calls the biggest production symptom, here reproduced for every session expiry even with perfect Istio stickiness). The SPA cannot distinguish "session expired, navigate top-level to re-auth silently" from a network error.

**Recommendation.** Register a delegating entry point: requests matching `contextPrefix + "/rs/**"` (or bearing `X-Requested-With: XMLHttpRequest` / `Accept: application/json`) → `HttpStatusEntryPoint(UNAUTHORIZED)`; everything else keeps the redirect. Document the SPA contract: on 401, do `window.location.assign(<current-route>)` to trigger §2B silent re-auth.

**Effort:** S–M (entry point + SPA handling note). **Interactions:** completes the silent re-auth story in `docs/auth-workflow.md` §3 rows c/f.

### §2.4 F10 — Servlet session outlives a dead refresh token (zombie session)

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium | P1 | BBA-27 §6.1: BFF session lifetime SHOULD match refresh-token lifetime; terminate the session when the refresh token becomes invalid | `security/ScheduledTokenRefreshTask.java:101-114`; `security/TokenRefreshFilter.java:66-68` |

**Evidence.** On `invalid_grant` the scheduler calls `removeAuthorizedClient(...)` — but nothing touches the `HttpSession` or the `SessionRegistry`. `TokenRefreshFilter` swallows all refresh failures (`catch (Exception) → log.warn`, request proceeds).

**Impact.** After Keycloak-side revocation or SSO Session Max, the user keeps an *authenticated* servlet session for up to 8h: page navigation works, only `/rs/**` calls fail one by one. Security-wise the IdP has revoked, but the app hasn't heard. This is also why F7 (no back-channel logout) currently has no compensating control.

**Recommendation.** On `invalid_grant`: in `ScheduledTokenRefreshTask`, additionally expire the principal's sessions (`sessionRegistry.getAllSessions(principal, false).forEach(SessionInformation::expireNow)`); in `TokenRefreshFilter`, catch `ClientAuthorizationException`, check for `invalid_grant`, invalidate the current session, and let the request re-enter the auth flow (which is silent while SSO is alive). Keep all other failures retry-only (the spec §5.3 rule stands).

**Effort:** M (+ tests). **Interactions:** **is the compensating control for F7** — after this fix, IdP-side revocation propagates in ≤ one scheduler tick + skew (~2 min) instead of ≤ 8h.

### §2.5 F3b — No security headers configuration (no CSP; HSTS default-only)

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium | P2 | BBA-27 §6.1.3.2 (BFF does not defend against XSS *driving* the session; CSP is the remaining control); OWASP Secure Headers | `config/SecurityConfig.java` (no `.headers(...)` block) |

**Evidence.** The chain never customizes headers — Spring defaults only (X-Content-Type-Options, X-Frame-Options=DENY, Cache-Control). No `Content-Security-Policy`; HSTS relies on default behavior, which requires the request to be seen as secure (broken until F3a).

**Impact.** The BFF keeps tokens out of the browser, but an XSS in the hosted SPA can still ride the session cookie to `/rs/**` freely. CSP is the primary remaining mitigation and is absent.

**Recommendation.** Add `.headers()` with an explicit HSTS policy and a CSP tailored to the Vite bundle (`default-src 'self'`; adjust `script-src`/`style-src`/`connect-src` for the SPA + Keycloak redirect hosts; start with `Content-Security-Policy-Report-Only` to shake out violations).

**Effort:** M (CSP tuning needs SPA verification). **Interactions:** depends on F3a for HSTS to be emitted at all.

### §2.6 F4 — CORS wider than a same-origin BFF needs

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium | P2 | BBA-27 (BFF is same-origin by design); OWASP CORS (never wildcard-with-credentials) | `config/CorsConfig.java:20-31`; `config/SecurityConfig.java:126` |

**Evidence.** `allowedOriginPatterns("https://*.example.com")` + `allowedHeaders("*")` + `allowCredentials(true)` applied to `/**`.

**Impact.** Any page on **any** `example.com` subdomain can make credentialed XHRs to every endpoint and read responses — a compromised sibling subdomain gets full API access with the victim's session, and credentialed wildcard-subdomain CORS largely neutralizes SameSite protections for reads. The SPA itself is same-origin and needs none of this.

**Recommendation.** Delete `CorsConfig` (and the `.cors()` line) unless a cross-origin consumer actually exists; if one does, enumerate its exact origins, restrict to the specific `/rs/**` paths and headers it needs, and document it.

**Effort:** S (delete) / M (tighten). **Interactions:** none.

### §2.7 F7 — No OIDC back-channel logout → resolved as accepted deviation

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium (risk) → **Accepted deviation** (§3) | P2 (decision record only) | OIDC Back-Channel Logout 1.0; Spring Security 6.5 `http.oidcLogout(o -> o.backChannel(...))` | `config/SecurityConfig.java` (no `oidcLogout`); `docs/keycloak-realm-checklist.md` (no back-channel rows) |

**Evidence.** Only RP-initiated (front-channel) logout is implemented; the realm checklist has no back-channel client settings.

**Impact & why deviation, not fix.** Keycloak's back-channel logout is a **server-to-server POST carrying no user affinity signal** — under the Istio `consistentHash` DestinationRule it lands on an arbitrary pod, whose in-memory `SessionRegistry` likely doesn't hold the target session. With pod-local sessions the feature would silently misfire. Correct adoption requires session externalization (spec Appendix A) first.

**Recommendation.** Register in §3 with the F10 fix as compensating control (revocation → refresh failure → session invalidated within ~2 min). Add "back-channel logout adoption" to the spec Appendix A trigger list.

**Effort:** S (documentation). **Interactions:** blocked by Appendix A externalization; compensated by F10.

### §2.8 F13 — `post-logout-redirect-uri` hardcodes a placeholder host

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium (escalated — no override exists) | P1 | OIDC RP-Initiated Logout (`post_logout_redirect_uri` must match the registered value); 12-factor config | `application.yml:36` |

**Evidence.** `post-logout-redirect-uri: https://myapp.example.com${app.context-prefix}/page/logged-out` — the only environment-dependent URL in the file **without** an `${ENV_VAR:default}` placeholder (siblings `DOWNSTREAM_API_URL`, `THIRD_PARTY_API_URL`, `KEYCLOAK_ISSUER_URI` are all parameterized). No profile, manifest, or doc overrides it; only the test profile does.

**Impact.** In any environment whose public host isn't `myapp.example.com`, Keycloak rejects the post-logout redirect (exact matching) — logout lands on Keycloak's error/confirmation page instead of `/page/logged-out`. Unless the ops-mounted config replaces the whole file (undocumented — Appendix A), this is broken everywhere but the example host.

**Recommendation.** `post-logout-redirect-uri: ${POST_LOGOUT_REDIRECT_URI:https://myapp.example.com${app.context-prefix}/page/logged-out}` + note in `docs/keycloak-realm-checklist.md` that the Keycloak *Valid Post Logout Redirect URIs* row must match per environment. Consider `{baseUrl}`-relative resolution once F3a is fixed.

**Effort:** S. **Interactions:** F3a (a correct `{baseUrl}` would allow deriving this value).

### §2.9 F5 — Logout is GET and CSRF-exempt

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Low | P3 | Spring Security docs (logout SHOULD be POST + CSRF); OWASP (forced logout / login CSRF chaining) | `config/SecurityConfig.java:148-159` |

**Evidence.** `PathPatternRequestMatcher … GET logoutUrl` + `csrf.ignoringRequestMatchers(logoutUrl)`.

**Impact.** Any site can force-logout a user via a top-level GET — and because the handler performs RP-initiated logout at Keycloak, it kills the user's **entire SSO session across all realm applications**, not just this app. A cross-app nuisance/DoS primitive, but no data exposure — hence Low.

**Recommendation.** Be honest about the trade-off: GET is load-bearing — RP-initiated logout needs a **top-level navigation** to follow the 302 to Keycloak's `end_session`, which `fetch()` cannot do. Either have the SPA POST to logout (CSRF-protected) and then `window.location.assign()` the returned end-session URL, or keep GET and record it in §3 as a deviation with the nuisance impact stated. Don't oversell this fix.

**Effort:** M (needs SPA change) or S (document). **Interactions:** none.

### §2.10 F6 — `ea_login_hint` cookie lacks an explicit SameSite attribute

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Low | P3 | BBA-27 cookie hygiene (SHOULD SameSite) | `security/LoginSuccessHandler.java:56-61`; `security/SilentAuthFailureHandler.java:69-76`; `security/KeycloakLogoutSuccessHandler.java:44-56` |

**Evidence.** All three sites build `jakarta.servlet.http.Cookie` manually; the servlet `Cookie` API predates SameSite, and `server.servlet.session.cookie.same-site: lax` applies **only** to `JSESSIONID`. (Already noted in `docs/auth-workflow.md` Appendix.)

**Impact.** Minimal — modern browsers default to Lax; the cookie is HttpOnly, Secure, value `"1"`, and only gates the server-side `prompt=none` decision.

**Recommendation.** `cookie.setAttribute("SameSite", "Lax")` (Servlet 6.0+) at all three write/clear sites; assert it in `HintCookieScopeTest`.

**Effort:** S. **Interactions:** batch with F8.

### §2.11 F8 — Session cookie: `SameSite=Lax` (not Strict), no `__Host-` prefix

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Low (Lax half likely becomes a §3 deviation) | P3 | BBA-27: SHOULD `SameSite=Strict`, SHOULD `__Host-` prefix | `application.yml:47-51` |

**Evidence.** `same-site: lax`, name `JSESSIONID`, `Secure`, `HttpOnly`; no context-path → cookie path is `/`.

**Impact & nuance.** *SameSite is site-scoped (registrable domain).* In the doc examples, app (`myapp.example.com`) and Keycloak (`keycloak.example.com`) share `example.com` → the callback redirect is **same-site** and `Strict` would work. But the real issuer host comes from `KEYCLOAK_ISSUER_URI` at deploy time; if prod Keycloak is on a different registrable domain, `Strict` withholds `JSESSIONID` on the callback → `authorization_request_not_found` → the exact bare-401 failure of spec §3.2. `Lax` is the safe, standard choice until the domain relationship is confirmed (Appendix A). The `__Host-` half is feasible **now**: path is already `/`, `Secure` already on.

**Recommendation.** Set `server.servlet.session.cookie.name: __Host-JSESSIONID` immediately. For SameSite: confirm prod domains; if same-site, consider `strict`; else record Lax in §3. (`ea_login_hint` cannot take `__Host-` — it is deliberately path-scoped — and that's fine; it's not a session credential.) Related: the `XSRF-TOKEN` cookie defaults to `Path=/` — host-wide, clobberable by sibling apps on the same gateway host; consider `repository.setCookiePath(contextPrefix)` and `setCookieCustomizer(...)` for SameSite.

**Effort:** S. **Interactions:** F3a (`Secure` detection correctness); F6.

### §2.12 F11 — No dependency vulnerability scanning

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Low | P3 | OWASP Dependency-Check / SSDLC practice | `backend/pom.xml` |

**Evidence.** Only `spring-boot-maven-plugin`; no OWASP dependency-check, no versions-maven-plugin, no CI SCA config in the repo. Patch cadence = manually bumping the Boot parent. Concrete proof of the gap: Boot 3.5.4 ships Spring Security 6.5.2, which sits in the affected range of [CVE-2025-41248](https://spring.io/security/cve-2025-41248/) (method-security authorization bypass, fixed in 6.5.5) — not exploitable here (no method-security annotations), but nothing in the build would have flagged it.

**Recommendation.** Add `org.owasp:dependency-check-maven` (or ecosystem SCA — Dependabot/Renovate/Snyk) to the build or CI; alert on the OAuth2/security starters especially; bump the Boot parent to the latest 3.5.x patch.

**Effort:** S–M (baseline noise triage). **Interactions:** none.

### §2.13 F12 — Security-critical components without tests

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Low | P3 | Project quality bar (7 existing test classes set the pattern) | `backend/src/test/java/...` |

**Evidence.** No tests for: `SilentAuthRequestResolver` (`prompt=none` decision), `SilentAuthFailureHandler` (**the infinite-redirect-loop guard**), `TokenRefreshFilter`, `CorsConfig`, response headers. The loop guard protects the hazard `docs/auth-workflow.md` §2B calls out explicitly, yet has zero coverage.

**Recommendation.** Priority order: (1) failure handler — `login_required` clears cookie + redirects interactive, other errors delegate; (2) resolver — hint cookie present/absent/wrong-value matrix; (3) refresh filter — failure swallow + `invalid_grant` session behavior once F10 lands; (4) header assertions once F3b lands. Follow the existing plain-JUnit + mock-servlet style of `HintCookieScopeTest`.

**Effort:** M. **Interactions:** F10/F3b add the behaviors these tests should pin.

### §2.14 F14 — WebFlux stack carried for WebClient only (Info)

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Info | — | Already tracked: spec §6 (`RestClient` + `OAuth2ClientHttpRequestInterceptor`, Security 6.4+) | `backend/pom.xml` (starter-webflux) |

Extra dependency surface acknowledged and already on the future-proofing track; no new action. Cross-reference only.

### §2.15 F15 — Token store keyed by mutable `preferred_username`

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium | P2 | OIDC Core §5.1: the RP "MUST NOT rely upon" `preferred_username` "being unique" or stable | `application.yml:67` (`user-name-attribute: preferred_username`); `config/TokenRefreshConfig.java:42-48` |

**Evidence.** `Authentication.getName()` — the key of every `InMemoryOAuth2AuthorizedClientService` entry — is `preferred_username`, which Keycloak admins can change and re-assign.

**Impact.** If a username is recycled (offboard alice, onboard a new alice), the new user's session resolves the **previous** user's cached `OAuth2AuthorizedClient` until it is overwritten — cross-user token confusion. Rare in an LDAP-backed enterprise realm, but the OIDC spec explicitly forbids relying on this claim's stability, and the entire token architecture hangs off it.

**Recommendation.** Key token storage on `sub` (stable, unique) while keeping `preferred_username` for display/logging: either set `user-name-attribute: sub` and expose the friendly name via `UserInfoService` (which already reads claims), or wrap the client service to key on `oidcUser.getSubject()`. Note the blast radius: `getName()` feeds logs, `X-Acting-User`, and the scheduler — change deliberately, with tests.

**Effort:** M. **Interactions:** touches the same keying the scheduler and WebClients rely on (`docs/auth-workflow.md` §1 deep-dive).

### §2.16 F16 — Authorized client not purged on logout

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Low | P3 | BBA-27 session-termination hygiene | `config/SecurityConfig.java:148-154` (no token-clearing `LogoutHandler`) |

**Evidence.** Logout invalidates the session and clears cookies, but no handler calls `authorizedClientService.removeAuthorizedClient("keycloak", name)` — the access/refresh tokens stay in the JVM map.

**Impact.** Post-logout, Keycloak has ended the SSO session (refresh would fail), but a still-valid access token (≤ 5 min) lingers in heap, and entries for logged-out users accumulate until pod restart (slow unbounded growth keyed by user count).

**Recommendation.** Add `.addLogoutHandler((req, res, auth) -> { if (auth != null) authorizedClientService.removeAuthorizedClient("keycloak", auth.getName()); })` to the logout DSL.

**Effort:** S. **Interactions:** none; complements F10.

### §2.17 F17 — No application-level authorization: any realm user is fully authorized (known/tracked)

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium (known/tracked) | P2 | Least privilege; Keycloak client authorization options | `config/SecurityConfig.java:129-138` (`anyRequest().authenticated()` only); `security/PrivilegeCheckFilter.java:30-36` (TODO stub); `application.yml:39-40` |

**Evidence.** The only rule beyond permitAll is `authenticated()`. Roles are extracted (`UserInfoService`) but never enforced. `PrivilegeCheckFilter` is a documented stub awaiting LDAP logic (CLAUDE.md) and passes everything through even when `app.privilege.enabled=true` — a false sense of control if ops flips the toggle.

**Impact.** In a shared enterprise realm, "authenticated" = *every* SSO user in the realm, not "authorized for this app". Anyone with realm SSO can use the app and reach `/rs/**`.

**Recommendation.** Tracked work — until the LDAP logic lands: (a) either restrict at Keycloak (require a client role / group for this client) or add a `hasAuthority(...)` rule using the already-extracted `realm_access.roles`; (b) make `PrivilegeCheckFilter` **deny** (503/403) when enabled-but-unimplemented rather than silently allowing.

**Effort:** S (interim guard) / tracked (full LDAP). **Interactions:** F12 (the filter has no tests either).

### §2.18 F18 — Same Keycloak access token sent to the third-party API (no audience restriction)

| Severity | Priority | Authority | Files |
|---|---|---|---|
| Medium (conditional — hinges on whether the partner is genuinely external) | P2 | RFC 9700 §2.3 (access tokens SHOULD be audience-restricted to a specific resource server) | `config/WebClientConfig.java:68-79`; `application.yml:32-33` |

**Evidence.** `thirdPartyWebClient` reuses the same `keycloak` client registration — the identical access token minted for this app's own APIs is attached to calls to `THIRD_PARTY_API_URL` (`https://partner.example.com` placeholder). The javadoc at `WebClientConfig.java:61-67` *acknowledges* the issue and names the fix, but the shipped default does not implement it.

**Impact.** An external partner receives a token that is also valid at the internal downstream APIs — the partner (or anyone who compromises it) can replay it against every other audience the token works for, within its ~5-min lifetime.

**Recommendation.** If the third party is truly external: register a separate `ClientRegistration` (own scopes; ideally `client_credentials` or a token-exchange flow with a partner-specific audience mapper in Keycloak) and point `thirdPartyWebClient` at it, exactly as the javadoc suggests. If "third party" is actually another internal service, rename and document that — then this drops to Info.

**Effort:** M (Keycloak client + registration + config). **Interactions:** Appendix A (is the partner external?).

---

## §3 — Accepted-deviations register

Deviations are *deliberate* departures from an authority, kept honest with rationale, compensating controls, and a revisit trigger.

| # | Deviation | Authority deviated from | Rationale | Compensating controls | Revisit trigger |
|---|---|---|---|---|---|
| D1 (F9) | **No refresh-token rotation** (Keycloak *Revoke Refresh Token: OFF*) | RFC 9700 §2.2.2 — but note the MUST applies to **public** clients ("Refresh tokens for public clients MUST be sender-constrained or use refresh token rotation"); for confidential clients RFC 6749's client binding suffices | Rotation ON races the 3 refresh layers × N pods (spec §4 risk 3) → spurious `invalid_grant` logouts | Confidential-client credential binds the token; tokens never in browser; access token 5 min; SSO Idle/Max ceilings | Token-store externalization (spec Appendix A) with a single locked scheduler |
| D2 (F7) | **No back-channel logout** | OIDC Back-Channel Logout 1.0 | Server-to-server logout POST has no pod affinity → misfires against pod-local sessions | F10 fix (session invalidated ≤ ~2 min after revocation via refresh failure); RP-initiated logout covers user-initiated flows | Session externalization (spec Appendix A) |
| D3 (F8-partial) | **Session cookie `SameSite=Lax`** (not Strict) | BBA-27 SHOULD Strict | Strict breaks the OAuth callback if Keycloak is cross-site; domain relationship is deploy-time (Appendix A) | Lax still blocks cross-site POST; CSRF double-submit independent of SameSite | Confirmation that prod Keycloak shares the app's registrable domain |
| D4 | **In-memory sessions + tokens, per pod** (`InMemoryOAuth2AuthorizedClientService`) | BBA-27 availability guidance | Documented: Istio `consistentHash` DestinationRule is the replication mechanism (`docs/istio-stickiness.md`) | DestinationRule verified per env; silent re-auth recovers restarts | Any spec §4 trigger (stickiness removal, zero-blip deploys, rotation, load) |
| D5 | **Unlimited concurrent sessions** (`maximumSessions(-1)`) | Session-fixation/hardening guides | Registry exists only to feed the scheduler; concurrency limits would break multi-tab UX | Sessions are cookie-bound; tokens principal-keyed (by design) | Security team mandates device limits |
| D6 | **No `server.servlet.context-path`** (prefix-everywhere) | Convention only | Istio VirtualService routes `/gui_epmmFormQuery/*`; explicit prefixes keep Security patterns/controllers/resource handler aligned (`docs/auth-workflow.md` §0) | Consistent `contextPrefix + …` construction; smoke tests | None — permanent design choice |

---

## §4 — Prioritized fix plan

No code changes were made in this review. Effort: S < ½ day, M ≈ 1–2 days, L > 2 days.

| Pri | Finding(s) | Action | Effort | Depends on | Verification | Keycloak-side counterpart |
|---|---|---|---|---|---|---|
| **P0** | F3a | Confirm ops-mounted config (Appendix A), then set `server.forward-headers-strategy: framework` in committed yml; document as load-bearing | S | ops answer | Behind-proxy integration test asserting `{baseUrl}`→https; manual: login on a non-example host | Confirm Valid Redirect URIs are https-only |
| **P1** | F1 | Enable PKCE via `withPkce()` on the wrapped resolver | S | — | Authorization request carries `code_challenge=…&code_challenge_method=S256` (add to `SilentAuthRequestResolver` tests) | Set client *PKCE Code Challenge Method: S256*; add checklist row |
| **P1** | F2 | Delegating auth entry point: 401 for `/rs/**`/XHR, redirect otherwise | S–M | — | MockMvc: expired-session GET `/rs/x` with `Accept: application/json` → 401; browser GET → 302 | — |
| **P1** | F10 | Invalidate sessions on `invalid_grant` (scheduler: `expireNow`; filter: invalidate + re-auth) | M | — | Extend `ScheduledTokenRefreshTaskTest`; new filter test | — |
| **P1** | F13 | Parameterize `post-logout-redirect-uri` with env placeholder | S | — | Config test; manual logout on real host | Per-env *Valid Post Logout Redirect URIs* |
| **P2** | F3b | `.headers()`: explicit HSTS + CSP (start Report-Only) | M | F3a | Header assertions test; SPA console clean under CSP | — |
| **P2** | F4 | Delete `CorsConfig` (or tighten to enumerated origins/paths) | S | confirm no cross-origin consumer | Same-origin SPA still works; cross-origin XHR blocked | — |
| **P2** | F7 | Write D2 decision record; add Appendix-A trigger | S | F10 | Register row present | Note client back-channel URL unset intentionally |
| **P2** | F15 | Key token store on `sub`; keep username for display | M | — | Keying tests; scheduler still refreshes | Confirm `sub` mapper present |
| **P2** | F17 | Interim guard: Keycloak client-role requirement or `hasAuthority` rule; stub filter denies when enabled | S | — | 403 test for role-less user | Client role + assignment |
| **P2** | F18 | Separate client registration (or token exchange) for the third-party API — or reclassify if internal | M | ops answer (partner external?) | Partner call carries partner-audience token | Partner-audience client/mapper |
| **P3** | F8 | `__Host-JSESSIONID` cookie name; SameSite decision after domain confirmation | S | F3a, Appendix A | Cookie asserts in smoke test | — |
| **P3** | F5 | Decide: SPA POST-logout flow or documented GET deviation | S–M | SPA team | — | — |
| **P3** | F6 | `setAttribute("SameSite","Lax")` at 3 hint-cookie sites | S | — | Extend `HintCookieScopeTest` | — |
| **P3** | F16 | `LogoutHandler` removing the authorized client | S | — | Unit test: map entry gone after logout | — |
| **P3** | F11 | Add OWASP dependency-check / SCA to build | S–M | — | CI run with baseline | — |
| **P3** | F12 | Tests: failure-handler loop guard → resolver → refresh filter → headers | M | F10/F3b ideally first | `mvn -B test` green | — |

---

## §5 — Compliance matrix

Status: **Pass** · **Gap→F-nn** · **Deviation→D-n** · **N/A**. Level: MUST / SHOULD / REC(OMMENDED).

### Authorization request

| Requirement | Authority | Level | Status |
|---|---|---|---|
| Authorization-code grant (no implicit/ROPC) | RFC 9700 §2.1.2; BBA-27 | MUST | **Pass** (`application.yml:62`) |
| PKCE on authorization requests | BBA-27 §6.1 MUST; RFC 9700 §2.1.1 REC (confidential) | MUST/REC | **Gap→F1** |
| One-time `state` bound to user agent | RFC 9700 §2.1 | MUST | **Pass** (Spring default resolver kept) |
| OIDC `nonce` present | RFC 9700 §4.5.3.2 | MAY (alternative) | **Pass** |
| No open redirectors | RFC 9700 §2.1 | MUST | **Pass** (redirect targets are compile-time constants; `SilentAuthFailureHandler.java:57-64`) |
| Silent-auth loop guard | project invariant | — | **Pass** (cookie-clear on `login_required`) — untested → F12 |

### Redirect / callback

| Requirement | Authority | Level | Status |
|---|---|---|---|
| Exact redirect URI matching (AS side) | RFC 9700 §2.1 | MUST | **Pass** (`keycloak-realm-checklist.md:20`) |
| `redirect_uri` resolves to the true https origin | RFC 9700 §2.1 (correctness precondition) | MUST | **Gap→F3a** |
| Code single-use, validated `id_token` | OIDC Core / Spring | MUST | **Pass** (framework) |

### Session & cookies

| Requirement | Authority | Level | Status |
|---|---|---|---|
| Session cookie `Secure` | BBA-27 | MUST | **Pass** in config (`application.yml:50`) — runtime correctness gated by **F3a** |
| Session cookie `HttpOnly` | BBA-27 | MUST | **Pass** (`application.yml:51`) |
| Session cookie `SameSite=Strict` | BBA-27 | SHOULD | **Deviation→D3** (Lax) |
| `__Host-` cookie-name prefix | BBA-27 | SHOULD | **Gap→F8** (feasible now) |
| Hint cookie flags complete | BBA-27 hygiene | SHOULD | **Gap→F6** (no SameSite) |
| Session fixation protection | Spring default (`changeSessionId`) | — | **Pass** |
| Session lifetime tied to refresh-token validity | BBA-27 §6.1 | SHOULD | **Gap→F10** |
| Tokens never sent to browser | BBA-27 core BFF property | MUST | **Pass** (`UserInfo` has no token field + serialization test) |

### CSRF

| Requirement | Authority | Level | Status |
|---|---|---|---|
| CSRF defense on state-changing requests | BBA-27 | MUST | **Pass** (`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `SpaCsrfTokenRequestHandler`, `SecurityConfig.java:156-159`; enforced by `CsrfConfigTest`) |
| Exemptions minimal and justified | — | SHOULD | **Pass-with-note** (logout only → F5) |

### Logout

| Requirement | Authority | Level | Status |
|---|---|---|---|
| RP-initiated logout with `id_token_hint` | OIDC RP-Initiated Logout | SHOULD | **Pass** (`KeycloakLogoutSuccessHandler` extends the framework handler; tested) |
| `post_logout_redirect_uri` valid per environment | OIDC RP-Initiated Logout | MUST | **Gap→F13** |
| Back-channel logout | OIDC Back-Channel Logout | SHOULD | **Deviation→D2** |
| Local session + cookies cleared | — | MUST | **Pass** (`invalidateHttpSession`, `deleteCookies`, hint-cookie clear) |
| Logout CSRF-protected | Spring docs | SHOULD | **Gap→F5** (Low) |

### Token lifecycle

| Requirement | Authority | Level | Status |
|---|---|---|---|
| Confidential client; secret server-side only | BBA-27 | MUST | **Pass** (env-injected, `application.yml:60-61`) |
| Refresh rotation or sender-constraining | RFC 9700 §2.2.2 (MUST for public clients) | MUST(public) | **Deviation→D1** (confidential binding per RFC 6749) |
| Refresh-failure classification (`invalid_grant` only drops) | project invariant / spec §5.3 | — | **Pass** (tested) |
| Multi-instance token-store correctness | BBA-27 availability | — | **Deviation→D4** (Istio stickiness) |
| Token store keyed on a stable identifier (`sub`) | OIDC Core §5.1 | MUST NOT rely on `preferred_username` | **Gap→F15** |
| Access tokens audience-restricted per resource server | RFC 9700 §2.3 | SHOULD | **Gap→F18** |
| Authorized client purged on logout | BBA-27 session termination | SHOULD | **Gap→F16** |
| App-level authorization beyond `authenticated()` | least privilege | SHOULD | **Gap→F17** (tracked) |
| No tokens in logs | — | MUST | **Pass** (expiry at TRACE only; usernames not tokens) |

### Headers, CORS, proxy

| Requirement | Authority | Level | Status |
|---|---|---|---|
| Forwarded-headers strategy behind TLS-terminating proxy | Spring Boot ref | MUST(topology) | **Gap→F3a** |
| HSTS emitted | OWASP | SHOULD | **Gap→F3b** |
| CSP for the hosted SPA | BBA-27 XSS considerations | SHOULD | **Gap→F3b** |
| CORS least-privilege / absent when same-origin | OWASP | SHOULD | **Gap→F4** |
| Outbound proxying restricted to known resource servers | BBA-27 §6.1 | MUST | **Pass** (fixed base URLs `application.yml:30-33`; no request-driven targets) |

### Supply chain & process

| Requirement | Authority | Level | Status |
|---|---|---|---|
| Supported framework versions | — | SHOULD | **Pass** (Boot 3.5.4; stale yml header comment noted) |
| Dependency vulnerability scanning | OWASP SSDLC | SHOULD | **Gap→F11** |
| Security components under test | project bar | SHOULD | **Gap→F12** |
| No deprecated security APIs | Spring 7 readiness | SHOULD | **Pass** (`PathPatternRequestMatcher`) |
| Minimal dependency surface | — | REC | **Info→F14** (tracked) |

---

## Appendix A — Environment assumptions requiring ops confirmation

These facts live outside the repo; several finding severities are conditional on them.

| # | Question for ops | Affects | If confirmed "yes" | If "no" |
|---|---|---|---|---|
| A1 | Does the mounted `/config/application.yaml` (Dockerfile `SPRING_CONFIG_LOCATION`, COMPLETE.md §14) set `server.forward-headers-strategy` or Tomcat remoteip? | F3a | Downgrade F3a to Medium ("undocumented load-bearing config — commit it") | F3a stays High; how does login work today? Investigate before P0 fix |
| A2 | Does that mounted config override `app.post-logout-redirect-uri` per environment? | F13 | F13 → Low (hygiene: parameterize anyway) | F13 stays Medium |
| A3 | Do the Istio gateway/sidecars pass `X-Forwarded-Proto: https` to the pod? | F3a fix choice | `framework` strategy works as-is | Need gateway EnvoyFilter/header config first |
| A4 | Is production Keycloak on the same **registrable domain** as the app (e.g. both under `example.com`)? | F8/D3 | `SameSite=Strict` becomes feasible — revisit D3 | Keep Lax (D3 stands) |
| A5 | Is the Keycloak client configured (or configurable) for back-channel logout, and can Keycloak reach pod-local endpoints? | F7/D2 | Still blocked on session externalization (D2 stands) | D2 stands |
| A6 | Does every environment carry the app's `consistentHash` DestinationRule (verification command in `docs/istio-stickiness.md`)? | D4 | D4 stands safely | **Critical** — spec §3 defects are live |
| A7 | Is the `THIRD_PARTY_API_URL` target genuinely external (different trust domain), and does it validate our Keycloak tokens? | F18 | F18 stays Medium — separate registration needed | F18 → Info (rename "third-party") |

---

*Review artifacts: exploration inventory (2 agents), targeted verification (1 agent), authority texts fetched 2026-07-09. Follow-up: execute §4 as a separate remediation effort; re-run this review after P0–P1 land. A reusable version of this review's method lives in `.claude/skills/spring-oidc-oauth2-review/`.*
