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
import com.nereonix.nereonet_mvp.core.audit.AuditService;

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

    @Autowired
    AuditService auditService;

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
                .andExpect(content().string(Matchers.containsString("Vessel added")));

        mockMvc.perform(get("/office/audit").param("shipId", entry.getShip().getId().toString()))
                .andExpect(content().string(Matchers.containsString("Vessel added")))
                .andExpect(content().string(Matchers.containsString("MV Aegean Spirit")));
    }

    // P39: the audit log shows 20 entries a page (50 or 100 on request) with the P19 pager under the table; the
    // search, filters and sort stay when the page changes, and a new filter starts again at page 1.
    @Test
    void theAuditLogShows20EntriesAPageAndKeepsTheFilters() throws Exception {
        for (int i = 1; i <= 45; i++) {
            auditService.record("TEST_ENTRY", null, "paged entry " + i);
        }
        auditService.record("TEST_ENTRY", null, "something else");

        String first = html(get("/office/audit").param("q", "paged").param("sort", "when").param("dir", "asc"));
        assertThat(rows(first)).isEqualTo(20);
        assertThat(first).contains("1 to 20 of 45", "paged entry 1<", "paged entry 20<").doesNotContain("paged entry 21<")
                // The page sizes: 20 (current, the default), 50 and 100.
                .containsPattern("(?s)<ul class=\"pager-size-list\"[^>]*>\\s*<li>\\s*<span class=\"pager-size-current num\" "
                        + "aria-current=\"true\">20</span>.*?>50</a>.*?>100</a>")
                // Next keeps the search and the sort; the default size is not written in the URL.
                .contains("rel=\"next\" href=\"/office/audit?q=paged&amp;sort=when&amp;dir=asc&amp;page=2\"")
                // The filter form sends the sort along, never the page: a new filter starts at page 1.
                .contains("<input type=\"hidden\" name=\"sort\" value=\"when\">")
                .doesNotContain("name=\"page\"");

        String second = html(get("/office/audit").param("q", "paged").param("sort", "when").param("dir", "asc")
                .param("page", "2"));
        assertThat(rows(second)).isEqualTo(20);
        assertThat(second).contains("21 to 40 of 45", "paged entry 21<", "paged entry 40<")
                .contains("rel=\"prev\" href=\"/office/audit?q=paged&amp;sort=when&amp;dir=asc\"");

        String last = html(get("/office/audit").param("q", "paged").param("sort", "when").param("dir", "asc")
                .param("page", "3"));
        assertThat(rows(last)).isEqualTo(5);
        assertThat(last).contains("41 to 45 of 45");

        String fifty = html(get("/office/audit").param("q", "paged").param("size", "50"));
        assertThat(rows(fifty)).isEqualTo(45);
        assertThat(fifty).contains("1 to 45 of 45", "<input type=\"hidden\" name=\"size\" value=\"50\">");
        // 25 is not a size of this list: the default, 20.
        assertThat(rows(html(get("/office/audit").param("q", "paged").param("size", "25")))).isEqualTo(20);
    }

    // P39: the From and To filters take a day within 30 years back and 1 year ahead, as their pickers do; another day
    // shows the limits under the field and filters nothing.
    @Test
    void anImpossibleDayInTheFiltersIsShownWithTheLimits() throws Exception {
        auditService.record("TEST_ENTRY", null, "an entry of today");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.format.DateTimeFormatter day =
                java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

        assertThat(html(get("/office/audit"))).contains("min=\"" + today.minusYears(30) + "\"",
                "max=\"" + today.plusYears(1) + "\"");

        String page = html(get("/office/audit").param("from", "0333-11-22"));
        assertThat(page).contains("Enter a date between " + day.format(today.minusYears(30)) + " and "
                        + day.format(today.plusYears(1)) + ".", "is-invalid", "value=\"0333-11-22\"", "an entry of today")
                .doesNotContain("22 Nov 0333"); // no filter chip: nothing is filtered
        // Not a date at all (only by hand in the URL): the same, with its own message.
        assertThat(html(get("/office/audit").param("to", "soon"))).contains("Enter a date.", "an entry of today");
    }

    private String html(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private static int rows(String html) {
        String body = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        return body.split("<tr>", -1).length - 1;
    }
}
