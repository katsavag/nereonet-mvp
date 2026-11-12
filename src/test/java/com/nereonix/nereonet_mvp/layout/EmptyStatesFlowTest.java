package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P25 empty states: on a fresh database (only the bootstrap office user) every list shows its empty state, with an
 * icon, a title, one sentence of help and an action, and the page header leaves out the actions the empty state
 * shows, so each page keeps one primary button. A filtered list with no rows stays "No results for these filters".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class EmptyStatesFlowTest {

    private static final String ACTIONS = "<div class=\"page-actions\">";
    private static final Pattern PAGE_ACTIONS = Pattern.compile(Pattern.quote(ACTIONS));

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired ItemRequestRepository requestRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void onAFreshDatabaseEveryFleetListShowsAnEmptyStateWithAnAction() throws Exception {
        // list, title, help, the action's link and label
        List<List<String>> lists = List.of(
                List.of("/office/ships", "No ships yet", "Add your first ship to invite its crew.",
                        "data-bs-target=\"#newShipModal\"", "Add ship"),
                List.of("/office/crew", "No crew yet", "Crew members belong to a ship: add your first ship, then invite its crew.",
                        "href=\"/office/ships\"", "Go to Ships"),
                List.of("/office/certificates", "No certificates yet", "on each crew member&#39;s page",
                        "href=\"/office/crew\"", "Go to Crew"),
                List.of("/office/announcements", "No announcements yet", "Send news and instructions to all ships or to the ships they concern",
                        "href=\"/office/announcements/new\"", "New announcement"),
                List.of("/office/requests", "No requests yet", "Galley requests approved on board appear here",
                        "href=\"/office/ships\"", "Go to Ships"),
                List.of("/office/audit", "Nothing recorded yet", "start with the setup steps on the Overview",
                        "href=\"/office\"", "Go to Overview"),
                List.of("/notifications", "No notifications yet", "Certificate reminders, new library documents",
                        "href=\"/office\"", "Go to start page"));
        for (List<String> l : lists) {
            String html = html(get(l.get(0)));
            String empty = emptyState(html, l.get(0));
            assertThat(empty).as(l.get(0)).contains("class=\"empty-state-icon\" aria-hidden=\"true\"", "<svg",
                    "<p class=\"empty-state-title\">" + l.get(1) + "</p>", l.get(2), "empty-state-actions",
                    l.get(3), l.get(4));
            // The action is not repeated in the page header, and the page keeps at most one primary button.
            assertThat(PAGE_ACTIONS.matcher(html).find()).as("page header actions on " + l.get(0)).isFalse();
            assertThat(count(withoutModals(html), "btn btn-primary")).as("primary buttons on " + l.get(0)).isLessThanOrEqualTo(1);
            assertThat(html).as(l.get(0)).doesNotContain("No results for these filters");
        }
        // Greek office screens.
        assertThat(html(get("/office/ships").param("lang", "el")))
                .contains("Δεν υπάρχουν πλοία ακόμη", "Προσθέστε το πρώτο σας πλοίο για να προσκαλέσετε το πλήρωμά του.");
    }

    @Test
    void withAShipEveryShipTabAndTheCrewListOfferTheirFirstAction() throws Exception {
        Ship ship = ship("MV Empty", "9074729");
        String tab = "/office/ships/" + ship.getId();

        String crew = html(get("/office/crew"));
        assertThat(emptyState(crew, "crew")).contains("Invite your ships&#39; crew one by one",
                "data-bs-target=\"#addCrewModal\"", "Add crew member", "href=\"/office/crew/import\"", "Import from file");
        assertThat(html(get(tab + "/crew"))).contains("No crew on this ship yet", "Invite this ship&#39;s crew");
        assertThat(emptyState(html(get(tab + "/items")), "items"))
                .contains("No galley items yet", "href=\"" + tab + "/items/new\"", "href=\"" + tab + "/items/import\"");
        assertThat(emptyState(html(get(tab + "/library")), "library"))
                .contains("No documents yet", "href=\"" + tab + "/library/new\"", "Add an item");
        assertThat(emptyState(html(get(tab + "/announcements")), "announcements"))
                .contains("href=\"/office/announcements/new?shipId=" + ship.getId() + "\"");
        assertThat(emptyState(html(get(tab + "/requests")), "requests"))
                .contains("No requests yet", "href=\"" + tab + "/items\"", "Go to galley items");
        assertThat(emptyState(html(get(tab)), "overview"))
                .contains("No active crew on board", "href=\"" + tab + "/crew?add=true\"", "Add crew member");
        // Only the ship header's actions (Edit, more): the tab heading leaves out what the empty state shows.
        for (String page : List.of(tab + "/crew", tab + "/items", tab + "/library", tab + "/announcements",
                tab + "/certificates", tab + "/certificates/list")) {
            assertThat(count(html(get(page)), ACTIONS)).as("action blocks on " + page).isEqualTo(1);
        }
    }

    @Test
    void aFilteredListWithNoRowsStaysNoResultsWithClearAll() throws Exception {
        ship("MV Busy", "9074729");
        String html = html(get("/office/ships/{id}/items", shipRepository.findAll().getFirst().getId()).param("q", "nothing"));
        assertThat(html).contains("class=\"card-body list-empty\"", "No results for these filters", "Clear all")
                .doesNotContain("class=\"empty-state\"", "No galley items yet");
        // The tab heading keeps its actions (with the ship header's): the list is not empty, only filtered.
        assertThat(count(html, ACTIONS)).isEqualTo(2);

        String audit = html(get("/office/audit").param("q", "nothing"));
        assertThat(audit).contains("No results for these filters").doesNotContain("class=\"empty-state\"");
    }

    @Test
    void listsWhoseDefaultViewHidesRecordsSayWhatIsHiddenAndLeadToIt() throws Exception {
        Ship ship = ship("MV Calm", "9074729");
        User anna = userService.createUser("anna@example.com", "Anna Able", "secret123", Role.CREW_MEMBER, ship,
                Rank.ABLE_SEAMAN);
        Certificate certificate = new Certificate();
        certificate.setUser(anna);
        certificate.setType("Passport");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(400));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(anna);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedAt(Instant.now());
        request.setCreatedAt(Instant.now());
        request.setExportedAt(Instant.now());
        requestRepository.save(request);

        String certificates = html(get("/office/certificates"));
        assertThat(emptyState(certificates, "certificates")).contains("No certificates expire within 90 days",
                "Every certificate is valid for more than 90 days.", "href=\"/office/certificates?status=all\"",
                "btn btn-outline-secondary", "Show all certificates");
        assertThat(certificates).contains("Export CSV"); // not first use: the header keeps its action

        String requests = html(get("/office/requests"));
        assertThat(emptyState(requests, "requests")).contains("Nothing left to export",
                "Every approved request has been exported.", "href=\"/office/requests?exported=yes\"",
                "Show exported requests");

        // A crew member without certificates: Add certificate moves from the card header into the empty state.
        String member = html(get("/office/crew/{id}/edit", anna.getId()));
        assertThat(emptyState(member, "member")).doesNotContain("No certificates yet"); // she has one
        User bob = userService.createUser("bob@example.com", "Bob Bosun", "secret123", Role.CREW_MEMBER, ship,
                Rank.BOSUN);
        assertThat(emptyState(html(get("/office/crew/{id}/edit", bob.getId())), "bob"))
                .contains("No certificates yet", "href=\"/office/crew/" + bob.getId() + "/certificates/new\"",
                        "btn btn-outline-secondary");
    }

    @Test
    void theCrewSeeWhoAddsTheirLibraryAndTheMasterGetsAddAnItem() throws Exception {
        Ship ship = ship("MV Crew", "9074729");
        User cook = userService.createUser("cook@example.com", "Chris Cook", "secret123", Role.CHIEF_COOK, ship,
                Rank.CHIEF_COOK);
        User master = userService.createUser("master@example.com", "Mia Master", "secret123", Role.MASTER, ship,
                Rank.MASTER);

        String crewLibrary = html(get("/ship/library").with(user(new CurrentUser(cook))));
        assertThat(emptyState(crewLibrary, "crew library")).contains("No documents yet",
                "appear here once the Master or the office adds them").doesNotContain("empty-state-actions");

        String masterLibrary = html(get("/ship/library").with(user(new CurrentUser(master))));
        assertThat(emptyState(masterLibrary, "master library")).contains("href=\"/ship/library/new\"", "Add an item");
        assertThat(PAGE_ACTIONS.matcher(masterLibrary).find()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private Ship ship(String name, String imo) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private String html(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The empty state of the page; an empty string when it has none. */
    private static String emptyState(String html, String page) {
        int start = html.indexOf("<div class=\"empty-state\">");
        if (start < 0) {
            return "";
        }
        int end = html.indexOf("</div>", html.indexOf("empty-state-text", start));
        int actions = html.indexOf("empty-state-actions", start);
        if (actions >= 0 && actions < end + 60) {
            end = html.indexOf("</div>", actions);
        }
        return html.substring(start, end);
    }

    /** The page without its Bootstrap modals. */
    private static String withoutModals(String html) {
        Matcher modal = Pattern.compile("(?s)<div class=\"modal fade\".*?</form>\\s*</div>\\s*</div>").matcher(html);
        return modal.replaceAll("");
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
