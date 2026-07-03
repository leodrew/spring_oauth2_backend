# Keycloak Backend Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the pmc-epmmformquerygui backend into a real source tree, fix its four live defects, and future-proof it (Boot 3.5.x, Security-7-ready matchers) — multi-pod correctness is provided by the verified Istio `consistentHash` DestinationRule (spec §4 revision 2), so per-pod in-memory state stays.

**Architecture:** Spring Boot BFF (server-side `oauth2Login` against Keycloak) serving the SPA from the jar. State remains in-memory per pod; the Istio DestinationRule pins each user to one pod and is documented as a load-bearing auth dependency. DB externalization is deferred (spec Appendix A; the dropped Tasks 10–14 live in this file's git history).

**Tech Stack:** Java 17, Spring Boot 3.5.x (upgraded from 3.4.5 in Task 3), Spring Security 6.5.x, Maven.

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
    │   │             WebAppConfig, CorsConfig
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
            └── application-test.yml
```

Responsibilities: `config/*` = bean wiring only; `security/*` = one auth concern per class.

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
- Consumes: existing constructor `(SessionRegistry, OAuth2AuthorizedClientService, OAuth2AuthorizedClientManager)` — unchanged.
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

### Task 10: Documentation — teaching material + realm checklist (spec §7, §8)

**Files:**
- Modify: `pmc-epmmformquerygui-COMPLETE.md` (append §17, correct §7 wording)
- Create: `docs/keycloak-realm-checklist.md`
- Create: `docs/istio-stickiness.md`
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
| Client: Valid Redirect URIs | https://<host>/gui_epmmFormQuery/login/oauth2/code/keycloak | Exact match — wrong URI is the usual cause of a 4xx hop in the silent-auth chain |
| Client: Valid Post Logout Redirect URIs | https://<host>/gui_epmmFormQuery/page/logged-out | Required for RP-initiated logout with id_token_hint |
| Token mapper | realm_access.roles present | UserInfoService reads roles from this claim |

Deliberate design note: while any servlet session is live, the background
refresher resets SSO Idle every ~5 min — SSO Idle is NOT an idle-logout
control here; SSO Session Max is the only hard stop.
```

- [ ] **Step 3b: Write the Istio stickiness doc**

`docs/istio-stickiness.md`:

```markdown
# Istio stickiness — load-bearing auth dependency

Sessions and OAuth2 tokens are in-memory PER POD. The app's DestinationRule
consistentHash policy is what makes multi-pod operation correct. Removing or
weakening it reintroduces intermittent login 401s
(authorization_request_not_found) and broken API calls.

## Current state: useSourceIp (fragile)

The app's DestinationRule uses `consistentHash.useSourceIp: true`. This hashes
whatever source IP the app's sidecar sees:
- Via the ingress gateway that IP is often the GATEWAY POD's IP → one gateway
  replica funnels all its users to one app pod (hotspot), and multiple gateway
  replicas can route the SAME user to DIFFERENT app pods (stickiness broken).
- Corporate NAT: many users share one IP → one pod takes them all.
- Mobile clients: IP changes mid-session → user hops pods (one auth blip).

## Recommended: httpCookie

Replace the app DestinationRule's loadBalancer block with:

    trafficPolicy:
      loadBalancer:
        consistentHash:
          httpCookie:
            name: epmm-affinity
            ttl: 0s        # session cookie, issued by Envoy automatically

Cookie hashing is immune to gateway hops, NAT, and client IP changes.

## Verification

    kubectl get destinationrule -A -o yaml | grep -B4 -A6 consistentHash

Confirm the policy exists in EVERY environment and targets the APP's Service
host (a rule on the Keycloak service does not protect the app).
```

- [ ] **Step 4: Update CLAUDE.md**

In `CLAUDE.md`, replace the "What this workspace is" paragraph statement that no source tree exists with: the Maven project now lives in `backend/` (extracted from the reference doc in Task 1 of `docs/superpowers/plans/2026-07-04-keycloak-backend-hardening.md`); the reference doc remains the narrative/teaching document, and `docs/keycloak-realm-checklist.md` holds the realm values. Keep the architecture and constraint sections, adding two updates: (1) the build/test command is `mvn -B test` in `backend/`; (2) multi-pod correctness depends on the Istio `consistentHash` DestinationRule — sessions/tokens are in-memory per pod, and that DestinationRule must never be weakened without first implementing spec Appendix A (DB externalization).

- [ ] **Step 5: Commit**

```bash
git add pmc-epmmformquerygui-COMPLETE.md docs/ CLAUDE.md
git commit -m "docs: login-form matrix, corrected realm guidance, realm checklist, CLAUDE.md refresh"
```

---

## Post-plan verification (manual, on the cluster — from spec §9)

Not tasks for the coding agent; run these when deploying:

1. Confirm the `DestinationRule` `consistentHash` policy exists in EVERY environment (it is now a documented auth dependency): `kubectl get destinationrule -A -o yaml | grep -B2 -A4 consistentHash`
2. Kill one pod mid-session → affected users recover silently (one blip, no login form) while Keycloak SSO is alive.
3. Spot-check matrix rows a, c, d, f, i from the reference doc §17.
