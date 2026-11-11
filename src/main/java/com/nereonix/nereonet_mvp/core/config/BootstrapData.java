package com.nereonix.nereonet_mvp.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.UserService;

// Creates the first office user on startup if there is none.
@Component
public class BootstrapData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapData.class);

    private final UserService userService;
    private final String officeEmail;
    private final String officePassword;

    public BootstrapData(UserService userService,
                         @Value("${app.bootstrap.office-email}") String officeEmail,
                         @Value("${app.bootstrap.office-password}") String officePassword) {
        this.userService = userService;
        this.officeEmail = officeEmail;
        this.officePassword = officePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userService.officeUserExists()) {
            return;
        }
        if (officeEmail.isBlank() || officePassword.isBlank()) {
            throw new IllegalStateException(
                    "No office user exists: set app.bootstrap.office-email and app.bootstrap.office-password");
        }
        userService.createUser(officeEmail, "John Doe", officePassword, Role.OFFICE_USER, null, null);
        log.info("Created the first office user {}", officeEmail);
    }
}
