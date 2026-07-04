# pmc-epmmformquerygui — Complete Project Reference

> Spring Boot 3.4.x · Spring Security 6.4.x · Java 17 · Keycloak OIDC · React (Vite) SPA · Kubernetes
>
> Single-file reference containing every source file, all auth flows, setup, and usage.

## Table of contents

1. Overview
2. Authentication flows (with diagrams)
3. Project structure
4. Setup — step by step
5. Usage — how to use each capability
6. Configuration reference
7. Keycloak realm settings
8. Full source — build & configuration
9. Full source — config package
10. Full source — security package
11. Full source — web package
12. Full source — client package
13. Full source — model package
14. Deployment — Dockerfile
15. Things to notice (gotchas & operations)
16. Verification checklist

---

## 1. Overview

A Spring Boot app that hosts a React (Vite) SPA under `/gui_epmmFormQuery/web/`,
authenticates users through Keycloak OIDC (server-side `oauth2Login`), and keeps
them logged in without re-prompting via a hybrid token-refresh layer plus silent
re-authentication. It also exposes OAuth2-aware `WebClient`s for outgoing calls,
a server-side `UserInfoService` for OIDC claims, and a Fortify-hardened static
page controller.

**Design note:** `server.servlet.context-path` is deliberately NOT set. Every URL
carries the `/gui_epmmFormQuery` prefix explicitly, built from `app.context-prefix`.
This is why `oauth2Login` sets explicit `authorizationEndpoint.baseUri` and
`redirectionEndpoint.baseUri`.

---

## 2. Authentication flows

### 2.1 First-time login (interactive)

```
Browser  GET /gui_epmmFormQuery/web/            (no session)
  → 302  /oauth2/authorization/keycloak
  → 302  Keycloak /auth                          (login form shown)
  user submits credentials
  → 302  /login/oauth2/code/keycloak?code=...
  Spring exchanges code → tokens, creates session, sets JSESSIONID
  → 302  /gui_epmmFormQuery/web/  → 200 SPA loads
  LoginSuccessHandler sets ea_login_hint cookie (30 days)
```

### 2.2 Silent token refresh (user active, access token expiring)

Three layers, one shared `OAuth2AuthorizedClientManager`:

- **Proactive** — `TokenRefreshFilter` on each authenticated request.
- **Reactive** — the manager refreshes when a WebClient call needs the token.
- **Scheduled** — `ScheduledTokenRefreshTask` (~60s) warms idle users' tokens.

```
request → TokenRefreshFilter → manager.authorize()
  token within skew window? → POST Keycloak /token (grant=refresh_token)
  → new tokens saved → request proceeds. User notices nothing.
```

### 2.3 Silent re-authentication (idle, then page refresh)

Servlet session expired but Keycloak SSO still alive:

```
GET /my-page → 302 /oauth2/authorization/keycloak → 302 Keycloak /auth
  (browser→Keycloak, NOT in Spring logs)
  SSO cookie valid → Keycloak returns code WITHOUT a login form
  → GET /login/oauth2/code/keycloak?code=... → 302 → back on page (200)
  SPA's next backend call succeeds — no Keycloak prompt.
```

If Keycloak SSO is also gone, the callback returns `?error=login_required`;
`SilentAuthFailureHandler` clears the hint cookie and falls back to the
interactive login form.

### 2.4 Logout

```
GET /gui_epmmFormQuery/logout
  invalidate session, clear auth, delete JSESSIONID
  CustomLogoutSuccessHandler clears ea_login_hint
  → Keycloak end_session_endpoint (kills SSO) → /page/logged-out
```

---

## 3. Project structure

```
pmc-epmmformquerygui/
├── pom.xml
├── Dockerfile
└── src/main/
    ├── java/com/example/epmmformquery/
    │   ├── EpmmFormQueryApplication.java
    │   ├── config/   SecurityConfig, TokenRefreshConfig, WebClientConfig,
    │   │             WebAppConfig, CorsConfig
    │   ├── security/ TokenRefreshFilter, ScheduledTokenRefreshTask,
    │   │             SilentAuthRequestResolver, SilentAuthFailureHandler,
    │   │             LoginSuccessHandler, CustomLogoutSuccessHandler,
    │   │             UserInfoService, RequestTracingFilter,
    │   │             SecurityLoggingFilter, PrivilegeCheckFilter
    │   ├── web/      ExternalPageController (+ FrontendConfigController optional)
    │   ├── client/   DownstreamApiClient, ThirdPartyApiClient
    │   └── model/    UserInfo
    └── resources/
        ├── application.yml
        └── gui_epmmFormQuery/...   (SPA bundle when served from classpath)
```

---

## 4. Setup — step by step

1. **Package name.** Files use `com.example.epmmformquery`; rename to yours.
2. **Dependencies.** `pom.xml` includes web, security, oauth2-client, webflux
   (for WebClient), actuator. No extra deps beyond these.
3. **Environment variables:** `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`,
   `KEYCLOAK_ISSUER_URI` (and optional `DOWNSTREAM_API_URL`, `THIRD_PARTY_API_URL`).
4. **Enable config properties.** Add `@ConfigurationPropertiesScan` (or
   `@EnableConfigurationProperties`) if you use the optional runtime frontend config.
5. **Component scan.** Ensure `config`, `security`, `web`, `client`, `model`
   packages are under the application's base package.
6. **Frontend.** Build the SPA with Vite `base: '/gui_epmmFormQuery/web/'`; place
   `dist/` under `resources/gui_epmmFormQuery/web/` (classpath) or serve from a
   filesystem path via `APP_FRONTEND_RESOURCE_LOCATION=file:/app/web/`.
7. **Keycloak.** Configure the realm/client (see §7).
8. **Run:** `./mvnw spring-boot:run` (or build the jar and container per §14).

---

## 5. Usage — how to use each capability

**Read the current user (server-side):**

```java
@Autowired UserInfoService userInfoService;

UserInfo me = userInfoService.currentOrThrow();
String user = me.username();
if (me.hasRole("admin")) { ... }
```

**Call a downstream API with the bearer token auto-attached:**

```java
@Autowired @Qualifier("downstreamWebClient") WebClient downstream;

String body = downstream.get().uri("/internal/profile")
    .retrieve().bodyToMono(String.class).block();
// Do NOT set the Authorization header yourself — the filter does it,
// and it refreshes the token when needed.
```

**Call a 3rd-party API:** inject `@Qualifier("thirdPartyWebClient")` the same way.

**Static status pages:** `GET /gui_epmmFormQuery/page/access-denied` serves the
allow-listed page; an unknown name serves the fallback page.

**Toggle behavior via config:** `app.token-refresh.enabled`,
`app.silent-auth.enabled`, `app.privilege.enabled`.

---

## 6. Configuration reference

See §8 for the full `application.yml`. Key blocks: `app.context-prefix`,
`app.frontend.*`, `app.token-refresh.*`, `app.silent-auth.*`,
`server.servlet.session.*`, `spring.security.oauth2.client.*`.

### The three timeouts

| Timeout | Where | Role |
|---|---|---|
| `server.servlet.session.timeout` | Spring/Tomcat | Idle timer; on lapse JSESSIONID dies → triggers OIDC redirect. |
| Keycloak **SSO Session Idle** | Keycloak realm | Decides silent vs interactive re-auth. |
| Access / refresh token lifespans | Keycloak realm | Drive the token-refresh layer. |

