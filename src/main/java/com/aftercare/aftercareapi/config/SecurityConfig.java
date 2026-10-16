package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.Services.StaffAccessService;
import com.aftercare.aftercareapi.config.security.AccessPolicy;
import com.aftercare.aftercareapi.config.security.CurrentStaff;
import com.aftercare.aftercareapi.config.security.PublicRateLimitFilter;
import com.aftercare.aftercareapi.config.security.StaffAccessDeniedHandler;
import com.aftercare.aftercareapi.config.security.StaffContextFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins:http://localhost:4200}")
    private List<String> allowedOrigins;

    /**
     * Patient endpoints: no JWT handling at all, so an Authorization header is neither required nor honoured.
     * Authentication is the access code in the path.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain publicFilterChain(HttpSecurity http, RateLimitProperties rateLimit, Clock clock,
                                                 ObjectMapper mapper, org.springframework.core.env.Environment env) throws Exception {
        com.aftercare.aftercareapi.config.security.RateLimitStartupCheck.check(rateLimit, env);
        http
                .securityMatcher("/api/public/**")
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        if (rateLimit.enabled()) {
            // Not a bean on purpose: it must run only in this chain, not in the servlet container's own filter chain.
            http.addFilterBefore(new PublicRateLimitFilter(rateLimit, clock, mapper), AuthorizationFilter.class);
        }
        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, StaffAccessService staffAccess,
                                                   StaffAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize
                            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/api/public/**").permitAll();
                    // The whole matrix lives in AccessPolicy. Order matters: first match wins.
                    for (AccessPolicy.Rule rule : AccessPolicy.RULES) {
                        var matcher = AntPathRequestMatcher.antMatcher(rule.method(), rule.pattern());
                        if (rule.permission() == null) {
                            authorize.requestMatchers(matcher).authenticated();
                        } else {
                            authorize.requestMatchers(matcher).hasAuthority(CurrentStaff.PERMISSION_PREFIX + rule.permission().name());
                        }
                    }
                    // Deny by default: an /api route that is not in the table is never reachable.
                    authorize.requestMatchers("/api/**").denyAll();
                    authorize.anyRequest().hasAuthority(CurrentStaff.STAFF_AUTHORITY);
                })
                .exceptionHandling(e -> e.accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2ResourceServer -> oauth2ResourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                )
                // Not a bean on purpose (it would also be registered in the servlet container's own chain).
                .addFilterAfter(new StaffContextFilter(staffAccess), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // Configure the converter if necessary, e.g., setting a custom authority prefix or authority mapping
        return converter;
    }

    @Bean
    @Profile("!local")
    public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                 @Value("${auth0.audience}") String audience) {
        Assert.hasText(issuer, "spring.security.oauth2.resourceserver.jwt.issuer-uri must not be blank");
        // Lazy: the issuer's discovery document is fetched on first token, not at startup.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(jwtValidator(issuer, audience));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> jwtValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
