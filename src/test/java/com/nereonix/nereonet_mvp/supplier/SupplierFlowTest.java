package com.nereonix.nereonet_mvp.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

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
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.supplier.ChannelType;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P44, the suppliers the office asks for quotes: the list (empty state, active and inactive), adding one with its
 * checks, editing it (a name taken by another supplier is refused) and the audit entries. Office users only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class SupplierFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired SupplierRepository supplierRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;

    @Test
    void theOfficeAddsASupplierAndEditsIt() throws Exception {
        // No supplier yet: the empty state with Add supplier.
        assertThat(page("/office/suppliers")).contains("No suppliers yet", "href=\"/office/suppliers/new\"");

        // The checks: name, email and currency required, a three-letter currency, a web address.
        String errors = mockMvc.perform(post("/office/suppliers").param("name", " ").param("email", "nope")
                        .param("currency", "EURO").param("website", "www.vanos.gr").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(errors).contains("Enter the name of the supplier.", "Enter an email address such as sales@example.com.",
                "Enter a three-letter currency code, such as EUR.", "Enter a web address starting with https://");
        assertThat(supplierRepository.count()).isZero();

        mockMvc.perform(post("/office/suppliers").param("name", " VANOS (Piraeus) ")
                        .param("email", "quotes.vanos@example.com").param("portsServed", "Piraeus, Perama")
                        .param("currency", "eur").param("website", "https://www.vanos.gr/").param("active", "true")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/suppliers"))
                .andExpect(flash().attribute("successMessage", "supplier.added"));
        Supplier vanos = supplierRepository.findAll().getFirst();
        assertThat(vanos.getName()).isEqualTo("VANOS (Piraeus)");
        assertThat(vanos.getCurrency()).isEqualTo("EUR");
        assertThat(vanos.getChannel()).isEqualTo(ChannelType.EMAIL);
        assertThat(vanos.isActive()).isTrue();
        assertThat(vanos.serves("PIRAEUS")).isTrue();
        assertThat(vanos.serves("Rotterdam")).isFalse();
        assertThat(audit("SUPPLIER_ADDED")).singleElement().extracting(AuditEntry::getDetails).asString()
                .contains("VANOS (Piraeus)");

        assertThat(page("/office/suppliers")).contains("VANOS (Piraeus)", "quotes.vanos@example.com", "Piraeus, Perama",
                ">EUR<", "https://www.vanos.gr/", "href=\"/office/suppliers/" + vanos.getId() + "/edit\"");

        // A second supplier cannot take its name, whatever the case.
        supplier("Harbour Provisions", "USD", true);
        String taken = mockMvc.perform(post("/office/suppliers").param("name", "vanos (piraeus)")
                        .param("email", "other@example.com").param("currency", "EUR").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(taken).contains("There is already a supplier with this name.");

        // Edit: inactive (the checkbox cleared), and it moves to the Inactive filter.
        assertThat(page("/office/suppliers/" + vanos.getId() + "/edit")).contains("value=\"VANOS (Piraeus)\"",
                "value=\"quotes.vanos@example.com\"");
        mockMvc.perform(post("/office/suppliers/{id}", vanos.getId()).param("name", "VANOS (Piraeus)")
                        .param("email", "quotes.vanos@example.com").param("portsServed", "Piraeus, Perama, Elefsina")
                        .param("currency", "EUR").param("website", "https://www.vanos.gr/").param("_active", "on")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/suppliers"))
                .andExpect(flash().attribute("successMessage", "supplier.saved"));
        assertThat(vanos.isActive()).isFalse();
        assertThat(vanos.getPortsServed()).isEqualTo("Piraeus, Perama, Elefsina");
        assertThat(audit("SUPPLIER_UPDATED")).hasSize(1);
        assertThat(page("/office/suppliers?status=active")).contains("Harbour Provisions").doesNotContain("VANOS");
        assertThat(page("/office/suppliers?status=inactive")).contains("VANOS (Piraeus)")
                .doesNotContain("Harbour Provisions");
        // The other supplier's name is refused on edit as well.
        String takenOnEdit = mockMvc.perform(post("/office/suppliers/{id}", vanos.getId())
                        .param("name", "Harbour Provisions").param("email", "quotes.vanos@example.com")
                        .param("currency", "EUR").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(takenOnEdit).contains("There is already a supplier with this name.");
    }

    @Test
    void suppliersAreTheOfficesOnlyAndSpeakGreek() throws Exception {
        assertThat(page("/office/suppliers?lang=el")).contains("Προμηθευτές", "Δεν υπάρχουν προμηθευτές ακόμη");

        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(),
                ship, Rank.MASTER);
        mockMvc.perform(get("/office/suppliers").with(user(new CurrentUser(master)))).andExpect(status().isForbidden());
    }

    private Supplier supplier(String name, String currency, boolean active) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setEmail(name.toLowerCase().replaceAll("[^a-z]", "") + "@example.com");
        supplier.setCurrency(currency);
        supplier.setActive(active);
        supplier.setChannel(ChannelType.EMAIL);
        supplier.setCreatedAt(Instant.now());
        return supplierRepository.save(supplier);
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
    }
}