Rule of thumb: **Keycloak SSO Session Idle ≥ servlet session timeout** for
"idle then refresh stays silent."

---

## 7. Keycloak realm settings

- Standard Flow Enabled: ON
- Valid Redirect URIs: `https://<host>/gui_epmmFormQuery/login/oauth2/code/keycloak`
- Valid Post Logout Redirect URIs: `https://<host>/gui_epmmFormQuery/page/logged-out`
- SSO Session Idle: MUST be >= servlet session timeout (8h) — use 10h
- SSO Session Max: 8–24h
- Access Token Lifespan: ~5 min
- Revoke Refresh Token: OFF (this switch controls rotation; "Refresh Token Max Reuse" only applies when it is ON) — avoids concurrent-refresh races
- Token mapper includes `realm_access.roles` (so `UserInfoService` sees roles)

## 8. Full source — build & configuration

### `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>pmc-epmmformquerygui</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Web layer (servlet stack) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Security + OAuth2 client (authorization code flow + WebClient filter) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>

        <!-- WebFlux brings in the WebClient + the OAuth2 filter function for it.
             Note: we only use WebClient, not the reactive stack — WebFlux jars
             coexist fine with Spring MVC when no reactive controllers exist. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Actuator for /actuator/health used in SecurityConfig permitAll list -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Test scope -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```


### `src/main/resources/application.yml`

```yaml
# =============================================================================
# pmc-epmmformquerygui — application.yml
# Spring Boot 3.4.x · Spring Security 6.4.x · Java 17 · Keycloak OIDC
# =============================================================================

