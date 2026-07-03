# Keycloak Backend Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the pmc-epmmformquerygui backend into a real source tree, fix its four live defects, and externalize all per-pod state to a shared relational DB so multiple pods serve users without login interruptions.

**Architecture:** Spring Boot BFF (server-side `oauth2Login` against Keycloak) serving the SPA from the jar. Session state moves from Tomcat memory to `spring-session-jdbc`; OAuth2 tokens move from `InMemoryOAuth2AuthorizedClientService` to `JdbcOAuth2AuthorizedClientService`; the background token-refresh scheduler becomes cluster-safe via ShedLock and iterates the shared token table instead of a per-pod `SessionRegistry`.

**Tech Stack:** Java 17, Spring Boot 3.5.x (upgraded from 3.4.5 in Task 3), Spring Security 6.5.x, spring-session-jdbc, ShedLock, PostgreSQL (prod) / H2 (test), Testcontainers (Keycloak + PostgreSQL), Maven.

**Spec:** `docs/superpowers/specs/2026-07-04-keycloak-backend-hardening-design.md`
**Source of truth for extraction:** `pmc-epmmformquerygui-COMPLETE.md` (all code blocks referenced by section number below are verbatim in that file at repo root).

## Global Constraints

- Java 17; Spring Boot parent `3.4.5` for Task 1–2 only, `3.5.4` (or latest 3.5.x) from Task 3 onward.
- `server.servlet.context-path` is NEVER set; every URL carries the explicit `/gui_epmmFormQuery` prefix built from `app.context-prefix` (verbatim spec constraint).
- Package root: `com.example.epmmformquery`. Client registration id: `"keycloak"`.
- Maven project lives in `backend/` (matches the Dockerfile's `COPY backend/target/*.jar`). All `mvn` commands in this plan run from `backend/`.
- Windows host: use `mvn` (no wrapper committed); commands shown work in both PowerShell and bash.
- Commit after every task (steps include the commands).

## File Structure (end state)

```
backend/
├── pom.xml
└── src/
    ├── main/java/com/example/epmmformquery/
    │   ├── EpmmFormQueryApplication.java
    │   ├── config/   SecurityConfig, TokenRefreshConfig, WebClientConfig,
    │   │             WebAppConfig, CorsConfig, SchedulerLockConfig (new)
    │   ├── security/ TokenRefreshFilter, ScheduledTokenRefreshTask,
    │   │             SilentAuthRequestResolver, SilentAuthFailureHandler,
    │   │             LoginSuccessHandler, KeycloakLogoutSuccessHandler (new,
    │   │             replaces CustomLogoutSuccessHandler),
    │   │             SpaCsrfTokenRequestHandler (new), UserInfoService,
    │   │             RequestTracingFilter, SecurityLoggingFilter, PrivilegeCheckFilter
    │   ├── web/      ExternalPageController
    │   ├── client/   DownstreamApiClient, ThirdPartyApiClient
    │   └── model/    UserInfo
    ├── main/resources/
    │   ├── application.yml
    │   └── gui_epmmFormQuery/web/index.html        (placeholder for SPA)
    └── test/
        ├── java/com/example/epmmformquery/ ... (per task)
        └── resources/
            ├── application-test.yml
            ├── schema.sql                          (H2: oauth2 + shedlock tables)
            └── keycloak/epmm-test-realm.json       (Task 14)
docs/db/migrations/  001-spring-session.sql, 002-oauth2-authorized-client.sql,
                     003-shedlock.sql               (PostgreSQL DDL, Task 10/11/13)
```

Responsibilities: `config/*` = bean wiring only; `security/*` = one auth concern per class; scheduler owns cluster-wide refresh; DDL lives in `docs/db/migrations/` for prod and `src/test/resources/schema.sql` for H2.

---

### Task 1: Extract the Maven project from the reference doc

**Files:**
- Create: `backend/pom.xml`, `backend/.gitignore`, all `backend/src/main/...` files listed in the mapping table
- Create: `backend/src/main/resources/gui_epmmFormQuery/web/index.html`

**Interfaces:**
- Produces: a compiling Maven project; every class exactly as printed in `pmc-epmmformquerygui-COMPLETE.md`.

- [ ] **Step 1: Verify prerequisites**

Run: `java -version` (expect 17.x) and `mvn -v` (expect Maven 3.8+ with Java 17). If missing, install Temurin 17 + Maven before continuing.

- [ ] **Step 2: Create the tree and copy each code block VERBATIM from the reference doc**

Copy the fenced code block under each heading of `pmc-epmmformquerygui-COMPLETE.md` to the target path (no edits, no reformatting):

| Reference section heading | Target file |
|---|---|
| §8 `pom.xml` | `backend/pom.xml` |
| §8 `src/main/resources/application.yml` | `backend/src/main/resources/application.yml` |
| §8 `EpmmFormQueryApplication.java` | `backend/src/main/java/com/example/epmmformquery/EpmmFormQueryApplication.java` |
| §9 `config/SecurityConfig.java` | `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java` |
| §9 `config/TokenRefreshConfig.java` | `backend/src/main/java/com/example/epmmformquery/config/TokenRefreshConfig.java` |
| §9 `config/WebClientConfig.java` | `backend/src/main/java/com/example/epmmformquery/config/WebClientConfig.java` |
| §9 `config/WebAppConfig.java` | `backend/src/main/java/com/example/epmmformquery/config/WebAppConfig.java` |
| §9 `config/CorsConfig.java` | `backend/src/main/java/com/example/epmmformquery/config/CorsConfig.java` |
| §10 `security/TokenRefreshFilter.java` | `backend/src/main/java/com/example/epmmformquery/security/TokenRefreshFilter.java` |
| §10 `security/ScheduledTokenRefreshTask.java` | `backend/src/main/java/com/example/epmmformquery/security/ScheduledTokenRefreshTask.java` |
| §10 `security/SilentAuthRequestResolver.java` | `backend/src/main/java/com/example/epmmformquery/security/SilentAuthRequestResolver.java` |
| §10 `security/SilentAuthFailureHandler.java` | `backend/src/main/java/com/example/epmmformquery/security/SilentAuthFailureHandler.java` |
| §10 `security/LoginSuccessHandler.java` | `backend/src/main/java/com/example/epmmformquery/security/LoginSuccessHandler.java` |
| §10 `security/CustomLogoutSuccessHandler.java` | `backend/src/main/java/com/example/epmmformquery/security/CustomLogoutSuccessHandler.java` |
| §10 `security/UserInfoService.java` | `backend/src/main/java/com/example/epmmformquery/security/UserInfoService.java` |
| §10 `security/RequestTracingFilter.java` | `backend/src/main/java/com/example/epmmformquery/security/RequestTracingFilter.java` |
| §10 `security/SecurityLoggingFilter.java` | `backend/src/main/java/com/example/epmmformquery/security/SecurityLoggingFilter.java` |
| §10 `security/PrivilegeCheckFilter.java` | `backend/src/main/java/com/example/epmmformquery/security/PrivilegeCheckFilter.java` |
| §11 `web/ExternalPageController.java` | `backend/src/main/java/com/example/epmmformquery/web/ExternalPageController.java` |
| §12 `client/DownstreamApiClient.java` | `backend/src/main/java/com/example/epmmformquery/client/DownstreamApiClient.java` |
| §12 `client/ThirdPartyApiClient.java` | `backend/src/main/java/com/example/epmmformquery/client/ThirdPartyApiClient.java` |
| §13 `model/UserInfo.java` | `backend/src/main/java/com/example/epmmformquery/model/UserInfo.java` |

- [ ] **Step 3: Add the SPA placeholder and .gitignore**

`backend/src/main/resources/gui_epmmFormQuery/web/index.html`:

```html
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>EPMM Form Query</title></head>
<body><h1>SPA placeholder</h1><p>Replaced by the real bundle at Docker build time.</p></body></html>
```

`backend/.gitignore`:

```
target/
*.class
.idea/
*.iml
.vscode/
```

- [ ] **Step 4: Add the missing `redirect-uri` to application.yml (known latent gap)**

The reference yml never sets `redirect-uri`, so Spring would send `{baseUrl}/login/oauth2/code/keycloak` (unprefixed) to Keycloak while the app listens on the prefixed callback. In `backend/src/main/resources/application.yml`, under `spring.security.oauth2.client.registration.keycloak:` add:

```yaml
            redirect-uri: "{baseUrl}/gui_epmmFormQuery/login/oauth2/code/{registrationId}"
```

- [ ] **Step 5: Compile**

Run: `mvn -B -q compile` (in `backend/`)
Expected: BUILD SUCCESS, no errors. (Warnings about deprecation are fine at this stage.)

- [ ] **Step 6: Commit**

```bash
git add backend/ && git commit -m "feat: extract backend sources from reference doc into Maven project"
```

---

### Task 2: Test harness (offline OAuth2 config + smoke tests)

**Files:**
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/com/example/epmmformquery/EpmmFormQueryApplicationTests.java`
- Create: `backend/src/test/java/com/example/epmmformquery/SecuritySmokeTest.java`

**Interfaces:**
- Produces: `@ActiveProfiles("test")` convention — the test profile defines explicit provider endpoints so NO issuer-uri discovery (and no running Keycloak) is needed. All later test classes reuse this profile.

- [ ] **Step 1: Write the test profile**

`backend/src/test/resources/application-test.yml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: test-client
            client-secret: test-secret
            authorization-grant-type: authorization_code
            scope: openid, profile, email
            redirect-uri: "{baseUrl}/gui_epmmFormQuery/login/oauth2/code/{registrationId}"
        provider:
          keycloak:
            authorization-uri: http://localhost:9999/realms/test/protocol/openid-connect/auth
            token-uri: http://localhost:9999/realms/test/protocol/openid-connect/token
            jwk-set-uri: http://localhost:9999/realms/test/protocol/openid-connect/certs
            user-info-uri: http://localhost:9999/realms/test/protocol/openid-connect/userinfo
            user-name-attribute: preferred_username

app:
  keycloak:
    logout-url: http://localhost:9999/realms/test/protocol/openid-connect/logout
  post-logout-redirect-uri: http://localhost:8080/gui_epmmFormQuery/page/logged-out
  token-refresh:
    enabled: false          # no background scheduler noise in tests
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/example/epmmformquery/EpmmFormQueryApplicationTests.java`:

```java
package com.example.epmmformquery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EpmmFormQueryApplicationTests {
    @Test
    void contextLoads() { }
}
```

`backend/src/test/java/com/example/epmmformquery/SecuritySmokeTest.java`:

```java
package com.example.epmmformquery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecuritySmokeTest {

    @Autowired MockMvc mockMvc;

    @Test
    void protectedPageRedirectsToKeycloakAuthorization() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/web/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        containsString("/gui_epmmFormQuery/oauth2/authorization/keycloak")));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void pingIsPublic() throws Exception {
        // permitAll'd in SecurityConfig; no controller exists so security-pass = 404
        mockMvc.perform(get("/gui_epmmFormQuery/rs/gui/ping"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run tests**

Run: `mvn -B test`
Expected: all 4 tests PASS. If `contextLoads` fails on an unresolved `${KEYCLOAK_CLIENT_ID}`, the test profile is not being picked up — check the `application-test.yml` filename and `@ActiveProfiles("test")`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test && git commit -m "test: add offline test profile and security smoke tests"
```

---

### Task 3: Upgrade Spring Boot 3.4.5 → 3.5.x

**Files:**
- Modify: `backend/pom.xml` (parent version only)

- [ ] **Step 1: Bump the parent**

In `backend/pom.xml` change:

```xml
        <version>3.4.5</version>
```
to
```xml
        <version>3.5.4</version>
```
(Use the latest available 3.5.x — check with `mvn versions:display-parent-versions` if unsure; 3.4.x is past OSS EOL.)

- [ ] **Step 2: Full build + tests**

Run: `mvn -B clean test`
Expected: BUILD SUCCESS, all Task-2 tests pass. If a deprecation turned into an error, fix per the message (Boot 3.4→3.5 is a low-friction bump; Security becomes 6.5.x).

- [ ] **Step 3: Commit**

```bash
git add backend/pom.xml && git commit -m "build: upgrade Spring Boot 3.4.5 -> 3.5.4 (3.4 past OSS EOL)"
```

---

### Task 4: CSRF fix — cookie-based token for the SPA (spec §5.1)

**Files:**
- Create: `backend/src/main/java/com/example/epmmformquery/security/SpaCsrfTokenRequestHandler.java`
- Modify: `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java` (csrf block)
- Test: `backend/src/test/java/com/example/epmmformquery/security/CsrfConfigTest.java`

**Interfaces:**
- Produces: `XSRF-TOKEN` readable cookie on every response; SPA must echo it back in the `X-XSRF-TOKEN` header on POST/PUT/DELETE. CSRF stays ENFORCED on `/gui_epmmFormQuery/rs/**` (the old `"/rs/**"` ignore never matched the real prefixed paths anyway — it is removed, not fixed).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/example/epmmformquery/security/CsrfConfigTest.java`:

```java
package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfConfigTest {

    @Autowired MockMvc mockMvc;

    @Test
    void csrfTokenIsExposedAsReadableCookieForTheSpa() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/rs/gui/ping"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void apiPostWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/gui_epmmFormQuery/rs/anything").with(oauth2Login()))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiPostWithCsrfTokenPassesSecurity() throws Exception {
        // No controller behind this path: passing security means 404, not 403
        mockMvc.perform(post("/gui_epmmFormQuery/rs/anything").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=CsrfConfigTest`
Expected: `csrfTokenIsExposedAsReadableCookieForTheSpa` FAILS (default repository is session-based; no cookie). The other two pass already — they pin current behavior.

- [ ] **Step 3: Implement the handler (verbatim Spring Security SPA pattern)**

`backend/src/main/java/com/example/epmmformquery/security/SpaCsrfTokenRequestHandler.java`:

```java
package com.example.epmmformquery.security;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security's documented SPA pattern: BREACH-protected token for
 * server-rendered use, plain comparison for the value the SPA echoes back
 * in the X-XSRF-TOKEN header. csrfToken.get() forces the deferred token to
 * resolve on every request so CookieCsrfTokenRepository writes the cookie.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                .resolveCsrfTokenValue(request, csrfToken);
    }
}
```

- [ ] **Step 4: Rewire SecurityConfig**

In `SecurityConfig.securityFilterChain(...)` replace:

```java
            .csrf(csrf -> csrf.ignoringRequestMatchers(logoutUrl, "/rs/**"))
```
with:
```java
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .ignoringRequestMatchers(logoutUrl))
```

Add imports:

```java
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import com.example.epmmformquery.security.SpaCsrfTokenRequestHandler;
```

- [ ] **Step 5: Run tests**

Run: `mvn -B test`
Expected: all PASS (including Task 2 smoke tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src && git commit -m "fix: expose CSRF token as SPA-readable cookie; drop dead unprefixed /rs/** ignore"
```

**SPA follow-up (document, out of code scope):** the frontend must read the `XSRF-TOKEN` cookie and send it as `X-XSRF-TOKEN` on mutating requests (axios does this by default).

---

### Task 5: Logout via OidcClientInitiatedLogoutSuccessHandler (spec §5.2)

**Files:**
- Create: `backend/src/main/java/com/example/epmmformquery/security/KeycloakLogoutSuccessHandler.java`
- Delete: `backend/src/main/java/com/example/epmmformquery/security/CustomLogoutSuccessHandler.java`
- Modify: `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java` (inject the new handler)
- Modify: `backend/src/main/resources/application.yml` (remove now-unused `app.keycloak.logout-url`)
- Test: `backend/src/test/java/com/example/epmmformquery/security/KeycloakLogoutSuccessHandlerTest.java`

**Interfaces:**
- Consumes: `ClientRegistrationRepository` (provider metadata supplies `end_session_endpoint`; in prod it arrives via issuer-uri discovery).
- Produces: `KeycloakLogoutSuccessHandler` bean (type `LogoutSuccessHandler`); redirect to Keycloak includes `id_token_hint` and `post_logout_redirect_uri`; clears the hint cookie with `Path=<contextPrefix>` and configurable `Secure` flag (`app.silent-auth.cookie-secure`, default `true`).

- [ ] **Step 1: Write the failing unit test**

`backend/src/test/java/com/example/epmmformquery/security/KeycloakLogoutSuccessHandlerTest.java`:

```java
package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakLogoutSuccessHandlerTest {

    private static final String END_SESSION = "http://kc:9999/realms/test/protocol/openid-connect/logout";

    private KeycloakLogoutSuccessHandler handler() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .clientId("test-client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/gui_epmmFormQuery/login/oauth2/code/{registrationId}")
                .authorizationUri("http://kc:9999/auth")
                .tokenUri("http://kc:9999/token")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", END_SESSION))
                .build();
        return new KeycloakLogoutSuccessHandler(
                new InMemoryClientRegistrationRepository(reg),
                "http://app/gui_epmmFormQuery/page/logged-out",
                "ea_login_hint", "/gui_epmmFormQuery", true);
    }

    private OAuth2AuthenticationToken oidcAuth() {
        OidcIdToken idToken = new OidcIdToken("the-id-token-value",
                Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "u-1", "preferred_username", "leo"));
        DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "preferred_username");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
    }

    @Test
    void redirectsToEndSessionWithIdTokenHint() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler().onLogoutSuccess(req, res, oidcAuth());

        String location = res.getRedirectedUrl();
        assertThat(location).startsWith(END_SESSION);
        assertThat(location).contains("id_token_hint=the-id-token-value");
        assertThat(location).contains("post_logout_redirect_uri=");
    }

    @Test
    void clearsHintCookieScopedToContextPrefix() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler().onLogoutSuccess(new MockHttpServletRequest(), res, oidcAuth());

        Cookie c = res.getCookie("ea_login_hint");
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isZero();
        assertThat(c.getPath()).isEqualTo("/gui_epmmFormQuery");
        assertThat(c.getSecure()).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=KeycloakLogoutSuccessHandlerTest`
Expected: COMPILE FAILURE — `KeycloakLogoutSuccessHandler` does not exist.

- [ ] **Step 3: Implement**

`backend/src/main/java/com/example/epmmformquery/security/KeycloakLogoutSuccessHandler.java`:

```java
package com.example.epmmformquery.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RP-initiated logout done right: delegates end_session URL construction to
 * Spring's OidcClientInitiatedLogoutSuccessHandler, which appends
 * id_token_hint + client_id so Keycloak >=18 skips its logout-confirmation
 * page and honours post_logout_redirect_uri. Also clears the silent-auth
 * hint cookie (scoped to the context prefix) so the next visit is
 * interactive, as the user expects after an explicit logout.
 */
@Component
public class KeycloakLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

    private final String hintCookieName;
    private final String cookiePath;
    private final boolean cookieSecure;

    public KeycloakLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${app.post-logout-redirect-uri}") String postLogoutRedirectUri,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName,
            @Value("${app.context-prefix:/}") String cookiePath,
            @Value("${app.silent-auth.cookie-secure:true}") boolean cookieSecure) {
        super(clientRegistrationRepository);
        setPostLogoutRedirectUri(postLogoutRedirectUri);
        this.hintCookieName = hintCookieName;
        this.cookiePath = cookiePath;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication)
            throws IOException, ServletException {
        Cookie clear = new Cookie(hintCookieName, "");
        clear.setMaxAge(0);
        clear.setPath(cookiePath);
        clear.setHttpOnly(true);
        clear.setSecure(cookieSecure);
        response.addCookie(clear);

        super.onLogoutSuccess(request, response, authentication);
    }
}
```

- [ ] **Step 4: Rewire SecurityConfig and delete the old handler**

In `SecurityConfig`: replace the field/constructor-param type `CustomLogoutSuccessHandler customLogoutSuccessHandler` with `KeycloakLogoutSuccessHandler keycloakLogoutSuccessHandler` (update the import and the `.logoutSuccessHandler(...)` call accordingly). Delete `CustomLogoutSuccessHandler.java`. In `application.yml` remove the `app.keycloak.logout-url` line (and the now-empty `app.keycloak:` block); keep `app.post-logout-redirect-uri`. Remove the same override from `application-test.yml`.

- [ ] **Step 5: Run all tests**

Run: `mvn -B test`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add -A backend/src && git commit -m "fix: RP-initiated logout with id_token_hint via OidcClientInitiatedLogoutSuccessHandler"
```

---

### Task 6: Scheduler — remove tokens only on invalid_grant (spec §5.3)

**Files:**
- Modify: `backend/src/main/java/com/example/epmmformquery/security/ScheduledTokenRefreshTask.java` (catch block only)
- Test: `backend/src/test/java/com/example/epmmformquery/security/ScheduledTokenRefreshTaskTest.java`

**Interfaces:**
- Consumes: existing constructor `(SessionRegistry, OAuth2AuthorizedClientService, OAuth2AuthorizedClientManager)` — unchanged in this task (reworked later in Task 12).
- Produces: `removeAuthorizedClient` is called ONLY when the manager throws `ClientAuthorizationException` with error code `invalid_grant`.

- [ ] **Step 1: Write the failing unit test**

`backend/src/test/java/com/example/epmmformquery/security/ScheduledTokenRefreshTaskTest.java`:

```java
package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScheduledTokenRefreshTaskTest {

    SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    OAuth2AuthorizedClientService clientService = mock(OAuth2AuthorizedClientService.class);
    OAuth2AuthorizedClientManager clientManager = mock(OAuth2AuthorizedClientManager.class);

    ScheduledTokenRefreshTask task;
    OAuth2User user;
    OAuth2AuthorizedClient expiringClient;

    @BeforeEach
    void setUp() {
        task = new ScheduledTokenRefreshTask(sessionRegistry, clientService, clientManager);
        ReflectionTestUtils.setField(task, "skewSeconds", 60L);

        user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_user")),
                Map.of("preferred_username", "leo"), "preferred_username");

        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .clientId("c").clientSecret("s")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/x").authorizationUri("http://kc/a").tokenUri("http://kc/t")
                .build();
        expiringClient = new OAuth2AuthorizedClient(reg, "leo",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok",
                        Instant.now().minusSeconds(600), Instant.now().plusSeconds(10)));

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(user));
        when(sessionRegistry.getAllSessions(user, false))
                .thenReturn(List.of(mock(SessionInformation.class)));
        when(clientService.loadAuthorizedClient("keycloak", "leo")).thenReturn(expiringClient);
    }

    @Test
    void invalidGrantRemovesAuthorizedClient() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "refresh token expired", null), "keycloak"));

        task.refreshExpiringTokens();

        verify(clientService).removeAuthorizedClient("keycloak", "leo");
    }

    @Test
    void transientOAuthErrorKeepsTokens() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("server_error", "kc 502", null), "keycloak"));

        task.refreshExpiringTokens();

        verify(clientService, never()).removeAuthorizedClient(any(), any());
    }

    @Test
    void networkBlipKeepsTokens() {
        when(clientManager.authorize(any()))
                .thenThrow(new IllegalStateException("connection reset"));

        task.refreshExpiringTokens();

        verify(clientService, never()).removeAuthorizedClient(any(), any());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=ScheduledTokenRefreshTaskTest`
Expected: `transientOAuthErrorKeepsTokens` and `networkBlipKeepsTokens` FAIL (current code removes on every exception). `invalidGrantRemovesAuthorizedClient` passes.

- [ ] **Step 3: Fix the catch block**

In `ScheduledTokenRefreshTask.refreshExpiringTokens()`, replace the existing `catch (Exception ex) { ... removeAuthorizedClient ... }` with:

```java
            } catch (ClientAuthorizationException ex) {
                failed++;
                if (OAuth2ErrorCodes.INVALID_GRANT.equals(ex.getError().getErrorCode())) {
                    log.warn("Refresh token invalid for {} (invalid_grant); removing authorized client — user will silently re-auth.", name);
                    clientService.removeAuthorizedClient(REGISTRATION_ID, name);
                } else {
                    log.warn("OAuth2 error refreshing for {} ({}); keeping tokens for retry next tick.",
                            name, ex.getError().getErrorCode());
                }
            } catch (Exception ex) {
                failed++;
                log.warn("Transient failure refreshing for {}: {}; keeping tokens for retry next tick.",
                        name, ex.getMessage());
            }
```

Add imports:

```java
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
```

- [ ] **Step 4: Run tests**

Run: `mvn -B test`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src && git commit -m "fix: scheduler removes tokens only on invalid_grant, retries transient failures"
```

---

### Task 7: Remove the access token from UserInfo (spec §5.4)

**Files:**
- Modify: `backend/src/main/java/com/example/epmmformquery/model/UserInfo.java`
- Modify: `backend/src/main/java/com/example/epmmformquery/security/UserInfoService.java`
- Test: `backend/src/test/java/com/example/epmmformquery/model/UserInfoSerializationTest.java`

**Interfaces:**
- Produces: `UserInfo(username, subject, email, fullName, givenName, familyName, roles)` — 7 components, NO token. New method `UserInfoService.currentAccessToken(): Optional<String>` for server-side callers that genuinely need the raw token.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/example/epmmformquery/model/UserInfoSerializationTest.java`:

```java
package com.example.epmmformquery.model;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserInfoSerializationTest {

    @Test
    void serializedUserInfoNeverContainsATokenField() throws Exception {
        // Compile-level guard: the record must not even have an accessToken component.
        assertThat(Arrays.stream(UserInfo.class.getRecordComponents())
                .map(c -> c.getName()))
                .doesNotContain("accessToken");

        UserInfo me = new UserInfo("leo", "u-1", "leo@example.com",
                "Leo T", "Leo", "T", List.of("admin"));
        String json = new ObjectMapper().writeValueAsString(me);
        assertThat(json).doesNotContain("accessToken");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=UserInfoSerializationTest`
Expected: COMPILE FAILURE — `UserInfo` constructor still takes 8 arguments.

- [ ] **Step 3: Shrink the record**

`UserInfo.java` — remove the `accessToken` component and its comment; the record becomes:

```java
public record UserInfo(
        String username,        // preferred_username (OIDC standard)
        String subject,         // sub claim (Keycloak's stable user id)
        String email,
        String fullName,        // name claim
        String givenName,
        String familyName,
        List<String> roles
) {
    public UserInfo {
        roles = roles == null ? Collections.emptyList() : List.copyOf(roles);
    }

    /** True if the user has the given role (Keycloak realm role). */
    public boolean hasRole(String role) {
        return roles.contains(Objects.requireNonNull(role));
    }
}
```

- [ ] **Step 4: Update UserInfoService**

In `buildUserInfo(...)`: delete the `String accessToken = loadAccessToken(oauth);` line and pass 7 args to the constructor. Replace the private `loadAccessToken` with a public accessor:

```java
    /**
     * Raw bearer token for the rare server-side case WebClient can't cover.
     * Never return this to the SPA.
     */
    public Optional<String> currentAccessToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) {
            return Optional.empty();
        }
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                CLIENT_REGISTRATION_ID, oauth.getName());
        return Optional.ofNullable(client)
                .map(OAuth2AuthorizedClient::getAccessToken)
                .map(t -> t.getTokenValue());
    }
```

- [ ] **Step 5: Run tests, commit**

Run: `mvn -B test` — Expected: all PASS.

```bash
git add backend/src && git commit -m "fix: remove bearer token from UserInfo DTO; explicit currentAccessToken() accessor"
```

---

### Task 8: Cookie scoping + actuator narrowing (spec §5.5)

**Files:**
- Modify: `backend/src/main/java/com/example/epmmformquery/security/LoginSuccessHandler.java`
- Modify: `backend/src/main/java/com/example/epmmformquery/security/SilentAuthFailureHandler.java`
- Modify: `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java` (loginSuccessHandler bean + permitAll list)
- Modify: `backend/src/main/resources/application.yml` (add `app.silent-auth.cookie-secure: true`)
- Test: `backend/src/test/java/com/example/epmmformquery/security/HintCookieScopeTest.java`, extend `SecuritySmokeTest`

**Interfaces:**
- Consumes: `app.silent-auth.cookie-secure` property (introduced by Task 5's handler; now also documented in yml).
- Produces: `LoginSuccessHandler(RequestCache, String defaultTargetUrl, OAuth2AuthorizedClientRepository, String hintCookieName, int hintCookieMaxAge, String cookiePath, boolean cookieSecure)` — two NEW trailing constructor params. Hint cookie `Path=/gui_epmmFormQuery` at all three write/clear sites. Only `/actuator/health` and `/actuator/health/**` are public.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/example/epmmformquery/security/HintCookieScopeTest.java`:

```java
package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

class HintCookieScopeTest {

    @Test
    void loginSuccessHandlerScopesHintCookieToContextPrefix() throws Exception {
        LoginSuccessHandler handler = new LoginSuccessHandler(
                new HttpSessionRequestCache(), "/gui_epmmFormQuery/web/",
                null, "ea_login_hint", 2592000, "/gui_epmmFormQuery", true);

        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res,
                new TestingAuthenticationToken("leo", "n/a"));

        Cookie c = res.getCookie("ea_login_hint");
        assertThat(c).isNotNull();
        assertThat(c.getPath()).isEqualTo("/gui_epmmFormQuery");
        assertThat(c.getSecure()).isTrue();
        assertThat(c.isHttpOnly()).isTrue();
    }
}
```

Add to `SecuritySmokeTest`:

```java
    @Test
    void actuatorInfoIsNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().is3xxRedirection());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=HintCookieScopeTest,SecuritySmokeTest`
Expected: COMPILE FAILURE on the 7-arg `LoginSuccessHandler` constructor; `actuatorInfoIsNotPublic` FAILS (currently 200 via `/actuator/**` permitAll).

- [ ] **Step 3: Implement**

`LoginSuccessHandler`: add fields `String cookiePath; boolean cookieSecure;`, extend the constructor with `String cookiePath, boolean cookieSecure` (after `hintCookieMaxAge`), and in `onAuthenticationSuccess` set `hint.setPath(cookiePath); hint.setSecure(cookieSecure);` instead of the hard-coded values.

`SilentAuthFailureHandler`: inject two more values —

```java
    @Value("${app.context-prefix:/}") — constructor param String cookiePath
    @Value("${app.silent-auth.cookie-secure:true}") — constructor param boolean cookieSecure
```
and in `clearHintCookie` use `c.setPath(cookiePath); c.setSecure(cookieSecure);`.

`SecurityConfig`:
- `loginSuccessHandler(...)` bean: pass `contextPrefix` and a new injected `@Value("${app.silent-auth.cookie-secure:true}") boolean hintCookieSecure` as the two new args.
- permitAll list: replace `"/actuator/**"` with `"/actuator/health", "/actuator/health/**"`.

`application.yml` — under `app.silent-auth:` add:

```yaml
    cookie-secure: true   # set false ONLY for plain-HTTP local dev, or the clear-cookie fails and prompt=none loops
```

- [ ] **Step 4: Run tests, commit**

Run: `mvn -B test` — Expected: all PASS.

```bash
git add backend/src && git commit -m "fix: scope hint cookie to context prefix, configurable secure flag, narrow actuator permitAll"
```

---

### Task 9: Replace AntPathRequestMatcher (spec §6)

**Files:**
- Modify: `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java` (logout matcher)

- [ ] **Step 1: Swap the matcher**

In `securityFilterChain(...)` replace:

```java
                .logoutRequestMatcher(new AntPathRequestMatcher(logoutUrl, "GET"))
```
with:
```java
                .logoutRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, logoutUrl))
```

Replace the `AntPathRequestMatcher` import with:

```java
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
```

- [ ] **Step 2: Guard with a test (logout still routes)**

Add to `SecuritySmokeTest`:

```java
    @Test
    void logoutEndpointIsRoutedViaGet() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/logout")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login()))
                .andExpect(status().is3xxRedirection());
    }
```

- [ ] **Step 3: Run tests, commit**

Run: `mvn -B test` — Expected: all PASS (Security 7-ready matcher).

```bash
git add backend/src && git commit -m "refactor: AntPathRequestMatcher -> PathPatternRequestMatcher (removed in Spring Security 7)"
```

---

### Task 10: spring-session-jdbc — shared HttpSession (spec §4)

**Files:**
- Modify: `backend/pom.xml` (spring-session-jdbc, spring-boot-starter-jdbc, postgresql runtime, h2 test)
- Modify: `backend/src/main/resources/application.yml` (datasource + session block)
- Modify: `backend/src/test/resources/application-test.yml` (H2 datasource)
- Create: `docs/db/migrations/001-spring-session.sql`
- Test: `backend/src/test/java/com/example/epmmformquery/config/SpringSessionJdbcTest.java`

**Interfaces:**
- Produces: `JdbcIndexedSessionRepository` bean (implements `FindByIndexNameSessionRepository` — consumed by Task 12); `SPRING_SESSION` tables; sessions survive pod restarts and are visible to all pods. `spring.session.timeout=8h` replaces the servlet-container timeout.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/example/epmmformquery/config/SpringSessionJdbcTest.java`:

```java
package com.example.epmmformquery.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SpringSessionJdbcTest {

    @Autowired FindByIndexNameSessionRepository<?> sessionRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sessionsAreStoredInTheSharedDatabase() {
        assertThat(sessionRepository).isNotNull();
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
        assertThat(rows).isNotNull();   // table exists and is queryable
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=SpringSessionJdbcTest`
Expected: FAIL — no `FindByIndexNameSessionRepository` bean / no DataSource.

- [ ] **Step 3: Add dependencies**

In `backend/pom.xml` `<dependencies>` add:

```xml
        <dependency>
            <groupId>org.springframework.session</groupId>
            <artifactId>spring-session-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 4: Configure main + test yml**

`application.yml` — add under `spring:`:

```yaml
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://db:5432/epmm}
    username: ${SPRING_DATASOURCE_USERNAME:epmm}
    password: ${SPRING_DATASOURCE_PASSWORD:}
  session:
    timeout: 8h
    jdbc:
      initialize-schema: never    # prod schema applied via docs/db/migrations
```
and DELETE `server.servlet.session.timeout: 8h` (cookie settings under `server.servlet.session.cookie` stay).

`application-test.yml` — add under `spring:`:

```yaml
  datasource:
    url: jdbc:h2:mem:epmm;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
    username: sa
    password: ""
  session:
    jdbc:
      initialize-schema: always
```

- [ ] **Step 5: Create the prod migration**

`docs/db/migrations/001-spring-session.sql` — copy VERBATIM the file `org/springframework/session/jdbc/schema-postgresql.sql` from the `spring-session-jdbc` jar (visible in your IDE's external-libraries view, or extract with `jar -xf`). It creates `SPRING_SESSION` + `SPRING_SESSION_ATTRIBUTES` with the `PRINCIPAL_NAME` index.

- [ ] **Step 6: Run all tests**

Run: `mvn -B test`
Expected: all PASS (Spring Session auto-configures because spring-session-jdbc + DataSource are present).

- [ ] **Step 7: Commit**

```bash
git add backend/ docs/db && git commit -m "feat: externalize HttpSession to spring-session-jdbc (multi-pod shared sessions)"
```

---

### Task 11: JdbcOAuth2AuthorizedClientService — shared tokens (spec §4)

**Files:**
- Modify: `backend/src/main/java/com/example/epmmformquery/config/TokenRefreshConfig.java`
- Create: `backend/src/test/resources/schema.sql`
- Modify: `backend/src/test/resources/application-test.yml` (`spring.sql.init.mode: always`)
- Create: `docs/db/migrations/002-oauth2-authorized-client.sql`
- Test: `backend/src/test/java/com/example/epmmformquery/config/JdbcAuthorizedClientServiceTest.java`

**Interfaces:**
- Produces: `OAuth2AuthorizedClientService` bean is `JdbcOAuth2AuthorizedClientService`; tokens live in table `oauth2_authorized_client` keyed by `(client_registration_id, principal_name)` — consumed by Task 12's expiring-token query.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/example/epmmformquery/config/JdbcAuthorizedClientServiceTest.java`:

```java
package com.example.epmmformquery.config;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JdbcAuthorizedClientServiceTest {

    @Autowired OAuth2AuthorizedClientService service;
    @Autowired ClientRegistrationRepository registrations;

    @Test
    void serviceIsJdbcBacked() {
        assertThat(service).isInstanceOf(JdbcOAuth2AuthorizedClientService.class);
    }

    @Test
    void tokensSurviveARoundTripThroughTheDatabase() {
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(
                registrations.findByRegistrationId("keycloak"), "leo",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok-123",
                        Instant.now(), Instant.now().plusSeconds(300)));

        service.saveAuthorizedClient(client, new TestingAuthenticationToken("leo", "n/a"));

        OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("keycloak", "leo");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("tok-123");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=JdbcAuthorizedClientServiceTest`
Expected: `serviceIsJdbcBacked` FAILS (bean is `InMemoryOAuth2AuthorizedClientService`).

- [ ] **Step 3: Swap the bean**

In `TokenRefreshConfig` replace the `authorizedClientService` bean with:

```java
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            JdbcOperations jdbcOperations,
            ClientRegistrationRepository clientRegistrationRepository) {
        return new JdbcOAuth2AuthorizedClientService(jdbcOperations, clientRegistrationRepository);
    }
```

Imports: add `org.springframework.jdbc.core.JdbcOperations` and `org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService`; remove the `InMemoryOAuth2AuthorizedClientService` import. Update the class-level javadoc ("in-memory" → "JDBC-backed, shared across pods"). Method signature change: the manager bean already takes `OAuth2AuthorizedClientService` — unchanged.

- [ ] **Step 4: Test schema (H2) + prod migration**

`backend/src/test/resources/schema.sql` (H2-compatible; matches Spring Security's canonical `oauth2-client-schema.sql` plus the ShedLock table used from Task 13):

```sql
CREATE TABLE IF NOT EXISTS oauth2_authorized_client (
  client_registration_id  varchar(100)  NOT NULL,
  principal_name          varchar(200)  NOT NULL,
  access_token_type       varchar(100)  NOT NULL,
  access_token_value      blob          NOT NULL,
  access_token_issued_at  timestamp     NOT NULL,
  access_token_expires_at timestamp     NOT NULL,
  access_token_scopes     varchar(1000) DEFAULT NULL,
  refresh_token_value     blob          DEFAULT NULL,
  refresh_token_issued_at timestamp     DEFAULT NULL,
  created_at              timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (client_registration_id, principal_name)
);

CREATE TABLE IF NOT EXISTS shedlock (
  name       varchar(64)  NOT NULL,
  lock_until timestamp    NOT NULL,
  locked_at  timestamp    NOT NULL,
  locked_by  varchar(255) NOT NULL,
  PRIMARY KEY (name)
);
```

`docs/db/migrations/002-oauth2-authorized-client.sql` (PostgreSQL — `bytea` instead of `blob`):

```sql
CREATE TABLE oauth2_authorized_client (
  client_registration_id  varchar(100)  NOT NULL,
  principal_name          varchar(200)  NOT NULL,
  access_token_type       varchar(100)  NOT NULL,
  access_token_value      bytea         NOT NULL,
  access_token_issued_at  timestamp     NOT NULL,
  access_token_expires_at timestamp     NOT NULL,
  access_token_scopes     varchar(1000) DEFAULT NULL,
  refresh_token_value     bytea         DEFAULT NULL,
  refresh_token_issued_at timestamp     DEFAULT NULL,
  created_at              timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (client_registration_id, principal_name)
);
```

`application-test.yml` — add under `spring:`:

```yaml
  sql:
    init:
      mode: always
```

- [ ] **Step 5: Run all tests, commit**

Run: `mvn -B test` — Expected: all PASS.

```bash
git add backend/ docs/db && git commit -m "feat: JDBC-backed OAuth2AuthorizedClientService (shared token store)"
```

---

### Task 12: Cluster-aware scheduler; drop SessionRegistry (spec §4)

**Files:**
- Modify: `backend/src/main/java/com/example/epmmformquery/security/ScheduledTokenRefreshTask.java` (full rewrite below)
- Modify: `backend/src/main/java/com/example/epmmformquery/config/TokenRefreshConfig.java` (delete `sessionRegistry` + `httpSessionEventPublisher` beans)
- Modify: `backend/src/main/java/com/example/epmmformquery/config/SecurityConfig.java` (delete `sessionManagement` block + `SessionRegistry` field/param/import)
- Modify: `backend/src/test/java/com/example/epmmformquery/security/ScheduledTokenRefreshTaskTest.java` (rewritten below)

**Interfaces:**
- Consumes: `oauth2_authorized_client` table (Task 11), `FindByIndexNameSessionRepository` (Task 10).
- Produces: `ScheduledTokenRefreshTask(JdbcOperations, FindByIndexNameSessionRepository<? extends Session>, OAuth2AuthorizedClientService, OAuth2AuthorizedClientManager)`. Design note (refinement over the spec's `SpringSessionBackedSessionRegistry` wording): the shared token table itself is the "registry" — the scheduler queries it for expiring tokens and uses the session repository's principal-name index to skip users with no live session anywhere in the cluster. `SessionRegistry` had no other consumer, so the concurrency machinery is removed outright.

- [ ] **Step 1: Rewrite the unit test**

Replace the whole `ScheduledTokenRefreshTaskTest.java` with:

```java
package com.example.epmmformquery.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ScheduledTokenRefreshTaskTest {

    JdbcOperations jdbc = mock(JdbcOperations.class);
    FindByIndexNameSessionRepository sessionRepository = mock(FindByIndexNameSessionRepository.class);
    OAuth2AuthorizedClientService clientService = mock(OAuth2AuthorizedClientService.class);
    OAuth2AuthorizedClientManager clientManager = mock(OAuth2AuthorizedClientManager.class);

    ScheduledTokenRefreshTask task;
    OAuth2AuthorizedClient expiringClient;

    @BeforeEach
    void setUp() {
        task = new ScheduledTokenRefreshTask(jdbc, sessionRepository, clientService, clientManager);
        ReflectionTestUtils.setField(task, "skewSeconds", 60L);

        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .clientId("c").clientSecret("s")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/x").authorizationUri("http://kc/a").tokenUri("http://kc/t")
                .build();
        expiringClient = new OAuth2AuthorizedClient(reg, "leo",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok",
                        Instant.now().minusSeconds(600), Instant.now().plusSeconds(10)));

        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("leo"));
        when(clientService.loadAuthorizedClient("keycloak", "leo")).thenReturn(expiringClient);
        when(sessionRepository.findByPrincipalName("leo"))
                .thenReturn(Map.of("s1", new MapSession()));
    }

    @Test
    void refreshesUserWithLiveSessionAndExpiringToken() {
        when(clientManager.authorize(any())).thenReturn(expiringClient);
        task.refreshExpiringTokens();
        verify(clientManager).authorize(any());
    }

    @Test
    void skipsUserWithNoLiveSessionAnywhereInTheCluster() {
        when(sessionRepository.findByPrincipalName("leo")).thenReturn(Map.of());
        task.refreshExpiringTokens();
        verify(clientManager, never()).authorize(any());
    }

    @Test
    void invalidGrantRemovesAuthorizedClient() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "refresh token expired", null), "keycloak"));
        task.refreshExpiringTokens();
        verify(clientService).removeAuthorizedClient("keycloak", "leo");
    }

    @Test
    void transientFailureKeepsTokens() {
        when(clientManager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("server_error", "kc 502", null), "keycloak"));
        task.refreshExpiringTokens();
        verify(clientService, never()).removeAuthorizedClient(any(), any());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=ScheduledTokenRefreshTaskTest`
Expected: COMPILE FAILURE — constructor mismatch.

- [ ] **Step 3: Rewrite the scheduler**

Replace the body of `ScheduledTokenRefreshTask.java` with:

```java
package com.example.epmmformquery.security;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Cluster-aware background refresh. The shared oauth2_authorized_client table
 * is the source of truth: query it for tokens entering the skew window, skip
 * principals with no live session anywhere in the cluster (Spring Session's
 * principal-name index), refresh the rest. Runs under a ShedLock (Task 13) so
 * only one pod executes the scan per tick.
 */
