package com.example.epmmformquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.example.epmmformquery.security.KeycloakLogoutSuccessHandler;
import com.example.epmmformquery.security.LoginSuccessHandler;
import com.example.epmmformquery.security.PrivilegeCheckFilter;
import com.example.epmmformquery.security.RequestTracingFilter;
import com.example.epmmformquery.security.SecurityLoggingFilter;
import com.example.epmmformquery.security.SilentAuthFailureHandler;
import com.example.epmmformquery.security.SilentAuthRequestResolver;
import com.example.epmmformquery.security.SpaCsrfTokenRequestHandler;
import com.example.epmmformquery.security.TokenRefreshFilter;

import jakarta.servlet.http.HttpServletRequest;

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
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final SilentAuthRequestResolver silentAuthRequestResolver;
    private final SilentAuthFailureHandler silentAuthFailureHandler;
    private final SessionRegistry sessionRegistry;
    private final KeycloakLogoutSuccessHandler keycloakLogoutSuccessHandler;
    private final SecurityLoggingFilter securityLoggingFilter;
    private final PrivilegeCheckFilter privilegeCheckFilter;

    @Value("${app.context-prefix:}")
    private String contextPrefix;

    @Value("${app.silent-auth.hint-cookie-name:ea_login_hint}")
    private String hintCookieName;

    @Value("${app.silent-auth.hint-cookie-max-age-seconds:2592000}")
    private int hintCookieMaxAge;

    @Value("${app.silent-auth.cookie-secure:true}")
    private boolean hintCookieSecure;

    @Value("${app.security.csp-policy:}")
    private String cspPolicy;

    @Value("${app.security.csp-report-only:true}")
    private boolean cspReportOnly;

    /** Empty = any authenticated realm user; non-empty = require ROLE_<name> (F17 interim guard). */
    @Value("${app.security.required-role:}")
    private String requiredRole;

    public SecurityConfig(OAuth2AuthorizedClientRepository authorizedClientRepository,
                          OAuth2AuthorizedClientManager authorizedClientManager,
                          OAuth2AuthorizedClientService authorizedClientService,
                          SilentAuthRequestResolver silentAuthRequestResolver,
                          SilentAuthFailureHandler silentAuthFailureHandler,
                          SessionRegistry sessionRegistry,
                          KeycloakLogoutSuccessHandler keycloakLogoutSuccessHandler,
                          SecurityLoggingFilter securityLoggingFilter,
                          PrivilegeCheckFilter privilegeCheckFilter) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClientService = authorizedClientService;
        this.silentAuthRequestResolver = silentAuthRequestResolver;
        this.silentAuthFailureHandler = silentAuthFailureHandler;
        this.sessionRegistry = sessionRegistry;
        this.keycloakLogoutSuccessHandler = keycloakLogoutSuccessHandler;
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
            // No .cors(): the SPA is same-origin by design (BFF) — credentialed
            // cross-origin access was review finding F4. If a cross-origin
            // consumer ever appears, add an explicit enumerated config here.
            .requestCache(c -> c.requestCache(requestCache))

            .authorizeHttpRequests(a -> {
                a.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/error",
                        "/favicon.ico",
                        contextPrefix + "/page/**",
                        contextPrefix + "/rs/gui/ping"
                ).permitAll();
                if (requiredRole == null || requiredRole.isBlank()) {
                    a.anyRequest().authenticated();
                } else {
                    // F17 interim guard: "authenticated" in a shared realm means
                    // every SSO user; the role narrows it to users authorized
                    // for THIS app (realm_access.roles via the mapper below).
                    a.anyRequest().hasRole(requiredRole);
                }
            })

            // F3b: BFF keeps tokens out of the browser, but XSS in the hosted
            // SPA could still ride the session cookie — CSP is the remaining
            // control. HSTS needs F3a (forward-headers) to be emitted at all.
            .headers(h -> {
                h.httpStrictTransportSecurity(hsts -> hsts
                        .maxAgeInSeconds(31536000)
                        .includeSubDomains(true));
                if (cspPolicy != null && !cspPolicy.isBlank()) {
                    h.contentSecurityPolicy(csp -> {
                        csp.policyDirectives(cspPolicy);
                        if (cspReportOnly) {
                            csp.reportOnly();
                        }
                    });
                }
            })

            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(auth -> auth
                        .baseUri(authBaseUri)
                        .authorizationRequestResolver(silentAuthRequestResolver))
                .redirectionEndpoint(r -> r.baseUri(callbackPattern))
                .successHandler(loginSuccessHandler(requestCache))
                .failureHandler(silentAuthFailureHandler))

            // GET logout is a documented deviation (review D7): RP-initiated
            // logout needs a top-level navigation to follow the 302 to
            // Keycloak's end_session, which fetch() cannot do.
            .logout(l -> l
                .logoutRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, logoutUrl))
                .logoutSuccessHandler(keycloakLogoutSuccessHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                // F16: purge the server-side tokens too — otherwise a
                // still-valid access token lingers in the JVM map and entries
                // accumulate until pod restart. Keyed by getName() = sub,
                // consistent with the store (F15).
                .addLogoutHandler((req, res, auth) -> {
                    if (auth != null) {
                        authorizedClientService.removeAuthorizedClient("keycloak", auth.getName());
                    }
                })
                .deleteCookies("__Host-JSESSIONID"))

            .csrf(csrf -> csrf
                .csrfTokenRepository(spaCsrfTokenRepository())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .ignoringRequestMatchers(logoutUrl))

            .sessionManagement(s -> s
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry)
                // Sessions expired by ScheduledTokenRefreshTask (dead refresh
                // token, F10): XHRs get 401 (same contract as the entry point
                // below); page navigations re-request the same URL, which
                // re-enters the auth flow — silent while Keycloak SSO is alive.
                .expiredSessionStrategy(event -> {
                    HttpServletRequest req = event.getRequest();
                    if (req.getRequestURI().startsWith(contextPrefix + "/rs/")) {
                        event.getResponse().sendError(HttpStatus.UNAUTHORIZED.value());
                    } else {
                        String query = req.getQueryString();
                        event.getResponse().sendRedirect(
                                req.getRequestURI() + (query != null ? "?" + query : ""));
                    }
                }))

            // XHRs from the SPA must see a clean 401 when the session is gone —
            // the oauth2Login default 302-to-Keycloak is unfollowable by fetch()
            // ("CORS on 302", review F2). Browser navigations keep the redirect,
            // which is what makes silent re-auth work. The explicit any-request
            // mapping is required: once a custom mapping exists, unmatched
            // requests would otherwise fall back to the FIRST-registered entry
            // point (the 401), turning every page navigation into a 401.
            .exceptionHandling(e -> e
                .accessDeniedPage(deniedPageUrl)
                .defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher(contextPrefix + "/rs/**"))
                .defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(authBaseUri + "/keycloak"),
                        AnyRequestMatcher.INSTANCE))

            .addFilterAfter(securityLoggingFilter, SecurityContextHolderFilter.class)
            .addFilterAfter(tokenRefreshFilter,    SecurityContextHolderFilter.class)
            .addFilterBefore(privilegeCheckFilter, AuthorizationFilter.class);

        return http.build();
    }

    /**
     * XSRF-TOKEN cookie for the SPA (double-submit pattern, review F8):
     * readable by JS, scoped to the context prefix instead of host-wide
     * Path=/ (a sibling app on the same gateway host must not be able to
     * clobber it), explicit SameSite=Lax. httpOnly(false) must live in this
     * same customizer — setCookieCustomizer REPLACES the customizer that
     * CookieCsrfTokenRepository.withHttpOnlyFalse() installs, so composing
     * them separately would silently re-enable HttpOnly and break the SPA.
     */
    private CookieCsrfTokenRepository spaCsrfTokenRepository() {
        CookieCsrfTokenRepository repo = new CookieCsrfTokenRepository();
        repo.setCookiePath(contextPrefix);
        repo.setCookieCustomizer(c -> c.httpOnly(false).sameSite("Lax"));
        return repo;
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
                hintCookieMaxAge,
                contextPrefix,
                hintCookieSecure);
    }

    @Bean
    public FilterRegistrationBean<RequestTracingFilter> requestTracingFilter() {
        FilterRegistrationBean<RequestTracingFilter> reg = new FilterRegistrationBean<>(new RequestTracingFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * Maps Keycloak's realm_access.roles claim to ROLE_* authorities at login
     * (picked up automatically by oauth2Login). Without this, the principal
     * only carries OIDC_USER/SCOPE_* and the app.security.required-role rule
     * could never match. UserInfoService's role extraction is unaffected —
     * it dedupes against the same claim.
     */
    @Bean
    public GrantedAuthoritiesMapper realmRolesAuthoritiesMapper() {
        return authorities -> {
            java.util.Set<GrantedAuthority> mapped = new java.util.LinkedHashSet<>(authorities);
            for (GrantedAuthority authority : authorities) {
                Object realmAccess = null;
                if (authority instanceof OidcUserAuthority oidc) {
                    realmAccess = oidc.getIdToken().getClaims().get("realm_access");
                    if (realmAccess == null && oidc.getUserInfo() != null) {
                        realmAccess = oidc.getUserInfo().getClaims().get("realm_access");
                    }
                } else if (authority instanceof OAuth2UserAuthority oauth2) {
                    realmAccess = oauth2.getAttributes().get("realm_access");
                }
                if (realmAccess instanceof java.util.Map<?, ?> map
                        && map.get("roles") instanceof java.util.Collection<?> roles) {
                    for (Object role : roles) {
                        if (role instanceof String s && !s.isBlank()) {
                            mapped.add(new SimpleGrantedAuthority("ROLE_" + s));
                        }
                    }
                }
            }
            return mapped;
        };
    }
}