app:
  context-prefix: /gui_epmmFormQuery

  # Frontend (SPA) hosting --------------------------------------------------
  frontend:
    url-prefix: ${app.context-prefix}/web
    resource-location: classpath:/gui_epmmFormQuery/web/
    index-path: ${app.context-prefix}/web/index.html
    classpath-base: gui_epmmFormQuery/web

  # Token refresh -----------------------------------------------------------
  token-refresh:
    enabled: true
    skew-seconds: 60
    schedule-rate-ms: 60000

  # Silent re-authentication -----------------------------------------------
  silent-auth:
    enabled: true
    hint-cookie-name: ea_login_hint
    hint-cookie-max-age-seconds: 2592000   # 30 days

  # WebClient base URLs -----------------------------------------------------
  downstream:
    base-url: ${DOWNSTREAM_API_URL:http://internal-api:8080}
  third-party:
    base-url: ${THIRD_PARTY_API_URL:https://partner.example.com}

  # Keycloak URLs -----------------------------------------------------------
  keycloak:
    logout-url: ${KEYCLOAK_ISSUER_URI}/protocol/openid-connect/logout
  post-logout-redirect-uri: https://myapp.example.com${app.context-prefix}/page/logged-out

  # Optional ----------------------------------------------------------------
  privilege:
    enabled: false                # set true to activate PrivilegeCheckFilter

# Server settings — context-path NOT set on purpose (v4 design)
server:
  port: 8080
  servlet:
    session:
      timeout: 8h
      cookie:
        same-site: lax
        secure: true
        http-only: true

# Spring Security OAuth2 client ------------------------------------------------
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: ${KEYCLOAK_CLIENT_ID}
            client-secret: ${KEYCLOAK_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            scope: openid, profile, email
        provider:
          keycloak:
            issuer-uri: ${KEYCLOAK_ISSUER_URI}
            user-name-attribute: preferred_username

# Logging ---------------------------------------------------------------------
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%X{traceId:-}] %-5level %logger{36} - %msg%n"
  level:
    com.example.epmmformquery: INFO
    com.example.epmmformquery.security.TokenRefreshFilter: DEBUG
    com.example.epmmformquery.security.ScheduledTokenRefreshTask: INFO
    com.example.epmmformquery.security.SilentAuthFailureHandler: INFO
    com.example.epmmformquery.security.SilentAuthRequestResolver: DEBUG
    org.springframework.security.oauth2.client: INFO
    org.springframework.web.reactive.function.client: INFO

# Actuator endpoints (referenced by SecurityConfig permitAll list)
management:
  endpoints:
    web:
      exposure:
        include: health, info
  endpoint:
    health:
      show-details: when-authorized
```


### `EpmmFormQueryApplication.java`

```java
package com.example.epmmformquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EpmmFormQueryApplication {
    public static void main(String[] args) {
        SpringApplication.run(EpmmFormQueryApplication.class, args);
    }
}
```


## 9. Full source — config package

### `config/SecurityConfig.java`

```java
package com.example.epmmformquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

import com.example.epmmformquery.security.CustomLogoutSuccessHandler;
import com.example.epmmformquery.security.LoginSuccessHandler;
import com.example.epmmformquery.security.PrivilegeCheckFilter;
import com.example.epmmformquery.security.RequestTracingFilter;
import com.example.epmmformquery.security.SecurityLoggingFilter;
import com.example.epmmformquery.security.SilentAuthFailureHandler;
import com.example.epmmformquery.security.SilentAuthRequestResolver;
import com.example.epmmformquery.security.TokenRefreshFilter;

/**
 * Security configuration with hybrid token refresh and silent re-authentication.
 *
 * FIX (vs previous draft):
 *   HttpSessionRequestCache is now declared as a top-level @Bean. Previously
 *   it was a local variable inside securityFilterChain() but was used as an
 *   autowire parameter in loginSuccessHandler(), which made Spring fail at
 *   startup with UnsatisfiedDependencyException ("No qualifying bean of type
 *   HttpSessionRequestCache available"). The IDE warning was correct.
 *
 *   With the cache as a @Bean both consumers (the filter chain that WRITES
 *   the saved request, and the success handler that READS it on redirect)
 *   share the SAME instance — which is required for the request-replay flow
 *   to work in the first place.
 */
@Configuration
@EnableScheduling
@EnableWebSecurity
public class SecurityConfig {

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final SilentAuthRequestResolver silentAuthRequestResolver;
    private final SilentAuthFailureHandler silentAuthFailureHandler;
    private final SessionRegistry sessionRegistry;
    private final CorsConfigurationSource corsConfigurationSource;
    private final CustomLogoutSuccessHandler customLogoutSuccessHandler;
    private final SecurityLoggingFilter securityLoggingFilter;
    private final PrivilegeCheckFilter privilegeCheckFilter;

    @Value("${app.context-prefix:}")
    private String contextPrefix;

    @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}")
    private String hintCookieName;

    @Value("${app.silent-auth.hint-cookie-max-age-seconds:2592000}")
    private int hintCookieMaxAge;

    public SecurityConfig(OAuth2AuthorizedClientRepository authorizedClientRepository,
                          OAuth2AuthorizedClientManager authorizedClientManager,
                          SilentAuthRequestResolver silentAuthRequestResolver,
                          SilentAuthFailureHandler silentAuthFailureHandler,
                          SessionRegistry sessionRegistry,
                          CorsConfigurationSource corsConfigurationSource,
                          CustomLogoutSuccessHandler customLogoutSuccessHandler,
                          SecurityLoggingFilter securityLoggingFilter,
                          PrivilegeCheckFilter privilegeCheckFilter) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.authorizedClientManager = authorizedClientManager;
        this.silentAuthRequestResolver = silentAuthRequestResolver;
        this.silentAuthFailureHandler = silentAuthFailureHandler;
        this.sessionRegistry = sessionRegistry;
        this.corsConfigurationSource = corsConfigurationSource;
        this.customLogoutSuccessHandler = customLogoutSuccessHandler;
        this.securityLoggingFilter = securityLoggingFilter;
        this.privilegeCheckFilter = privilegeCheckFilter;
    }

    /**
     * THE FIX: HttpSessionRequestCache is now a proper bean.
     *
     * Used by:
     *   - securityFilterChain() — registers it on the http builder so
     *     unauthenticated requests get their original URL saved.
     *   - loginSuccessHandler() — reads the saved URL after a successful
     *     login and redirects there.
     *
     * Both must use the SAME instance, which is exactly what a @Bean gives us.
     */
    @Bean
    public HttpSessionRequestCache httpSessionRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        // null = match the saved request regardless of query parameters
        // (so /web/?foo=bar comes back as /web/?foo=bar after login)
        cache.setMatchingRequestParameterName(null);
        return cache;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   HttpSessionRequestCache requestCache,
                                                   TokenRefreshFilter tokenRefreshFilter) throws Exception {

        String authBaseUri     = contextPrefix + "/oauth2/authorization";
        String callbackPattern = contextPrefix + "/login/oauth2/code/*";
        String logoutUrl       = contextPrefix + "/logout";
        String deniedPageUrl   = contextPrefix + "/page/access-denied";

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .requestCache(c -> c.requestCache(requestCache))

            .authorizeHttpRequests(a -> a
                .requestMatchers(
                        "/actuator/**",
                        "/error",
                        "/favicon.ico",
                        contextPrefix + "/page/**",
                        contextPrefix + "/rs/gui/ping"
                ).permitAll()
                .anyRequest().authenticated())

            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(auth -> auth
                        .baseUri(authBaseUri)
                        .authorizationRequestResolver(silentAuthRequestResolver))
                .redirectionEndpoint(r -> r.baseUri(callbackPattern))
                .successHandler(loginSuccessHandler(requestCache))
                .failureHandler(silentAuthFailureHandler))

            .logout(l -> l
                .logoutRequestMatcher(new AntPathRequestMatcher(logoutUrl, "GET"))
                .logoutSuccessHandler(customLogoutSuccessHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID"))

            .csrf(csrf -> csrf.ignoringRequestMatchers(logoutUrl, "/rs/**"))

            .sessionManagement(s -> s
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry))

            .exceptionHandling(e -> e.accessDeniedPage(deniedPageUrl))

            .addFilterAfter(securityLoggingFilter, SecurityContextHolderFilter.class)
            .addFilterAfter(tokenRefreshFilter,    SecurityContextHolderFilter.class)
            .addFilterBefore(privilegeCheckFilter, AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    public TokenRefreshFilter tokenRefreshFilter() {
        return new TokenRefreshFilter(authorizedClientManager);
    }

    @Bean
    public LoginSuccessHandler loginSuccessHandler(HttpSessionRequestCache requestCache) {
        return new LoginSuccessHandler(
                requestCache,
                contextPrefix + "/web/",
                authorizedClientRepository,
                hintCookieName,
                hintCookieMaxAge);
    }

    @Bean
    public FilterRegistrationBean<RequestTracingFilter> requestTracingFilter() {
        FilterRegistrationBean<RequestTracingFilter> reg = new FilterRegistrationBean<>(new RequestTracingFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
```


### `config/TokenRefreshConfig.java`

```java
package com.example.epmmformquery.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Token storage and refresh infrastructure.
 *
 * Replaces Spring's default session-scoped OAuth2AuthorizedClientRepository
 * with a principal-keyed in-memory service so:
 *   - The scheduled refresher can iterate tokens for any logged-in user.
 *   - Multiple browser tabs/sessions of the same user share one token set.
 *   - The reactive AuthorizedClientManager can refresh on demand.
 *
 * For multi-pod deployments where you need tokens to survive pod restarts,
 * swap InMemoryOAuth2AuthorizedClientService for JdbcOAuth2AuthorizedClientService.
 */
@Configuration
public class TokenRefreshConfig {

    @Value("${app.token-refresh.skew-seconds:60}")
    private long skewSeconds;

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository(
            OAuth2AuthorizedClientService authorizedClientService) {
        return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider provider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken(cfg -> cfg.clockSkew(Duration.ofSeconds(skewSeconds)))
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
```


### `config/WebClientConfig.java`

```java
package com.example.epmmformquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Two WebClient beans, both wired through the same OAuth2AuthorizedClientManager
 * so they share token storage and refresh logic.
 *
 *   downstreamWebClient  - calls a Resource Server YOU own (validates JWT itself)
 *   thirdPartyWebClient  - calls a 3rd-party API that requires Keycloak's access token
 *
 * Both inject "Authorization: Bearer <access_token>" automatically. Both will
 * trigger a refresh-token grant transparently when the token is within the skew
 * window — this is the same mechanism TokenRefreshFilter uses for the page flow.
 *
 * Token retrieval happens at request time, not bean creation time, so a token
 * refreshed by the scheduler 30 seconds ago will be picked up here.
 *
 * Why two clients instead of one: base URLs and timeouts often differ between
 * your own services and external partners. The second client also typically
 * needs different scope or even a different ClientRegistration if the 3rd-party
 * API requires different audience claims. Keeping them separate makes those
 * differences explicit and prevents one team's URL change from affecting the
 * other client's calls.
 */
@Configuration
public class WebClientConfig {

    public static final String CLIENT_REGISTRATION_ID = "keycloak";

    @Value("${app.downstream.base-url:}")
    private String downstreamBaseUrl;

    @Value("${app.third-party.base-url:}")
    private String thirdPartyBaseUrl;

    /**
     * For calling internal resource servers. The exchange filter pulls the
     * access_token from OAuth2AuthorizedClientManager — same manager that
     * TokenRefreshFilter and ScheduledTokenRefreshTask use, so all three
     * see the same token state.
     */
    @Bean
    public WebClient downstreamWebClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId(CLIENT_REGISTRATION_ID);

        WebClient.Builder builder = WebClient.builder().apply(oauth2.oauth2Configuration());
        if (downstreamBaseUrl != null && !downstreamBaseUrl.isEmpty()) {
            builder.baseUrl(downstreamBaseUrl);
        }
        return builder.build();
    }

    /**
     * For calling 3rd-party APIs that accept Keycloak access tokens.
     * Same access token is reused (same registrationId). If the 3rd party
     * requires a different audience or scope, register a separate
     * ClientRegistration in application.yml and call
     * setDefaultClientRegistrationId("third-party") here instead.
     */
    @Bean
    public WebClient thirdPartyWebClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId(CLIENT_REGISTRATION_ID);

        WebClient.Builder builder = WebClient.builder().apply(oauth2.oauth2Configuration());
        if (thirdPartyBaseUrl != null && !thirdPartyBaseUrl.isEmpty()) {
            builder.baseUrl(thirdPartyBaseUrl);
        }
        return builder.build();
    }
}
```


### `config/WebAppConfig.java`

```java
package com.example.epmmformquery.config;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * V4 component (kept as-is from your existing project).
 * Serves the Flutter SPA bundle from the classpath with SPA fallback to
 * index.html for client-side routes (paths without file extensions).
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    private static final Set<String> EXT = Set.of(
            "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico",
            "dart", "json", "wasm", "woff", "woff2", "ttf", "otf",
            "mp3", "mp4", "webp"
    );

    @Value("${app.frontend.url-prefix:/gui_epmmFormQuery/web}")
    private String urlPrefix;

    @Value("${app.frontend.resource-location:classpath:/gui_epmmFormQuery/web/}")
    private String resourceLocation;

    @Value("${app.frontend.index-path:/gui_epmmFormQuery/web/index.html}")
    private String indexPath;

    @Value("${app.frontend.classpath-base:gui_epmmFormQuery/web}")
    private String classpathBase;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(urlPrefix + "/**")
                .addResourceLocations(resourceLocation)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        // 1. Direct lookup in resourceLocation
                        Resource r = location.createRelative(path);
                        if (r.exists() && r.isReadable()) return r;

                        // 2. Classpath fallback
                        Resource cp = new ClassPathResource(classpathBase + "/" + path);
                        if (cp.exists() && cp.isReadable()) return cp;

                        // 3. Asset path-stripping fallback
                        if (hasExt(path) && path.contains("/")) {
                            String[] segs = path.split("/");
                            for (int i = 1; i < segs.length; i++) {
                                String stripped = String.join("/",
                                        java.util.Arrays.copyOfRange(segs, i, segs.length));
                                Resource alt = new ClassPathResource(classpathBase + "/" + stripped);
                                if (alt.exists() && alt.isReadable()) return alt;
                            }
                            return null;   // genuine 404 for missing asset
                        }
                        if (hasExt(path)) {
                            return null;   // missing asset, no slashes
                        }

                        // 4. SPA route → index.html
                        return new ClassPathResource(classpathBase + "/index.html");
                    }
                });
    }

    private boolean hasExt(String path) {
        int slash = path.lastIndexOf('/');
        String tail = slash < 0 ? path : path.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        if (dot < 0 || dot == tail.length() - 1) return false;
        String ext = tail.substring(dot + 1).toLowerCase();
        return EXT.contains(ext);
    }
}
```


### `config/CorsConfig.java`

```java
package com.example.epmmformquery.config;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * V4 component (kept as-is from your existing project).
 * Provides the CorsConfigurationSource bean that SecurityConfig wires
 * into the http.cors() DSL. Allows preflight OPTIONS to bypass auth.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // Adjust origins to your actual deployment
        cfg.setAllowedOriginPatterns(Arrays.asList("https://*.example.com"));
        cfg.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        cfg.setAllowedHeaders(Arrays.asList("*"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
```


## 10. Full source — security package

### `security/TokenRefreshFilter.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs after SecurityContextHolderFilter on every request. If the user is
 * OAuth2-authenticated, asks the AuthorizedClientManager to ensure the access
 * token is fresh. The manager is a no-op when the token is still valid; when
 * within the configured clock skew, it transparently calls Keycloak's token
 * endpoint with the refresh_token grant.
 *
 * NOTE: We use AuthorizedClientServiceOAuth2AuthorizedClientManager (configured
 * in TokenRefreshConfig), which per Spring Security docs is "designed to be
 * used outside the context of a HttpServletRequest." HttpServletRequest is
 * therefore NOT passed as a context attribute — that manager doesn't use it.
 *
 * Failures here are NOT fatal — we log and let the request proceed. If the
 * refresh token is truly dead, downstream API calls will get 401 and the SPA
 * can react (typically by redirecting to /oauth2/authorization/keycloak,
 * which kicks off Scenario B silent re-auth).
 */
public class TokenRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshFilter.class);

    private final OAuth2AuthorizedClientManager manager;

    public TokenRefreshFilter(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof OAuth2AuthenticationToken oauth) {
            try {
                OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                        .withClientRegistrationId(oauth.getAuthorizedClientRegistrationId())
                        .principal(oauth)
                        .build();

                OAuth2AuthorizedClient client = manager.authorize(authorizeRequest);
                if (client != null && log.isTraceEnabled()) {
                    log.trace("Token state for {}: expires_at={}",
                            oauth.getName(), client.getAccessToken().getExpiresAt());
                }
            } catch (Exception ex) {
                log.warn("Token refresh failed for {}: {}", oauth.getName(), ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
```


### `security/ScheduledTokenRefreshTask.java`

```java
package com.example.epmmformquery.security;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes access tokens for all logged-in users whose tokens
 * are about to expire. Catches the case where a user is logged in but idle —
 * no requests = neither TokenRefreshFilter nor reactive refresh fires, so
 * without this task the next user action would briefly stutter while the
 * just-expired token is refreshed.
 *
 * Iterates SessionRegistry's principals (populated because SecurityConfig
 * sets sessionManagement().maximumSessions(-1).sessionRegistry(...)).
 *
 * Disable via app.token-refresh.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "app.token-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTokenRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTokenRefreshTask.class);
    private static final String REGISTRATION_ID = "keycloak";

    private final SessionRegistry sessionRegistry;
    private final OAuth2AuthorizedClientService clientService;
    private final OAuth2AuthorizedClientManager clientManager;

    @Value("${app.token-refresh.skew-seconds:60}")
    private long skewSeconds;

    public ScheduledTokenRefreshTask(SessionRegistry sessionRegistry,
                                     OAuth2AuthorizedClientService clientService,
                                     OAuth2AuthorizedClientManager clientManager) {
        this.sessionRegistry = sessionRegistry;
        this.clientService = clientService;
        this.clientManager = clientManager;
    }

    @Scheduled(fixedRateString = "${app.token-refresh.schedule-rate-ms:60000}")
    public void refreshExpiringTokens() {
        int checked = 0, refreshed = 0, failed = 0;

        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof OAuth2User user)) {
                continue;
            }
            // Skip if no live (non-expired) sessions for this principal
            if (sessionRegistry.getAllSessions(principal, false).isEmpty()) {
                continue;
            }

            String name = user.getName();
            OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(REGISTRATION_ID, name);
            if (client == null || client.getAccessToken() == null) {
                continue;
            }
            checked++;

            Instant expiresAt = client.getAccessToken().getExpiresAt();
            if (expiresAt == null) {
                continue;
            }

            // Skip if not yet within skew window
            if (Instant.now().plus(Duration.ofSeconds(skewSeconds)).isBefore(expiresAt)) {
                continue;
            }

            try {
                Authentication synthetic = new UsernamePasswordAuthenticationToken(
                        name, null, user.getAuthorities());

                OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
                        .withAuthorizedClient(client)
                        .principal(synthetic)
                        .build();

                OAuth2AuthorizedClient updated = clientManager.authorize(req);
                if (updated != null) {
                    refreshed++;
                    log.debug("Background-refreshed token for {}", name);
                }
            } catch (Exception ex) {
                failed++;
                log.warn("Background refresh failed for {}: {}. Removing client; user must re-auth.",
                        name, ex.getMessage());
                clientService.removeAuthorizedClient(REGISTRATION_ID, name);
            }
        }

        if (checked > 0) {
            log.info("Token refresh scan: checked={} refreshed={} failed={}", checked, refreshed, failed);
        }
    }
}
```


### `security/SilentAuthRequestResolver.java`

```java
package com.example.epmmformquery.security;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Customises the OAuth2 authorization request that goes to Keycloak.
 *
 * If the browser carries the "ea_login_hint" cookie (set after a previous
 * successful login by LoginSuccessHandler), append prompt=none so Keycloak
 * attempts SSO without showing UI:
 *   - SSO cookie valid  → 302 back with ?code=...
 *   - SSO cookie absent → 302 back with ?error=login_required
 *                         (handled by SilentAuthFailureHandler, which
 *                         clears the hint cookie and retries interactively)
 *
 * Without the hint cookie (first-time visit, or after explicit logout), the
 * request is left untouched and the normal interactive flow runs.
 */