@Component
@ConditionalOnProperty(name = "app.token-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTokenRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTokenRefreshTask.class);
    private static final String REGISTRATION_ID = "keycloak";
    private static final String EXPIRING_SQL =
            "SELECT principal_name FROM oauth2_authorized_client "
            + "WHERE client_registration_id = ? AND access_token_expires_at <= ?";

    private final JdbcOperations jdbc;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final OAuth2AuthorizedClientService clientService;
    private final OAuth2AuthorizedClientManager clientManager;

    @Value("${app.token-refresh.skew-seconds:60}")
    private long skewSeconds;

    public ScheduledTokenRefreshTask(JdbcOperations jdbc,
                                     FindByIndexNameSessionRepository<? extends Session> sessionRepository,
                                     OAuth2AuthorizedClientService clientService,
                                     OAuth2AuthorizedClientManager clientManager) {
        this.jdbc = jdbc;
        this.sessionRepository = sessionRepository;
        this.clientService = clientService;
        this.clientManager = clientManager;
    }

    @Scheduled(fixedRateString = "${app.token-refresh.schedule-rate-ms:60000}")
    public void refreshExpiringTokens() {
        Timestamp cutoff = Timestamp.from(Instant.now().plus(Duration.ofSeconds(skewSeconds)));
        List<String> expiring = jdbc.queryForList(EXPIRING_SQL, String.class, REGISTRATION_ID, cutoff);

        int refreshed = 0, failed = 0, skipped = 0;
        for (String name : expiring) {
            if (sessionRepository.findByPrincipalName(name).isEmpty()) {
                skipped++;   // logged in nowhere in the cluster; let the row age out
                continue;
            }
            OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(REGISTRATION_ID, name);
            if (client == null) {
                continue;    // removed between query and load
            }
            try {
                Authentication synthetic =
                        new UsernamePasswordAuthenticationToken(name, null, List.of());
                OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
                        .withAuthorizedClient(client)
                        .principal(synthetic)
                        .build();
                if (clientManager.authorize(req) != null) {
                    refreshed++;
                    log.debug("Background-refreshed token for {}", name);
                }
            } catch (ClientAuthorizationException ex) {
                failed++;
                if (OAuth2ErrorCodes.INVALID_GRANT.equals(ex.getError().getErrorCode())) {
                    log.warn("Refresh token invalid for {} (invalid_grant); removing authorized client — user will silently re-auth.", name);
                    clientService.removeAuthorizedClient(REGISTRATION_ID, name);
                } else {
                    log.warn("OAuth2 error refreshing for {} ({}); keeping tokens for retry next tick.",
                            name, ex.getError().getErrorCode());
                }
            } catch (Exception ex) {
                failed++;
                log.warn("Transient failure refreshing for {}: {}; keeping tokens for retry next tick.",
                        name, ex.getMessage());
            }
        }
        if (!expiring.isEmpty()) {
            log.info("Token refresh scan: expiring={} refreshed={} failed={} skipped={}",
                    expiring.size(), refreshed, failed, skipped);
        }
    }
}
```

- [ ] **Step 4: Remove the SessionRegistry machinery**

`TokenRefreshConfig`: delete the `sessionRegistry()` and `httpSessionEventPublisher()` beans and their imports (`SessionRegistry`, `SessionRegistryImpl`, `HttpSessionEventPublisher`).

`SecurityConfig`: delete the `sessionRegistry` field, constructor parameter, its import, and the entire block:

```java
            .sessionManagement(s -> s
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry))
```

- [ ] **Step 5: Run all tests**

Run: `mvn -B test`
Expected: all PASS (context tests confirm the new wiring resolves).

- [ ] **Step 6: Commit**

```bash
git add backend/src && git commit -m "feat: cluster-aware token refresh driven by shared token table; remove per-pod SessionRegistry"
```

---

### Task 13: ShedLock — one scheduler tick cluster-wide (spec §4)

**Files:**
- Modify: `backend/pom.xml` (ShedLock deps)
- Create: `backend/src/main/java/com/example/epmmformquery/config/SchedulerLockConfig.java`
- Modify: `backend/src/main/java/com/example/epmmformquery/security/ScheduledTokenRefreshTask.java` (`@SchedulerLock` on the method)
- Create: `docs/db/migrations/003-shedlock.sql`
- Test: `backend/src/test/java/com/example/epmmformquery/config/SchedulerLockTest.java`

**Interfaces:**
- Consumes: `shedlock` table (already in test `schema.sql` from Task 11).
- Produces: `LockProvider` bean; the refresh scan named `"refreshExpiringTokens"` executes on at most one pod per tick.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/example/epmmformquery/config/SchedulerLockTest.java`:

