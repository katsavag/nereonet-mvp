package com.nereonix.nereonet_mvp.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // /error is public so error pages are not replaced by a login redirect.
                        .requestMatchers("/login", "/register/**", "/error",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        .requestMatchers("/office/**").hasRole("OFFICE_USER")
                        .requestMatchers("/ship/library/new", "/ship/library/particulars",
                                "/ship/library/*/edit", "/ship/library/*/retire",
                                "/ship/library/*/acknowledgements").hasRole("MASTER")
                        .requestMatchers("/ship/**").hasAnyRole("MASTER", "CHIEF_COOK", "CREW_MEMBER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(redirectByRole())
                        .failureHandler(logFailure()))
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                .addFilterBefore(new UploadTooLargeFilter(), CsrfFilter.class);
        return http.build();
    }

    private static AuthenticationSuccessHandler redirectByRole() {
        return (request, response, authentication) -> {
            CurrentUser user = (CurrentUser) authentication.getPrincipal();
            log.info("Login: {}", user.getUsername());
            response.sendRedirect(request.getContextPath() + user.getHomeUrl());
        };
    }

    // Logs the email only, never the password.
    private static AuthenticationFailureHandler logFailure() {
        AuthenticationFailureHandler redirect = new SimpleUrlAuthenticationFailureHandler("/login?error");
        return (request, response, exception) -> {
            log.info("Login failed for {}: {}", request.getParameter("email"), exception.getMessage());
            redirect.onAuthenticationFailure(request, response, exception);
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
