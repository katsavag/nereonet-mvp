package com.nereonix.nereonet_mvp.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Instant;
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

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ItemFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    ItemRepository itemRepository;

    @Test
    void officeUserAddsItemToShip() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        mockMvc.perform(post("/office/ships/{id}/items/new", ship.getId())
                        .param("name", "Basmati rice")
                        .param("unit", "kg")
                        .param("category", "Dry goods")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + ship.getId() + "/items"));

        List<Item> items = itemRepository.findAllByShipIdOrderByNameAsc(ship.getId());
        assertThat(items).hasSize(1);
        Item item = items.getFirst();
        assertThat(item.getName()).isEqualTo("Basmati rice");
        assertThat(item.getUnit()).isEqualTo("kg");
        assertThat(item.getCategory()).isEqualTo("Dry goods");
        assertThat(item.isActive()).isTrue();
        assertThat(item.getCreatedAt()).isNotNull();

        mockMvc.perform(get("/office/ships/{id}/items", ship.getId()))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("Basmati rice"),
                        Matchers.containsString("Dry goods"))));
    }
}