```java
package com.example.epmmformquery.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SchedulerLockTest {

    @Autowired LockProvider lockProvider;

    @Test
    void secondHolderCannotAcquireTheSameLock() {
        LockConfiguration cfg = new LockConfiguration(Instant.now(),
                "refreshExpiringTokens-test", Duration.ofSeconds(30), Duration.ofSeconds(1));

        Optional<SimpleLock> first = lockProvider.lock(cfg);
        assertThat(first).isPresent();

        Optional<SimpleLock> second = lockProvider.lock(cfg);
        assertThat(second).isEmpty();     // held by "the other pod"

        first.get().unlock();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B test -Dtest=SchedulerLockTest`
Expected: COMPILE FAILURE — ShedLock not on classpath.

- [ ] **Step 3: Add dependencies**

In `backend/pom.xml` (versions current at plan time — use latest 6.x):

```xml
        <dependency>
            <groupId>net.javacrumbs.shedlock</groupId>
            <artifactId>shedlock-spring</artifactId>
            <version>6.3.0</version>
        </dependency>
        <dependency>
            <groupId>net.javacrumbs.shedlock</groupId>
            <artifactId>shedlock-provider-jdbc-template</artifactId>
            <version>6.3.0</version>
        </dependency>
```

- [ ] **Step 4: Implement config + annotation**