@Component
public class SilentAuthRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;
    private final String hintCookieName;

    public SilentAuthRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${app.context-prefix:}") String contextPrefix,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName) {

        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                contextPrefix + "/oauth2/authorization");
        this.hintCookieName = hintCookieName;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(delegate.resolve(request, registrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req, HttpServletRequest request) {
        if (req == null || !hasHintCookie(request)) {
            return req;
        }
        Map<String, Object> extra = new HashMap<>(req.getAdditionalParameters());
        extra.put("prompt", "none");
        return OAuth2AuthorizationRequest.from(req)
                .additionalParameters(extra)
                .build();
    }

    private boolean hasHintCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if (hintCookieName.equals(c.getName()) && "1".equals(c.getValue())) {
                return true;
            }
        }
        return false;
    }
}
```


### `security/SilentAuthFailureHandler.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Catches OAuth2 errors returned by Keycloak. The two we react to:
 *
 *   login_required        - Keycloak SSO cookie was absent/expired and
 *                           prompt=none was set. Clear the hint cookie
 *                           (CRITICAL: prevents an infinite redirect loop)
 *                           and redirect to interactive flow.
 *   interaction_required  - Same intent, less common (account linking etc).
 *
 * Anything else delegates to Spring's default handler, which renders an
 * error page or reuses the configured failureUrl.
 */
