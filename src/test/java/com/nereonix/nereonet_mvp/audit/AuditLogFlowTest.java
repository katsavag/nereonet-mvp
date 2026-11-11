package com.nereonix.nereonet_mvp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class AuditLogFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuditRepository auditRepository;

    @Test
    void creatingShipIsAudited() throws Exception {
        mockMvc.perform(post("/office/ships/new")
                        .param("imoNumber", "9074729")
                        .param("name", "MV Aegean Spirit")
                        .param("type", "BULK_CARRIER")
                        .param("flag", "Greece (GR)")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));

        List<AuditEntry> entries = auditRepository.findAll();
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.getFirst();
        assertThat(entry.getAction()).isEqualTo("SHIP_CREATED");
        assertThat(entry.getActor().getEmail()).isEqualTo("office@example.com");
        assertThat(entry.getShip().getName()).isEqualTo("MV Aegean Spirit");
        assertThat(entry.getOccurredAt()).isNotNull();

        mockMvc.perform(get("/office/audit"))
                .andExpect(content().string(Matchers.containsString("Ship added")));

        mockMvc.perform(get("/office/audit").param("shipId", entry.getShip().getId().toString()))
                .andExpect(content().string(Matchers.containsString("Ship added")))
                .andExpect(content().string(Matchers.containsString("MV Aegean Spirit")));
    }
}