`backend/src/main/java/com/example/epmmformquery/config/SchedulerLockConfig.java`:

```java
package com.example.epmmformquery.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB-backed distributed lock so the token-refresh scan runs on exactly one
 * pod per tick. usingDbTime() avoids pod clock-skew issues.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT55S")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
```

In `ScheduledTokenRefreshTask.refreshExpiringTokens()` add above `@Scheduled`:

```java
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "refreshExpiringTokens", lockAtMostFor = "PT55S", lockAtLeastFor = "PT5S")
```

`docs/db/migrations/003-shedlock.sql`:

```sql
CREATE TABLE shedlock (
  name       varchar(64)  NOT NULL,
  lock_until timestamp    NOT NULL,
  locked_at  timestamp    NOT NULL,
  locked_by  varchar(255) NOT NULL,
  PRIMARY KEY (name)
);
```

- [ ] **Step 5: Run all tests, commit**

Run: `mvn -B test` — Expected: all PASS.

```bash
git add backend/ docs/db && git commit -m "feat: ShedLock guards the refresh scheduler (one pod per tick)"
```

---

### Task 14: Cross-instance integration test (Testcontainers, spec §9)

**Files:**
- Modify: `backend/pom.xml` (Testcontainers deps)
- Create: `backend/src/test/resources/keycloak/epmm-test-realm.json`
- Test: `backend/src/test/java/com/example/epmmformquery/it/CrossInstanceLoginIT.java`

