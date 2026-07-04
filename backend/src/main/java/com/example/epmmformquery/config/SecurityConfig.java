package com.example.epmmformquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

import com.example.epmmformquery.security.KeycloakLogoutSuccessHandler;
import com.example.epmmformquery.security.LoginSuccessHandler;
import com.example.epmmformquery.security.PrivilegeCheckFilter;
import com.example.epmmformquery.security.RequestTracingFilter;
import com.example.epmmformquery.security.SecurityLoggingFilter;
import com.example.epmmformquery.security.SilentAuthFailureHandler;
import com.example.epmmformquery.security.SilentAuthRequestResolver;
import com.example.epmmformquery.security.SpaCsrfTokenRequestHandler;
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

    public SecurityConfig(OAuth2AuthorizedClientRepository authorizedClientRepository,
                          OAuth2AuthorizedClientManager authorizedClientManager,
                          SilentAuthRequestResolver silentAuthRequestResolver,
                          SilentAuthFailureHandler silentAuthFailureHandler,
                          SessionRegistry sessionRegistry,
                          CorsConfigurationSource corsConfigurationSource,
                          KeycloakLogoutSuccessHandler keycloakLogoutSuccessHandler,
                          SecurityLoggingFilter securityLoggingFilter,
                          PrivilegeCheckFilter privilegeCheckFilter) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.authorizedClientManager = authorizedClientManager;
        this.silentAuthRequestResolver = silentAuthRequestResolver;
        this.silentAuthFailureHandler = silentAuthFailureHandler;
        this.sessionRegistry = sessionRegistry;
        this.corsConfigurationSource = corsConfigurationSource;
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
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .requestCache(c -> c.requestCache(requestCache))

            .authorizeHttpRequests(a -> a
                .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
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
                .logoutRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, logoutUrl))
                .logoutSuccessHandler(keycloakLogoutSuccessHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID"))

            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .ignoringRequestMatchers(logoutUrl))

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
}