@Component
public class SilentAuthFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SilentAuthFailureHandler.class);

    private final AuthenticationFailureHandler defaultHandler =
            new SimpleUrlAuthenticationFailureHandler();
    private final String contextPrefix;
    private final String hintCookieName;

    public SilentAuthFailureHandler(
            @Value("${app.context-prefix:}") String contextPrefix,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName) {
        this.contextPrefix = contextPrefix;
        this.hintCookieName = hintCookieName;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {

        if (exception instanceof OAuth2AuthenticationException oauthEx) {
            String code = oauthEx.getError().getErrorCode();
            if ("login_required".equals(code) || "interaction_required".equals(code)) {
                log.info("Silent auth failed ({}); falling back to interactive login", code);
                clearHintCookie(response);
                response.sendRedirect(contextPrefix + "/oauth2/authorization/keycloak");
                return;
            }
        }
        defaultHandler.onAuthenticationFailure(request, response, exception);
    }

    private void clearHintCookie(HttpServletResponse response) {
        Cookie c = new Cookie(hintCookieName, "");
        c.setMaxAge(0);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setSecure(true);
        response.addCookie(c);
    }
}
```


### `security/LoginSuccessHandler.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Updated from v4 baseline: now sets the "ea_login_hint" cookie after a
 * successful login. Next time this browser visits without a session, the
 * SilentAuthRequestResolver will see the cookie and add prompt=none to the
 * authorization request, triggering Scenario B silent re-auth.
 *
 * Cookie is HttpOnly + Secure to mitigate XSS / CSRF amplification.
 * Value is just "1" — we don't need to store anything sensitive; the
 * presence of the cookie is the signal.
 */
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final String hintCookieName;
    private final int hintCookieMaxAge;
    @SuppressWarnings("unused")  // retained for future per-user logic (e.g. token introspection)
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;

    public LoginSuccessHandler(RequestCache requestCache,
                               String defaultTargetUrl,
                               OAuth2AuthorizedClientRepository authorizedClientRepository,
                               String hintCookieName,
                               int hintCookieMaxAge) {
        super.setRequestCache(requestCache);
        super.setDefaultTargetUrl(defaultTargetUrl);
        this.authorizedClientRepository = authorizedClientRepository;
        this.hintCookieName = hintCookieName;
        this.hintCookieMaxAge = hintCookieMaxAge;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws ServletException, IOException {

        Cookie hint = new Cookie(hintCookieName, "1");
        hint.setMaxAge(hintCookieMaxAge);
        hint.setPath("/");
        hint.setHttpOnly(true);
        hint.setSecure(true);
        response.addCookie(hint);

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
```


### `security/CustomLogoutSuccessHandler.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Updated from v4 baseline: now clears the "ea_login_hint" cookie before
 * redirecting to Keycloak's end_session_endpoint.
 *
 * Why both: explicit logout means "I want to fully log out". If we left the
 * hint cookie, the next visit would attempt silent re-auth with prompt=none,
 * which (assuming Keycloak SSO is dead because we just hit end_session) would
 * fail with login_required and bounce through the failure handler. Functional
 * but wasteful. Clearing the cookie makes the next login go straight to the
 * interactive flow as the user expects.
 */
@Component
public class CustomLogoutSuccessHandler implements LogoutSuccessHandler {

    private final String keycloakLogoutUrl;
    private final String postLogoutRedirectUri;
    private final String hintCookieName;

    public CustomLogoutSuccessHandler(
            @Value("${app.keycloak.logout-url}") String keycloakLogoutUrl,
            @Value("${app.post-logout-redirect-uri}") String postLogoutRedirectUri,
            @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}") String hintCookieName) {
        this.keycloakLogoutUrl = keycloakLogoutUrl;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
        this.hintCookieName = hintCookieName;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication)
            throws IOException, ServletException {

        Cookie clear = new Cookie(hintCookieName, "");
        clear.setMaxAge(0);
        clear.setPath("/");
        clear.setHttpOnly(true);
        clear.setSecure(true);
        response.addCookie(clear);

        String redirect = keycloakLogoutUrl
                + "?post_logout_redirect_uri="
                + URLEncoder.encode(postLogoutRedirectUri, StandardCharsets.UTF_8);
        response.sendRedirect(redirect);
    }
}
```


### `security/UserInfoService.java`

```java
package com.example.epmmformquery.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.example.epmmformquery.model.UserInfo;

/**
 * Single point of access for OIDC user information from server-side code.
 *
 * Strategy: read everything from the OidcUser already attached to the
 * SecurityContext (was validated and parsed at login by Spring Security's
 * OidcAuthorizationCodeAuthenticationProvider). NO additional HTTP call
 * to Keycloak. This is fast, can't fail, and always reflects what was in
 * the validated id_token.
 *
 * Sample usage in a controller:
 *
 *   @GetMapping("/rs/some-endpoint")
 *   public ResponseEntity<X> handle() {
 *       UserInfo me = userInfoService.current()
 *           .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED));
 *       if (!me.hasRole("admin")) { ... }
 *       webClient.get().uri("/internal/data?actor=" + me.username())...
 *   }
 */
@Service
public class UserInfoService {

    private static final String CLIENT_REGISTRATION_ID = "keycloak";
    private static final String REALM_ROLE_PREFIX = "ROLE_";

    private final OAuth2AuthorizedClientService authorizedClientService;

    public UserInfoService(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * Returns the current user's info, or empty if no one is authenticated
     * (or if authentication is not OAuth2-based — e.g., during tests).
     *
     * accessToken is included but should be treated as a server-side secret.
     * Don't return it to the SPA. WebClient handles header injection
     * automatically; you should rarely need to read it directly.
     */
    public Optional<UserInfo> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) {
            return Optional.empty();
        }
        return Optional.of(buildUserInfo(oauth));
    }

    /**
     * Convenience for code paths that should fail loudly when not
     * authenticated. Throws IllegalStateException — typically wrapped
     * by a ControllerAdvice into a 401 response.
     */
    public UserInfo currentOrThrow() {
        return current().orElseThrow(() ->
                new IllegalStateException("No authenticated OAuth2 user in SecurityContext"));
    }

    private UserInfo buildUserInfo(OAuth2AuthenticationToken oauth) {
        OAuth2User principal = oauth.getPrincipal();
        OidcUser oidc = (principal instanceof OidcUser o) ? o : null;

        String username = oauth.getName();
        String subject  = oidc != null ? oidc.getSubject() : null;
        String email    = oidc != null ? oidc.getEmail() : (String) principal.getAttribute("email");
        String name     = oidc != null ? oidc.getFullName() : (String) principal.getAttribute("name");
        String given    = oidc != null ? oidc.getGivenName() : null;
        String family   = oidc != null ? oidc.getFamilyName() : null;

        List<String> roles = extractRoles(principal);
        String accessToken = loadAccessToken(oauth);

        return new UserInfo(username, subject, email, name, given, family, roles, accessToken);
    }

    /**
     * Extracts roles from two places, in priority order:
     *   1. Authentication.getAuthorities() — Spring's normalized view, may
     *      already include role mappers configured elsewhere.
     *   2. Keycloak's "realm_access.roles" claim — present in standard
     *      Keycloak token mappers.
     *
     * Strips Spring's "ROLE_" prefix from authorities so callers see plain
     * role names like "admin", "viewer" — matching what they'd see in the
     * Keycloak admin console.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(OAuth2User principal) {
        List<String> roles = new ArrayList<>();

        Collection<? extends GrantedAuthority> authorities = principal.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority a : authorities) {
                String s = a.getAuthority();
                if (s == null) continue;
                if (s.startsWith(REALM_ROLE_PREFIX)) {
                    roles.add(s.substring(REALM_ROLE_PREFIX.length()));
                } else if (!s.startsWith("SCOPE_") && !s.startsWith("OIDC_USER")) {
                    // Skip OIDC_USER / SCOPE_* synthetic authorities
                    roles.add(s);
                }
            }
        }

        // Pull from Keycloak's realm_access.roles claim if not already present
        Object realmAccess = principal.getAttribute("realm_access");
        if (realmAccess instanceof java.util.Map<?, ?> map) {
            Object rolesObj = map.get("roles");
            if (rolesObj instanceof Collection<?> kcRoles) {
                for (Object r : kcRoles) {
                    if (r instanceof String rs && !roles.contains(rs)) {
                        roles.add(rs);
                    }
                }
            }
        }

        return roles.isEmpty() ? Collections.emptyList() : roles;
    }

    /**
     * Reads the current access token from OAuth2AuthorizedClientService.
     *
     * The token returned here is whatever was last saved — which after the
     * proactive filter / scheduler / reactive refresh is always fresh
     * within the configured skew window. Returns null if the user is logged
     * in but the client is not (rare — only between login redirect and the
     * very first authenticated request).
     */
    private String loadAccessToken(OAuth2AuthenticationToken oauth) {
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                CLIENT_REGISTRATION_ID, oauth.getName());
        return (client != null && client.getAccessToken() != null)
                ? client.getAccessToken().getTokenValue()
                : null;
    }
}
```


### `security/RequestTracingFilter.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * V4 component (kept as-is from your existing project).
 * Adds a per-request traceId to MDC so all downstream logs can be correlated.
 *
 * Registered with HIGHEST_PRECEDENCE in SecurityConfig.requestTracingFilter()
 * so it runs before the security chain.
 */
