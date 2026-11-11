package com.nereonix.nereonet_mvp.core.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void createUserEncodesPasswordAndCanBeFoundByEmail() {
        userService.createUser("Jane.Doe@Example.com", "Jane Doe", "secret123", Role.OFFICE_USER, null, null);

        User found = userRepository.findByEmail("jane.doe@example.com").orElseThrow();
        assertThat(found.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(passwordEncoder.matches("secret123", found.getPasswordHash())).isTrue();
    }
}
