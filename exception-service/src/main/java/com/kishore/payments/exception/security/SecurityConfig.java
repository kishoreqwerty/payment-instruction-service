package com.kishore.payments.exception.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP Basic with in-memory users -- a deliberate simplification, per the
 * phase brief: this is not the phase for an identity provider, and building
 * one would be scope creep. Production would put a real IdP in front of this
 * (OIDC via Okta/Cognito/Keycloak, tokens carrying role claims this service
 * validates rather than issues) and this class would shrink to a JWT
 * resource-server configuration with no user store of its own.
 *
 * <p>Users, deliberately not matching any single real name, to make the
 * maker-checker tests self-explanatory from the username alone: two MAKERs
 * and two CHECKERs so "a different checker succeeds" has a real different
 * user to use, plus {@code dual1}, who holds both roles -- proving the
 * maker-checker rule rejects self-approval by *identity*
 * ({@code proposed_by <> approved_by}), not merely by role, even for a user
 * who is technically authorised to approve.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        JsonAuthEntryPoints entryPoints = new JsonAuthEntryPoints(objectMapper);
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoints))
                .exceptionHandling(handling -> handling.accessDeniedHandler(entryPoints))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * The ops-dashboard (Phase 9) is a browser client on a different origin
     * (the Vite dev server) than this API, and CORS is a browser-enforced
     * check with nothing to trigger it in Phase 8's own server-to-server or
     * test-client callers -- a genuine gap this phase's client exposed, not
     * a pre-existing bug. Origins are read from configuration rather than
     * hardcoded so a deployed dashboard's real origin doesn't require a
     * code change; {@code allowCredentials} is left false because the
     * dashboard authenticates with an explicit {@code Authorization} header
     * on each request, not a cookie -- there is no session for the browser
     * to send automatically, so the credentialed-CORS complexity (and its
     * incompatibility with a wildcard origin) does not apply here.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(Environment env) {
        String[] origins = env.getProperty("payments.exceptions.dashboard-origins", "http://localhost:5173").split(",");
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origins));
        configuration.setAllowedMethods(List.of("GET", "POST"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
        UserDetails viewer = user("viewer", encoder, "VIEWER");
        UserDetails maker1 = user("maker1", encoder, "MAKER");
        UserDetails maker2 = user("maker2", encoder, "MAKER");
        UserDetails checker1 = user("checker1", encoder, "CHECKER");
        UserDetails checker2 = user("checker2", encoder, "CHECKER");
        UserDetails dual1 = user("dual1", encoder, "MAKER", "CHECKER");
        return new InMemoryUserDetailsManager(viewer, maker1, maker2, checker1, checker2, dual1);
    }

    /** Every local/test user shares the same fixed password -- there is no real credential here to protect, only a role to authenticate as. */
    private static final String LOCAL_TEST_PASSWORD = "password";

    private static UserDetails user(String username, PasswordEncoder encoder, String... roles) {
        return User.withUsername(username).password(encoder.encode(LOCAL_TEST_PASSWORD)).roles(roles).build();
    }
}