public class RequestTracingFilter implements Filter {

    private static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```


### `security/SecurityLoggingFilter.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * V4 component (kept as-is from your existing project).
 * Logs the resolved authentication state for each request.
 * Inserted after SecurityContextHolderFilter so the SecurityContext is loaded.
 */
@Component
public class SecurityLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (log.isDebugEnabled()) {
            log.debug("path={} auth={} authenticated={}",
                    request.getRequestURI(),
                    auth == null ? "null" : auth.getName(),
                    auth != null && auth.isAuthenticated());
        }
        chain.doFilter(request, response);
    }
}
```


### `security/PrivilegeCheckFilter.java`

```java
package com.example.epmmformquery.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * V4 component (kept as-is from your existing project).
 * Inserted before AuthorizationFilter to perform LDAP-based privilege checks.
 * Set app.privilege.enabled=false to skip; on failure, redirects to access-denied page.
 */
@Component
public class PrivilegeCheckFilter extends OncePerRequestFilter {

    @Value("${app.privilege.enabled:false}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        // TODO: insert your existing LDAP privilege check logic here.
        chain.doFilter(request, response);
    }
}
```


## 11. Full source — web package

### `web/ExternalPageController.java (Fortify-hardened)`

```java
package com.example.epmmformquery.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a FIXED set of static HTML pages plus a fallback page, with the same
 * two Fortify fixes as before (allow-list breaks path-traversal taint; request
 * data is never reflected into the body).
 *
 * This version replaces the recursive serveFile(..., isFallback) form with a
 * flat, linear flow so the fallback path is obvious:
 *
 *     known page file present?    -> serve it
 *     else fallback file present? -> serve it          (THIS is the 501.html path)
 *     else                        -> serve EMERGENCY_HTML
 *
 * readPage() is the single file reader; it only ever receives constant
 * filenames (allow-list value or the configured fallback name), never request
 * data, and confines every read to baseDir.
 */
@RestController
@RequestMapping("/page")
public class ExternalPageController {

    private static final Logger log = LoggerFactory.getLogger(ExternalPageController.class);

    private static final Map<String, String> ALLOWED_PAGES = Map.of(
            "access-denied",   "access-denied",
            "logged-out",      "logged-out",
            "error",           "error",
            "session-expired", "session-expired"
    );