**Interfaces:**
- Consumes: everything built so far. Proves spec §9's headline scenario: a login INITIATED on instance A COMPLETES on instance B (shared session + shared `state`) — the exact flow that produced bare 401s pre-hardening.
- Requires Docker. The test self-skips when Docker is absent (`@Testcontainers(disabledWithoutDocker = true)`); first run pulls images (slow).

- [ ] **Step 1: Add dependencies**

`backend/pom.xml` (versions current at plan time — use latest):

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.21.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>1.21.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.github.dasniko</groupId>
            <artifactId>testcontainers-keycloak</artifactId>
            <version>3.7.0</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Realm import file**

`backend/src/test/resources/keycloak/epmm-test-realm.json`:

```json
{
  "realm": "epmm-test",
  "enabled": true,
  "ssoSessionIdleTimeout": 36000,
  "ssoSessionMaxLifespan": 50400,
  "accessTokenLifespan": 300,
  "revokeRefreshToken": false,
  "clients": [
    {
      "clientId": "epmm-client",
      "secret": "epmm-secret",
      "protocol": "openid-connect",
      "publicClient": false,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "redirectUris": [
        "http://localhost:18081/gui_epmmFormQuery/login/oauth2/code/keycloak",
        "http://localhost:18082/gui_epmmFormQuery/login/oauth2/code/keycloak"
      ],
      "attributes": { "post.logout.redirect.uris": "+" }
    }
  ],
  "users": [
    {
      "username": "testuser",
      "enabled": true,
      "email": "testuser@example.com",
      "firstName": "Test",
      "lastName": "User",
      "credentials": [ { "type": "password", "value": "testpass", "temporary": false } ]
    }
  ]
}
```

