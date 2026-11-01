package com.kartgen.hireflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ExtensionTokenAuthFilter extensionTokenFilter;
    private final JwtAuthenticationFilter jwtFilter;
    private final WebhookAuthFilter webhookAuthFilter;
    private final ObjectMapper objectMapper;

    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        objectMapper.writeValue(response.getOutputStream(), ApiError.builder()
                                .status(HttpServletResponse.SC_UNAUTHORIZED)
                                .error("Unauthorized")
                                .message("Authentication required")
                                .path(request.getRequestURI())
                                .build());
                    })
                    .accessDeniedHandler((request, response, deniedException) -> {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        objectMapper.writeValue(response.getOutputStream(), ApiError.builder()
                                .status(HttpServletResponse.SC_FORBIDDEN)
                                .error("Forbidden")
                                .message("You do not have permission to perform this action")
                                .path(request.getRequestURI())
                                .build());
                    })
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/", "/index.html", "/css/**", "/js/**", "/favicon.ico").permitAll();
                auth.requestMatchers("/api/v1/auth/**").permitAll();
                auth.requestMatchers("/api/v1/local-s3/**").permitAll();
                auth.requestMatchers("/api/v1/mail-webhook/**").permitAll(); // WebhookAuthFilter enforces secret
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                if (h2ConsoleEnabled) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(webhookAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(extensionTokenFilter, WebhookAuthFilter.class)
            .addFilterAfter(jwtFilter, ExtensionTokenAuthFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
