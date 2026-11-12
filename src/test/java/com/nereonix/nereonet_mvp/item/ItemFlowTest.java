package com.nereonix.nereonet_mvp.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.importfile.ImportMappingRepository;
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

    @Autowired
    ImportMappingRepository importMappingRepository;

    @Test
    void officeUserAddsItemToShip() throws Exception {
        Ship ship = ship("9074729", "MV Demo");

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

    // P24: a purchasing system's export, its own column names and order, a title above them and a column Nereonet does
    // not use. Each row says whether it adds, updates or changes nothing; the mapping is saved with the import.
    @Test
    void aPurchasingFileIsMappedAndAddsAndUpdatesItems() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        addItem(ship, "Olive oil", "lt", "Oils");
        addItem(ship, "Basmati rice", "kg", "Dry goods");
        String base = "/office/ships/" + ship.getId() + "/items/import";

        MockHttpSession session = new MockHttpSession();
        upload(session, base, "stores.csv", """
                Provisions order MV Demo;;;
                Item code;Description;UoM;Product group
                P-001;Olive oil;lt;Oils
                P-002;Basmati rice;bag;Dry goods
                P-003;Feta cheese;kg;Dairy
                """, base + "/map");

        String map = html(get(base + "/map").session(session));
        assertThat(map).contains("File: stores.csv", "First 3 of 3 rows", "Not imported:", "A · Item code",
                        "To: Name", "To: Unit", "To: Category")
                .containsPattern("<option value=\"1\"\\s+selected=\"selected\">Row 2: Item code, Description");
        assertSelected(map, "name", 1);
        assertSelected(map, "unit", 2);
        assertSelected(map, "category", 3);

        mockMvc.perform(post(base + "/map").session(session).with(csrf()))
                .andExpect(redirectedUrl(base + "/review"));
        String review = html(get(base + "/review").session(session));
        assertThat(review).contains("Will import: 1 new, 1 updates, 1 without changes (skipped).",
                        "value=\"Feta cheese\"", "<th scope=\"col\" class=\"import-col-name\">Name</th>")
                .containsPattern("(?s)<span class=\"import-row-number num\">3</span>\\s*<span class=\"badge[^\"]*\">No change</span>")
                .containsPattern("(?s)<span class=\"import-row-number num\">4</span>\\s*<span class=\"badge[^\"]*\">Update</span>")
                .containsPattern("(?s)<span class=\"import-row-number num\">5</span>\\s*<span class=\"badge[^\"]*\">New</span>");
        assertThat(itemRepository.findAllByShipIdOrderByNameAsc(ship.getId())).hasSize(2);

        mockMvc.perform(post(base + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(base + "/done"));
        assertThat(html(get(base + "/done").session(session)))
                .contains("<dt>Added</dt>", "<dt>Updated</dt>", "<dt>Skipped</dt>", "Go to the items",
                        "href=\"/office/ships/" + ship.getId() + "/items\"");
        assertThat(itemRepository.findAllByShipIdOrderByNameAsc(ship.getId()))
                .extracting(Item::getName, Item::getUnit, Item::getCategory)
                .containsExactly(tuple("Basmati rice", "bag", "Dry goods"), tuple("Feta cheese", "kg", "Dairy"),
                        tuple("Olive oil", "lt", "Oils"));
        assertThat(importMappingRepository.findAll()).hasSize(1);
    }

    // The mapping confirmed for one ship's file is suggested again for a file with the same column names, for any ship.
    @Test
    void theSavedMappingIsSuggestedAgain() throws Exception {
        Ship first = ship("9074729", "MV Demo");
        Ship second = ship("9321483", "MV Other");
        String firstBase = "/office/ships/" + first.getId() + "/items/import";
        String secondBase = "/office/ships/" + second.getId() + "/items/import";
        String csv = """
                Ref,Text,Pack,Kind
                1,Olive oil,lt,Oils
                """;

        MockHttpSession session = new MockHttpSession();
        upload(session, firstBase, "order.csv", csv, firstBase + "/map");
        assertThat(html(get(firstBase + "/map").session(session))).doesNotContain("Filled in from the mapping saved");
        mockMvc.perform(post(firstBase + "/map").session(session).with(csrf()).param("column.name", "1")
                        .param("column.unit", "2").param("column.category", "3"))
                .andExpect(redirectedUrl(firstBase + "/review"));
        mockMvc.perform(post(firstBase + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(firstBase + "/done"));

        MockHttpSession again = new MockHttpSession();
        upload(again, secondBase, "order.csv", csv, secondBase + "/map");
        String map = html(get(secondBase + "/map").session(again));
        assertThat(map).contains("Filled in from the mapping saved on");
        assertSelected(map, "name", 1);
        assertSelected(map, "unit", 2);
        assertSelected(map, "category", 3);
    }

    // A required field without a column keeps the user on the mapping step; the optional Category does not.
    @Test
    void aMissingUnitColumnBlocksContinue() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        String base = "/office/ships/" + ship.getId() + "/items/import";
        MockHttpSession session = new MockHttpSession();
        upload(session, base, "items.csv", """
                Name,Supplier
                Olive oil,Acme
                """, base + "/map");

        String blocked = mockMvc.perform(post(base + "/map").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(blocked).contains("The mapping has 1 problem", "Choose the column that holds it. It is required.", "href=\"#column-unit\"")
                .doesNotContain("href=\"#column-category\"", "is-invalid\" id=\"column-category\"");
        mockMvc.perform(get(base + "/review").session(session)).andExpect(redirectedUrl(base + "/map"));
        assertThat(itemRepository.findAllByShipIdOrderByNameAsc(ship.getId())).isEmpty();
    }

    // An import started for one ship is never continued or saved for another: its steps lead back to that ship's upload.
    @Test
    void anImportStartedForOneShipIsNotUsedForAnother() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        Ship other = ship("9321483", "MV Other");
        String base = "/office/ships/" + ship.getId() + "/items/import";
        String otherBase = "/office/ships/" + other.getId() + "/items/import";
        MockHttpSession session = new MockHttpSession();
        upload(session, base, "items.csv", "Name,Unit,Category\nOlive oil,lt,Oils\n", base + "/review");

        mockMvc.perform(get(otherBase + "/review").session(session)).andExpect(redirectedUrl(otherBase));
        mockMvc.perform(post(otherBase + "/submit").session(session).with(csrf())).andExpect(redirectedUrl(otherBase));
        assertThat(itemRepository.findAllByShipIdOrderByNameAsc(other.getId())).isEmpty();
        assertThat(itemRepository.findAllByShipIdOrderByNameAsc(ship.getId())).isEmpty();
    }

    private void upload(MockHttpSession session, String base, String filename, String csv, String next)
            throws Exception {
        mockMvc.perform(multipart(base).file(new MockMultipartFile("file", filename, "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8))).session(session).with(csrf()))
                .andExpect(redirectedUrl(next));
    }

    private static void assertSelected(String map, String key, int column) {
        assertThat(map).as(key + " -> column " + column)
                .containsPattern("(?s)<select class=\"form-select\" id=\"column-" + key + "\" name=\"column\\." + key
                        + "\"[^>]*>(\\s*<option value=\"\\d*\">[^<]*</option>)*\\s*<option value=\"" + column
                        + "\"\\s+selected=\"selected\">");
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(content().string(Matchers.startsWith("<!DOCTYPE html>")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void addItem(Ship ship, String name, String unit, String category) {
        itemRepository.save(ItemService.newItem(ship, name, unit, category));
    }

    private Ship ship(String imo, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}