- [ ] **Step 3: Write the integration test**

`backend/src/test/java/com/example/epmmformquery/it/CrossInstanceLoginIT.java`:

```java
package com.example.epmmformquery.it;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.epmmformquery.EpmmFormQueryApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the multi-pod fix: a login INITIATED on instance A (port 18081)
 * COMPLETES on instance B (port 18082), because sessions (and the saved
 * OAuth2 authorization request/state) live in the shared database.
 * Pre-hardening this produced authorization_request_not_found -> bare 401.
 */
@Testcontainers(disabledWithoutDocker = true)
class CrossInstanceLoginIT {

    static final int PORT_A = 18081;
    static final int PORT_B = 18082;

    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
            .withRealmImportFile("keycloak/epmm-test-realm.json");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static ConfigurableApplicationContext instanceA;
    static ConfigurableApplicationContext instanceB;

    @BeforeAll
    static void startInstances() {
        instanceA = startInstance(PORT_A, true);
        instanceB = startInstance(PORT_B, false);
    }

    @AfterAll
    static void stopInstances() {
        if (instanceA != null) instanceA.close();
        if (instanceB != null) instanceB.close();
    }

    static ConfigurableApplicationContext startInstance(int port, boolean initSchema) {
        return new SpringApplicationBuilder(EpmmFormQueryApplication.class)
                .properties(
                    "server.port=" + port,
                    "server.servlet.session.cookie.secure=false",   // plain-HTTP test
                    "spring.datasource.url=" + postgres.getJdbcUrl(),
                    "spring.datasource.username=" + postgres.getUsername(),
                    "spring.datasource.password=" + postgres.getPassword(),
                    "spring.session.jdbc.initialize-schema=" + (initSchema ? "always" : "never"),
                    "spring.sql.init.mode=" + (initSchema ? "always" : "never"),
                    "spring.security.oauth2.client.registration.keycloak.client-id=epmm-client",
                    "spring.security.oauth2.client.registration.keycloak.client-secret=epmm-secret",
                    "spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email",
                    "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/gui_epmmFormQuery/login/oauth2/code/{registrationId}",
                    "spring.security.oauth2.client.provider.keycloak.issuer-uri="
                            + keycloak.getAuthServerUrl() + "/realms/epmm-test",
                    "app.post-logout-redirect-uri=http://localhost:" + port + "/gui_epmmFormQuery/page/logged-out",
                    "app.silent-auth.cookie-secure=false",
                    "app.token-refresh.enabled=false")
                .run();
    }

    @Test
    void loginInitiatedOnInstanceACompletesOnInstanceB() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // 1. Hit a protected page on A -> redirect to A's authorization endpoint
        HttpResponse<String> r1 = get(http, url(PORT_A, "/gui_epmmFormQuery/web/"));
        assertThat(r1.statusCode()).isEqualTo(302);

        // 2. Follow to A's /oauth2/authorization/keycloak -> redirect to Keycloak /auth
        HttpResponse<String> r2 = get(http, absolute(PORT_A, r1.headers().firstValue("Location").orElseThrow()));
        assertThat(r2.statusCode()).isIn(302, 303);
        String kcAuthUrl = r2.headers().firstValue("Location").orElseThrow();
        assertThat(kcAuthUrl).contains("/realms/epmm-test/");

        // 3. GET the Keycloak login page, extract the form action
        HttpResponse<String> r3 = get(http, kcAuthUrl);
        assertThat(r3.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("action=\"([^\"]+)\"").matcher(r3.body());
        assertThat(m.find()).as("login form action in Keycloak page").isTrue();
        String formAction = m.group(1).replace("&amp;", "&");

        // 4. Submit credentials -> Keycloak redirects to the app callback (port A)
        String form = "username=" + enc("testuser") + "&password=" + enc("testpass")
                + "&credentialId=";
        HttpResponse<String> r4 = http.send(HttpRequest.newBuilder(URI.create(formAction))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r4.statusCode()).isEqualTo(302);
        String callback = r4.headers().firstValue("Location").orElseThrow();
        assertThat(callback).contains(":" + PORT_A + "/gui_epmmFormQuery/login/oauth2/code/keycloak");

        // 5. THE POINT: deliver the callback to instance B instead of A
        String callbackOnB = callback.replace(":" + PORT_A + "/", ":" + PORT_B + "/");
        HttpResponse<String> r5 = get(http, callbackOnB);
        assertThat(r5.statusCode())
                .as("callback on the OTHER pod must complete the login (shared session/state)")
                .isEqualTo(302);
        assertThat(r5.headers().firstValue("Location").orElseThrow())
                .endsWith("/gui_epmmFormQuery/web/");

        // 6. Authenticated request on B serves the SPA without a redirect
        HttpResponse<String> r6 = get(http, url(PORT_B, "/gui_epmmFormQuery/web/"));
        assertThat(r6.statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }

    private static String absolute(int port, String location) {
        return location.startsWith("http") ? location : url(port, location);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Run it (Docker required)**

Run: `mvn -B test -Dtest=CrossInstanceLoginIT`
Expected: PASS (first run pulls the Keycloak + Postgres images — several minutes). Without Docker: test reports SKIPPED, build still green.

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "test: cross-instance login integration test (Testcontainers Keycloak + Postgres)"
```

