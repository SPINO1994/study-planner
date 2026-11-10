package dev.studyplanner.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth, Clock clock,
            @Value("${app.auth-requests-per-minute:20}") int authRequests,
            @Value("${app.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}") List<String> allowedOrigins) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "MCP-Protocol-Version"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return http
                .cors(config -> config.configurationSource(source))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(access -> access
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.css", "/app.js", "/favicon.svg").permitAll()
                        .requestMatchers("/actuator/health", "/docs", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/problem+json");
                            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"A valid bearer token is required\"}");
                        }))
                .addFilterBefore(new BearerTokenFilter(auth), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new AuthRateLimitFilter(clock, authRequests), BearerTokenFilter.class)
                .build();
    }
}
