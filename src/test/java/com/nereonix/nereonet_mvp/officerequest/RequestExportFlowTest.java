package com.nereonix.nereonet_mvp.officerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestExportRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P21: the request export shows its own result. Select rows (select-all) or export all not exported under the ship
 * filter; the POST records the export and comes back to the list showing exactly those requests, exported, with
 * "2 requests exported · Download again"; the file comes from a GET URL. Exporting again asks first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class RequestExportFlowTest {

    private static final Pattern ROW_NUMBER = Pattern.compile(">Request #(\\d+)</a>");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    ItemRequestRepository requestRepository;

    @Autowired
    RequestLineRepository lineRepository;

    @Autowired
    RequestExportRepository exportRepository;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    Ship demo;
    Ship other;
    User cook;
    User master;
    User office;

    @BeforeEach
    void setUp() {
        demo = ship("9074729", "MV Demo");
        other = ship("9176187", "MV Other");
        cook = userService.createUser("cook@example.com", "Jane Cook", "secret123", Role.CHIEF_COOK, demo, null);
        master = userService.createUser("master@example.com", "John Master", "secret123", Role.MASTER, demo, null);
        office = userRepository.findByEmail("office@example.com").orElseThrow();
    }

    @Test
    void selectedRequestsAreExportedAndTheListShowsThemExported() throws Exception {
        ItemRequest rice = request(demo, "2026-11-10T09:30:00Z", null);
        addLine(rice, item(demo, "Basmati rice", "kg"), "25.500");
        addLine(rice, item(demo, "Eggs", "pcs"), "120");
        ItemRequest flour = request(demo, "2026-11-09T09:30:00Z", null);
        addLine(flour, item(demo, "Flour", "kg"), "10");
        ItemRequest done = request(demo, "2026-11-08T09:30:00Z", Instant.parse("2026-11-08T12:00:00Z"));

        // The default view: not exported yet, without the Exported column (every row would say "Not yet").
        String list = flat(html(get("/office/requests")));
        assertThat(numbers(list)).containsExactly(rice.getId(), flour.getId());
        assertThat(list).contains("<option value=\"\">Not exported yet</option>")
                .doesNotContain(">Exported</th>");
        // Select-all: in the table header and (phones) the selection line, both shown by the script only; the
        // selection count; Export selected needs a selection and says why it is disabled.
        assertThat(list).containsPattern("(?s)<th scope=\"col\" class=\"cell-select\">.*?<label class=\"select-target\" hidden data-selection-only>"
                        + "\\s*<input class=\"form-check-input\" type=\"checkbox\" data-select-all aria-label=\"Select all requests on this page\">")
                .contains("<div class=\"list-selection\" hidden data-selection-only>",
                        "id=\"selectAllPhone\" data-select-all", ">Select all on this page</label>",
                        "<p id=\"selectionCount\" class=\"list-selection-count\" role=\"status\" data-selection-count",
                        "data-none=\"Select the requests to export\" data-one=\"1 selected\" data-many=\"{count} selected\"",
                        "form=\"exportForm\" class=\"btn btn-primary\" data-needs-selection aria-describedby=\"selectionCount\"",
                        ">Export selected</span>",
                        "<form id=\"exportForm\" action=\"/office/requests/export\" method=\"post\" data-selection",
                        "name=\"ids\" value=\"" + rice.getId() + "\" id=\"select" + rice.getId() + "\" data-select-row")
                .doesNotContain("data-download");
        // A row opens its request when clicked outside its checkbox.
        assertThat(list).contains("<tr data-row-href=\"/office/requests/" + rice.getId() + "\">");
        String rowsScript = script("list-rows.js");
        assertThat(rowsScript).contains("[data-select-all]", "indeterminate", "data-needs-selection",
                "tr[data-row-href]", ".cell-select");
        assertThat(sidebarCount(list)).isEqualTo("2");

        // Select-all checks every row of the page: both are sent.
        MvcResult exported = mockMvc.perform(post("/office/requests/export")
                        .param("ids", rice.getId().toString(), flour.getId().toString()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "request.export.done"))
                .andReturn();
        long exportId = exportRepository.findAll().getLast().getId();
        String back = "/office/requests?exported=yes&exportId=" + exportId;
        assertThat(exported.getResponse().getRedirectedUrl()).isEqualTo(back);
        assertThat(exported.getFlashMap().get("successDownloadUrl")).isEqualTo("/office/requests/exports/" + exportId);

        // Without a reload: the list shows exactly the exported requests, exported now by the office user, and the
        // sidebar counter has dropped; the message starts the download and can repeat it.
        String result = html(get(back).flashAttrs(exported.getFlashMap()));
        assertThat(numbers(result)).containsExactly(rice.getId(), flour.getId());
        assertThat(result).contains("2 requests exported", "<a class=\"alert-link\" href=\"/office/requests/exports/"
                        + exportId + "\" download data-auto-download>Download again</a>",
                ">Exported</th>", "<span class=\"exported-by-name\">by " + office.getFullName() + "</span>",
                "class=\"filter-chip-text\">Export: ", "class=\"filter-chip-text\">Export status: Exported</span>");
        assertThat(sidebarCount(result)).isNull();
        assertThat(script("download.js")).contains("a[data-auto-download]", "link.click()");
        for (ItemRequest r : List.of(rice, flour)) {
            ItemRequest saved = requestRepository.findById(r.getId()).orElseThrow();
            assertThat(saved.getExportedAt()).isNotNull();
            assertThat(saved.getExportedBy().getId()).isEqualTo(office.getId());
        }
        assertThat(requestRepository.findById(done.getId()).orElseThrow().getExportedAt())
                .isEqualTo(Instant.parse("2026-11-08T12:00:00Z"));

        // The file, from its GET URL: downloading changes nothing and can be repeated.
        for (int i = 0; i < 2; i++) {
            byte[] body = mockMvc.perform(get("/office/requests/exports/{id}", exportId))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.startsWith("attachment; filename=\"approved-requests-")))
                    .andReturn().getResponse().getContentAsByteArray();
            String csv = new String(body, StandardCharsets.UTF_8);
            assertThat(csv).startsWith("﻿");
            assertThat(csv.substring(1).split("\r\n")).containsExactly(
                    "imo_number,ship_name,request_id,approved_at,approved_by,item_name,unit,quantity",
                    "9074729,MV Demo," + rice.getId() + ",2026-11-10T09:30:00Z,John Master,Basmati rice,kg,25.5",
                    "9074729,MV Demo," + rice.getId() + ",2026-11-10T09:30:00Z,John Master,Eggs,pcs,120",
                    "9074729,MV Demo," + flour.getId() + ",2026-11-09T09:30:00Z,John Master,Flour,kg,10");
        }
        assertThat(exportRepository.count()).isEqualTo(1);
        mockMvc.perform(get("/office/requests/exports/{id}", exportId + 1)).andExpect(status().isNotFound());

        // The other views of the filter.
        assertThat(numbers(html(get("/office/requests").param("exported", "yes"))))
                .containsExactly(rice.getId(), flour.getId(), done.getId());
        assertThat(numbers(html(get("/office/requests").param("exported", "all"))))
                .containsExactly(rice.getId(), flour.getId(), done.getId());
        assertThat(html(get("/office/requests"))).contains("Every approved request has been exported.")
                .doesNotContain(">Export all not exported");
    }

    @Test
    void exportAllExportsWhatIsNotExportedUnderTheShipFilter() throws Exception {
        ItemRequest a1 = request(demo, "2026-11-10T09:30:00Z", null);
        ItemRequest a2 = request(demo, "2026-11-09T09:30:00Z", null);
        ItemRequest b1 = request(other, "2026-11-10T10:30:00Z", null);

        assertThat(html(get("/office/requests"))).contains(">Export all not exported (3)</span>");
        String filtered = html(get("/office/requests").param("shipId", demo.getId().toString()));
        assertThat(filtered).contains("<form id=\"exportAllForm\" action=\"/office/requests/export-all\" method=\"post\">",
                        "<input type=\"hidden\" name=\"shipId\" value=\"" + demo.getId() + "\">",
                        "form=\"exportAllForm\" class=\"btn btn-outline-secondary\"", ">Export all not exported (2)</span>");

        // Without selecting anything: the ship filter's requests only, back to the list with its ship filter.
        MvcResult all = mockMvc.perform(post("/office/requests/export-all")
                        .param("shipId", demo.getId().toString()).with(csrf()))
                .andExpect(flash().attribute("successMessage", "request.export.done"))
                .andReturn();
        long exportId = exportRepository.findAll().getLast().getId();
        assertThat(all.getResponse().getRedirectedUrl())
                .isEqualTo("/office/requests?shipId=" + demo.getId() + "&exported=yes&exportId=" + exportId);
        assertThat((Object[]) all.getFlashMap().get("successArgs")).containsExactly(2);
        assertThat(exportRepository.findById(exportId).orElseThrow().getRequestIds())
                .containsExactly(a1.getId(), a2.getId());
        assertThat(requestRepository.findById(b1.getId()).orElseThrow().getExportedAt()).isNull();
        assertThat(html(get(all.getResponse().getRedirectedUrl()).flashAttrs(all.getFlashMap())))
                .contains("2 requests exported").doesNotContain(">Export all not exported");

        // On the ship's tab: that ship, back to the tab.
        String tab = "/office/ships/" + other.getId() + "/requests";
        assertThat(html(get(tab))).contains(">Export all not exported (1)</span>",
                "<input type=\"hidden\" name=\"returnShipId\" value=\"" + other.getId() + "\">");
        MvcResult one = mockMvc.perform(post("/office/requests/export-all")
                        .param("returnShipId", other.getId().toString()).with(csrf()))
                .andReturn();
        assertThat(one.getResponse().getRedirectedUrl())
                .isEqualTo(tab + "?exported=yes&exportId=" + exportRepository.findAll().getLast().getId());
        assertThat(html(get(one.getResponse().getRedirectedUrl()).flashAttrs(one.getFlashMap())))
                .contains("1 request exported");

        // Nothing left (another office user was quicker): a message, nothing recorded.
        mockMvc.perform(post("/office/requests/export-all").param("returnShipId", other.getId().toString()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "request.export.nothing"));
        assertThat(exportRepository.count()).isEqualTo(2);
    }

    @Test
    void exportingAgainAsksFirst() throws Exception {
        Instant before = Instant.parse("2026-11-08T12:00:00Z");
        ItemRequest done = request(demo, "2026-11-08T09:30:00Z", before);
        ItemRequest fresh = request(demo, "2026-11-09T09:30:00Z", null);

        // The dialog (js/confirm.js) opens when a checked row was exported before; neutral: it can be repeated.
        String list = flat(html(get("/office/requests").param("exported", "all")));
        assertThat(list).contains("data-confirm-title=\"Export requests that were exported before?\"",
                "data-confirm-label=\"Export again\" data-confirm-tone=\"neutral\"",
                "data-confirm-when=\"input[name=ids][data-exported]:checked\"",
                "id=\"select" + done.getId() + "\" data-select-row aria-label=\"Request #" + done.getId() + "\" data-exported=\"true\"")
                .doesNotContain("aria-label=\"Request #" + fresh.getId() + "\" data-exported");
        assertThat(script("confirm.js")).contains("dataset.confirmWhen", "confirmed.name = 'confirmed'");

        // Without the script: the plain confirmation page, which posts the same requests with confirmed=true.
        String page = flat(html(post("/office/requests/export")
                .param("ids", done.getId().toString(), fresh.getId().toString())
                .param("shipId", demo.getId().toString()).with(csrf())));
        assertThat(page).contains("Export requests that were exported before?",
                "Some of the selected requests were exported before.",
                "<input type=\"hidden\" name=\"ids\" value=\"" + done.getId() + "," + fresh.getId() + "\">",
                "<input type=\"hidden\" name=\"confirmed\" value=\"true\">", ">Export again</button>",
                "href=\"/office/requests?shipId=" + demo.getId() + "\">Cancel</a>");
        assertThat(requestRepository.findById(done.getId()).orElseThrow().getExportedAt()).isEqualTo(before);
        assertThat(requestRepository.findById(fresh.getId()).orElseThrow().getExportedAt()).isNull();
        assertThat(exportRepository.count()).isZero();

        // Confirmed (the page's form: the ids in one field): exported again, now, by the office user.
        mockMvc.perform(post("/office/requests/export")
                        .param("ids", done.getId() + "," + fresh.getId()).param("confirmed", "true")
                        .param("shipId", demo.getId().toString()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "request.export.done"));
        ItemRequest again = requestRepository.findById(done.getId()).orElseThrow();
        assertThat(again.getExportedAt()).isAfter(before);
        assertThat(again.getExportedBy().getId()).isEqualTo(office.getId());
        assertThat(exportRepository.findAll().getLast().getRequestIds()).containsExactlyInAnyOrder(done.getId(), fresh.getId());
    }

    @Test
    void theOverviewExportComesBackToTheOverview() throws Exception {
        ItemRequest request = request(demo, "2026-11-10T09:30:00Z", null);
        String overview = html(get("/office"));
        assertThat(overview).contains("<input type=\"hidden\" name=\"returnTo\" value=\"overview\">");

        MvcResult result = mockMvc.perform(post("/office/requests/export").param("ids", request.getId().toString())
                        .param("returnTo", "overview").with(csrf()))
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/office");
        assertThat(html(get("/office").flashAttrs(result.getFlashMap())))
                .contains("1 request exported", "data-auto-download>Download again</a>",
                        "Nothing needs your attention");
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    // Every run of white space as one space: attributes the template puts on several lines.
    private static String flat(String html) {
        return html.replaceAll("\\s+", " ");
    }

    private static List<Long> numbers(String html) {
        return ROW_NUMBER.matcher(html).results().map(m -> Long.parseLong(m.group(1))).toList();
    }

    // The Requests counter in the sidebar, or null when there is none.
    private static String sidebarCount(String html) {
        Matcher count = Pattern.compile("(?s)href=\"/office/requests\"[^>]*>.*?>Requests</span>\\s*(<span class=\"nav-count num\" aria-hidden=\"true\">(\\d+)</span>)?")
                .matcher(html);
        assertThat(count.find()).isTrue();
        return count.group(2);
    }

    private static String script(String name) throws Exception {
        return new ClassPathResource("static/js/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private Ship ship(String imo, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private ItemRequest request(Ship ship, String approvedAt, Instant exportedAt) {
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(cook);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedBy(master);
        request.setDecidedAt(Instant.parse(approvedAt));
        request.setCreatedAt(Instant.parse(approvedAt).minusSeconds(3600));
        request.setExportedAt(exportedAt);
        return requestRepository.save(request);
    }

    private Item item(Ship ship, String name, String unit) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCreatedAt(Instant.now());
        return itemRepository.save(item);
    }

    private void addLine(ItemRequest request, Item item, String quantity) {
        RequestLine line = new RequestLine();
        line.setRequest(request);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        request.getLines().add(lineRepository.save(line));
    }
}