---

### Task 15: Documentation — teaching material + realm checklist (spec §7, §8)

**Files:**
- Modify: `pmc-epmmformquerygui-COMPLETE.md` (append §17, correct §7 wording)
- Create: `docs/keycloak-realm-checklist.md`
- Modify: `CLAUDE.md` (workspace now has a real source tree)

- [ ] **Step 1: Append the login-form matrix to the reference doc**

Add a new section `## 17. When does the user see the login form again?` at the end of `pmc-epmmformquerygui-COMPLETE.md`, containing the full 13-row matrix from spec §2 (copy the table and the two summary paragraphs — "one-line summary" and the "deliberate trade-off" note — verbatim from `docs/superpowers/specs/2026-07-04-keycloak-backend-hardening-design.md`).

- [ ] **Step 2: Correct §7 of the reference doc**

In §7 "Keycloak realm settings" change:
- `SSO Session Idle: sized to real user idle (e.g. 2–4h)` → `SSO Session Idle: MUST be >= servlet session timeout (8h) — use 10h`
- `Refresh Token Max Reuse: 0 (rotation off) to avoid concurrent-refresh races` → `Revoke Refresh Token: OFF (this switch controls rotation; "Refresh Token Max Reuse" only applies when it is ON) — avoids concurrent-refresh races`

