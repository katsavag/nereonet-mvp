package com.nereonix.nereonet_mvp.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserDetailsService users,
                                            PasswordEncoder passwordEncoder) throws Exception {
        http
                .authenticationManager(new ProviderManager(signInProvider(users, passwordEncoder)))
                .authorizeHttpRequests(auth -> auth
                        // /error is public so error pages are not replaced by a login redirect.
                        .requestMatchers("/login", "/register/**", "/password/**", "/error",
                                "/css/**", "/js/**", "/vendor/**", "/fonts/**", "/images/**",
                                "/favicon*.svg", "/favicon.ico").permitAll()
                        .requestMatchers("/office/**").hasRole("OFFICE_USER")
                        .requestMatchers("/ship/library/new", "/ship/library/particulars",
                                "/ship/library/*/edit", "/ship/library/*/retire",
                                "/ship/library/*/acknowledgements").hasRole("MASTER")
                        .requestMatchers("/ship/**").hasAnyRole("MASTER", "CHIEF_COOK", "CREW_MEMBER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(returnAfterSignIn())
                        .failureHandler(logFailure()))
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                // A form sent after the session expired: sign in again, then back to the page (P12).
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(new SignedOutHandler()))
                .addFilterBefore(new UploadTooLargeFilter(), CsrfFilter.class);
        return http.build();
    }

    // Back to the page the user was on (see ReturnTo), else /office or /ship by role.
    private static AuthenticationSuccessHandler returnAfterSignIn() {
        return (request, response, authentication) -> {
            CurrentUser user = (CurrentUser) authentication.getPrincipal();
            log.info("Login: {}", user.getUsername());
            response.sendRedirect(request.getContextPath() + ReturnTo.afterSignIn(request, response, user));
        };
    }

    // Logs the email only, never the password. A deactivated account with the right password gets its own message
    // (/login?deactivated, P13); every other failure the generic one (/login?error).
    private static AuthenticationFailureHandler logFailure() {
        AuthenticationFailureHandler wrong = new SimpleUrlAuthenticationFailureHandler("/login?error");
        AuthenticationFailureHandler deactivated = new SimpleUrlAuthenticationFailureHandler("/login?deactivated");
        return (request, response, exception) -> {
            log.info("Login failed for {}: {}", request.getParameter("email"), exception.getMessage());
            (exception instanceof DisabledException ? deactivated : wrong)
                    .onAuthenticationFailure(request, response, exception);
        };
    }

    // Form login (P13). By default Spring Security checks "enabled" before the password, so anyone who knows an
    // email would learn that its account is deactivated. Here it is checked only after the password matched: a wrong
    // password always gets "Wrong email or password.", whatever the account's state. The filter chain's only
    // provider (no fallback to Spring Boot's default one, which checks in the default order).
    private static DaoAuthenticationProvider signInProvider(AppUserDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        // CurrentUser is never locked or expired; "enabled" is the only state that can fail.
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) {
                throw new DisabledException("User is deactivated");
            }
        });
        return provider;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