    private static final String EMERGENCY_HTML = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><title>Notice</title></head>
            <body><h1>Page not available</h1>
            <p>Please return to the application.</p></body></html>
            """;

    private final Path baseDir;
    private final String fallbackFile;

    public ExternalPageController(
            @Value("${app.pages.dir:#{systemProperties['user.dir']}}") String pagesDir,
            @Value("${app.pages.fallback-file:501.html}") String fallbackFile) {
        this.baseDir = Paths.get(pagesDir).toAbsolutePath().normalize();
        this.fallbackFile = Paths.get(fallbackFile).getFileName().toString();
        log.info("ExternalPageController serving from {} (fallback={})", baseDir, this.fallbackFile);
    }

    @GetMapping("/{pageName:[a-z0-9-]{1,40}}")
    public ResponseEntity<String> servePage(@PathVariable String pageName) {

        // 1. Known page whose file is present -> serve it.
        String safeName = ALLOWED_PAGES.get(pageName);
        if (safeName != null) {
            String body = readPage(safeName + ".html");
            if (body != null) {
                return html(body);
            }
            log.debug("Known page '{}' file missing; falling back to {}", safeName, fallbackFile);
        } else {
            log.debug("Unknown page '{}'; falling back to {}", pageName, fallbackFile);
        }

        // 2. Fallback file present -> serve it. (This is how you reach 501.html.)
        String fallbackBody = readPage(fallbackFile);
        if (fallbackBody != null) {
            return html(fallbackBody);
        }

        // 3. Fallback missing too -> emergency constant (never 500s).
        log.warn("Fallback file '{}' not found under {}; serving emergency HTML", fallbackFile, baseDir);
        return html(EMERGENCY_HTML);
    }

    /**
     * Reads one page file from baseDir. Returns null if it is missing,
     * unreadable, or would resolve outside baseDir. {@code filename} is always
     * a constant (allow-list value or configured fallback), never request data.
     */
    private String readPage(String filename) {
        Path filePath = baseDir.resolve(filename).normalize();

        if (!filePath.startsWith(baseDir)) {
            log.warn("Resolved path escaped base dir: {}", filePath);
            return null;
        }
        try {
            if (Files.isRegularFile(filePath) && Files.isReadable(filePath)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            log.debug("Page file not found/readable: {}", filePath);
        } catch (IOException ex) {
            log.warn("Failed reading {}: {}", filePath, ex.getMessage());
        }
        return null;
    }

    private ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }
}
```


## 12. Full source — client package

### `client/DownstreamApiClient.java`

```java
package com.example.epmmformquery.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.epmmformquery.model.UserInfo;
import com.example.epmmformquery.security.UserInfoService;

/**
 * Demonstrates the typical pattern for calling a downstream API:
 *
 *   1. Get the current user's info from UserInfoService.
 *   2. Make the WebClient call. Authorization header is injected
 *      AUTOMATICALLY by ServletOAuth2AuthorizedClientExchangeFilterFunction —
 *      you don't add it yourself.
 *   3. Pass user-identifying info via business headers (X-Acting-User)
 *      or query parameters as the downstream API requires.
 *
 * What you should NOT do:
 *   - Manually call .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
 *     — the WebClient is already configured to do this.
 *   - Read the access token in your controller and try to attach it
 *     yourself — adds bugs (stale tokens, missing refresh) and bypasses
 *     all the work TokenRefreshConfig set up.
 */
@Component
public class DownstreamApiClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamApiClient.class);

    private final WebClient downstreamWebClient;
    private final UserInfoService userInfoService;

    public DownstreamApiClient(@Qualifier("downstreamWebClient") WebClient downstreamWebClient,
                               UserInfoService userInfoService) {
        this.downstreamWebClient = downstreamWebClient;
        this.userInfoService = userInfoService;
    }

    /**
     * Calls GET /internal/profile on the downstream service.
     * Authorization header is added automatically. We also pass
     * X-Acting-User as a business-level identifier in case the
     * downstream service logs by username rather than by sub.
     */
    public String fetchProfile() {
        UserInfo me = userInfoService.currentOrThrow();
        log.debug("Fetching profile for {} (sub={})", me.username(), me.subject());

        return downstreamWebClient
                .get()
                .uri("/internal/profile")
                .header("X-Acting-User", me.username())
                .retrieve()
                .bodyToMono(String.class)
                .block();   // .block() OK in servlet context — see WebClient notes in markdown
    }

    /**
     * Example of a write call. Same auth pattern.
     */
    public void recordEvent(String eventType, String payload) {
        UserInfo me = userInfoService.currentOrThrow();

        downstreamWebClient
                .post()
                .uri("/internal/events")
                .header("X-Acting-User", me.username())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"type\":\"" + eventType + "\",\"payload\":" + payload + "}")
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
```


### `client/ThirdPartyApiClient.java`

```java
package com.example.epmmformquery.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.epmmformquery.model.UserInfo;
import com.example.epmmformquery.security.UserInfoService;

/**
 * Pattern for 3rd-party API calls. Same access token is reused —
 * the 3rd party trusts Keycloak as the issuer.
 *
 * If the 3rd party requires a DIFFERENT scope or audience, the cleaner
 * approach is to register a separate ClientRegistration for it in
 * application.yml (e.g. registration.partner-api), give it a separate
 * scope, and inject a third WebClient configured with that registration
 * id. That keeps tokens for the partner separate from your downstream
 * token, which means a leak of one doesn't affect the other.
 */
@Component
public class ThirdPartyApiClient {

    private static final Logger log = LoggerFactory.getLogger(ThirdPartyApiClient.class);

    private final WebClient thirdPartyWebClient;
    private final UserInfoService userInfoService;

    public ThirdPartyApiClient(@Qualifier("thirdPartyWebClient") WebClient thirdPartyWebClient,
                               UserInfoService userInfoService) {
        this.thirdPartyWebClient = thirdPartyWebClient;
        this.userInfoService = userInfoService;
    }

    public String fetchPartnerData(String resourceId) {
        UserInfo me = userInfoService.currentOrThrow();
        log.debug("Calling partner API on behalf of {}", me.username());

        return thirdPartyWebClient
                .get()
                .uri("/v1/resources/{id}", resourceId)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
```


## 13. Full source — model package

### `model/UserInfo.java`

```java
package com.example.epmmformquery.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of the authenticated user's OIDC claims, suitable for passing
 * to controllers, services, and downstream calls.
 *
 * Built from the OidcUser already loaded into Spring's SecurityContext at
 * login. There is NO extra HTTP call to Keycloak's /userinfo endpoint —
 * everything in here came from the validated id_token. If you need attributes
 * that aren't in the id_token (e.g. provider-specific claims), see
 * UserInfoService.fetchFreshFromUserInfoEndpoint() — but in 99% of cases,
 * the claims here are sufficient.
 */
public record UserInfo(
        String username,        // preferred_username (OIDC standard)
        String subject,         // sub claim (Keycloak's stable user id)
        String email,
        String fullName,        // name claim
        String givenName,
        String familyName,
        List<String> roles,
        String accessToken      // bearer string — for passing to downstream APIs only when WebClient isn't suitable
) {
    public UserInfo {
        // Defensive copy + null safety
        roles = roles == null ? Collections.emptyList() : List.copyOf(roles);
    }

    /** True if the user has the given role (Keycloak realm role). */
    public boolean hasRole(String role) {
        return roles.contains(Objects.requireNonNull(role));
    }
}
```


## 14. Deployment — Dockerfile (jar-surgery variant)

### `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1
# =============================================================================
# React jar-surgery, with a VARIABLE source build folder normalized into a
# FIXED destination inside the jar.
#
# Two knobs:
#   REACT_BUILD_DIR  (SOURCE, varies)   the folder `npm run build` emits.
#                                       Vite=dist, old CRA=build, custom=out, ...
#                                       Override: --build-arg REACT_BUILD_DIR=build
#   STATIC_DIR       (DEST, constant)   the fixed folder inside the jar the SPA
#                                       always lands in, under PROJECT_NAME.
#
# Result (defaults): whatever React emits ends up at
#   <classes>/gui_epmmFormQuery/dist/    => classpath:/gui_epmmFormQuery/dist/
# so resource-location is stable no matter which React tool built the bundle.
#
# Build context = repo root:
#   (cd backend && mvn -B clean package -DskipTests)
#   docker build -f Dockerfile -t <registry>/pmc-epmmformquerygui:${TAG} .
#   # older CRA project:
#   docker build --build-arg REACT_BUILD_DIR=build -f Dockerfile -t ... .
# =============================================================================

# Global args (re-declared in each stage that uses them).
ARG PROJECT_NAME=gui_epmmFormQuery
ARG REACT_BUILD_DIR=dist
ARG STATIC_DIR=dist

# ---- Stage 1: build React, normalize the output folder to "dist" ------------
FROM node:20-alpine AS frontend
ARG REACT_BUILD_DIR
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
# vite.config.ts (or your tool) must set the base URL to the SERVED url-prefix,
# i.e. '/gui_epmmFormQuery/web/' — NOT the internal STATIC_DIR name.
COPY frontend/ ./
RUN npm run build \
 && if [ "${REACT_BUILD_DIR}" != "dist" ]; then \
        echo "Normalizing build output '${REACT_BUILD_DIR}' -> 'dist'"; \
        rm -rf dist && mv "${REACT_BUILD_DIR}" dist; \
    fi
# => /frontend/dist now holds the bundle regardless of the tool's folder name.

# ---- Stage 2: jar surgery (needs the `jar` tool => JDK) ----------------------
FROM eclipse-temurin:17-jdk AS assembler
ARG PROJECT_NAME
ARG STATIC_DIR
WORKDIR /userapp/src

COPY backend/target/*.jar EPMM-snapshot.jar
COPY --from=frontend /frontend/dist/ /tmp/spa/

# Unzip -> inject into the FIXED destination -> re-zip.
#  - auto-detect fat-jar (BOOT-INF/classes) vs plain-jar (root) base;
#  - destination is always <base>/${PROJECT_NAME}/${STATIC_DIR};
#  - -0 (store) keeps nested BOOT-INF/lib jars uncompressed for the loader;
#  - -M + the '*' glob preserves the original executable manifest.
RUN jar -xf EPMM-snapshot.jar \
 && rm -f EPMM-snapshot.jar \
 && if [ -d BOOT-INF/classes ]; then BASE="BOOT-INF/classes"; else BASE="."; fi \
 && DEST="${BASE}/${PROJECT_NAME}/${STATIC_DIR}" \
 && echo "Injecting SPA into ${DEST}" \
 && mkdir -p "${DEST}" \
 && cp -r /tmp/spa/. "${DEST}/" \
 && jar -cfM0 /userapp/EPMM-snapshot.jar * \
 && echo "Repackaged. Verifying SPA entry:" \
 && jar -tf /userapp/EPMM-snapshot.jar | grep "${PROJECT_NAME}/${STATIC_DIR}/index.html"

# ---- Stage 3: runtime --------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=assembler /userapp/EPMM-snapshot.jar app.jar

ENV SPRING_CONFIG_LOCATION=file:/config/application.yaml

RUN useradd -r -u 1001 appuser
USER 1001

EXPOSE 8080 8124
ENTRYPOINT ["java", "-jar", "app.jar"]
```


---

## 15. Things to notice (gotchas & operations)

**Auth architecture tension (highest priority).** The backend runs server-side
`oauth2Login` (session + redirect). If the frontend also uses keycloak-js
(client-side), the two fight: a protected `/rs/**` XHR receives a 302 to Keycloak
that an XHR cannot follow → "CORS on 302". Page refreshes work; background API
calls do not. If keycloak-js is the model, make the backend a **resource server**
(validate the Bearer token) instead of `oauth2Login`.

**The "flash then disappears" on idle refresh is normal** — it's silent
re-authentication (§2.3). Enable DevTools → Network → "Preserve log" to see it.
Healthy = 302 chain ending in 200, no Keycloak login form. A 200 HTML login page
at `/auth` means SSO expired. A 4xx/5xx hop means a real error (often a
`redirect_uri` not registered in Keycloak).

**Silent-auth loop guard.** `SilentAuthFailureHandler` MUST clear the
`ea_login_hint` cookie on `login_required`, or `prompt=none` re-adds itself every
redirect and loops forever.

**`HttpSessionRequestCache` is a bean.** It is shared between the filter chain
(writes the saved request) and `LoginSuccessHandler` (reads it after login). If
you inline it as a local variable, Spring fails at startup with
`UnsatisfiedDependencyException` — the IDE warning about this is correct.

**Token storage is in-memory.** On pod restart the store is empty → users
silently re-auth from Keycloak SSO. For multi-pod use sticky sessions, or switch
to `JdbcOAuth2AuthorizedClientService`.

**WebClient rules.** Always inject with `@Qualifier` (two beans exist). Never set
the `Authorization` header manually — that bypasses the refresh logic and causes
sporadic 401s.

**CORS on external services.** A remote service that echoes back a URL-encoded
Origin (`http://localhost%3A8080`) breaks browser calls — that is the remote
server's bug. Fix on their side or proxy through Spring Boot; your own
`CorsConfig` cannot change a remote server's response headers.

**`ExternalPageController` hardening.** Path traversal is closed by an allow-list
(the request value is only a map key; the filename is a constant), a regex gate,
and `startsWith(baseDir)` confinement. XSS is closed by never reflecting request
data into the HTML body. The fallback filename, `app.pages.dir`, and the ConfigMap
key must all agree or every request drops to the emergency page.

**Frontend `base` vs internal folder.** Vite `base` must equal the URL prefix
`/gui_epmmFormQuery/web/`, NOT the internal classpath folder name. A wrong base is
the usual cause of a blank page with asset 404s.

**Dockerfile jar-surgery.** Injects the SPA into the pre-built jar; auto-detects
fat-jar (`BOOT-INF/classes`) vs plain-jar, re-zips with `-0` (store) so nested
libs stay uncompressed, and preserves the manifest.

---

## 16. Verification checklist

- [ ] First login shows Keycloak form, lands on the SPA, sets `ea_login_hint`.
- [ ] Active use past access-token lifespan → no interruption (token refresh).
- [ ] Idle past session timeout, SSO alive → refresh is silent (302 chain, no form),
      and the SPA's next backend call returns 200.
- [ ] Idle past Keycloak SSO idle → refresh shows the login form (interactive).
- [ ] Logout → Keycloak SSO cleared → next visit is interactive; hint cookie gone.
- [ ] `/gui_epmmFormQuery/web/` and its assets return 200 (correct Vite `base`).
- [ ] `/page/access-denied` renders; `/page/<unknown>` renders the fallback.
- [ ] App starts with no `UnsatisfiedDependencyException` (HttpSessionRequestCache bean).

---

*End of complete reference. Companion narrative docs (first-login workflow,
silent-auth workflow, CORS fix, Vite hosting, Dockerfile variants) exist as
separate files if you need the deep-dive on any single topic.*

---

## 17. When does the user see the login form again?

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