- [ ] **Step 3: Write the realm checklist**

`docs/keycloak-realm-checklist.md`:

```markdown
# Keycloak realm checklist — pmc-epmmformquerygui (production)

Where: Keycloak admin console → realm → Realm settings → Sessions / Tokens,
and Clients → <client> → Settings / Advanced.

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
```

- [ ] **Step 4: Update CLAUDE.md**

In `CLAUDE.md`, replace the "What this workspace is" paragraph statement that no source tree exists with: the Maven project now lives in `backend/` (extracted from the reference doc in Task 1 of `docs/superpowers/plans/2026-07-04-keycloak-backend-hardening.md`); the reference doc remains the narrative/teaching document, and `docs/keycloak-realm-checklist.md` holds the realm values. Keep the architecture and constraint sections, updating: token storage is now JDBC-backed (spring-session-jdbc + JdbcOAuth2AuthorizedClientService + ShedLock), and the build/test command is `mvn -B test` in `backend/`.

- [ ] **Step 5: Commit**

```bash
git add pmc-epmmformquerygui-COMPLETE.md docs/ CLAUDE.md
git commit -m "docs: login-form matrix, corrected realm guidance, realm checklist, CLAUDE.md refresh"
```

---

## Post-plan verification (manual, on the cluster — from spec §9)

Not tasks for the coding agent; run these when deploying:

1. Apply `docs/db/migrations/*.sql` to the shared PostgreSQL before rollout.
2. Kill one pod mid-session → user continues without login form or API errors.
3. Remove ingress stickiness (if present) → login succeeds on every attempt.
4. Stop the ShedLock-holding pod → another pod's scan takes over within one tick (watch for the "Token refresh scan" log line moving).
5. Spot-check matrix rows a, c, d, f, i from the reference doc §17.
