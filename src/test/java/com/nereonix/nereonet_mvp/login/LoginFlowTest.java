package com.nereonix.nereonet_mvp.login;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void officeUserLogsInAndOpensOfficePage() throws Exception {
        // Seeded by BootstrapData from application-test.yml
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login")
                        .param("email", "office@example.com")
                        .param("password", "office123")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andReturn().getRequest().getSession(false);

        mockMvc.perform(get("/office").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("John Doe")));
    }
}
