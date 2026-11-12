package com.nereonix.nereonet_mvp;

import static com.nereonix.nereonet_mvp.ListHtml.names;
import static com.nereonix.nereonet_mvp.ListHtml.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
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

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P19 on Certificates, Items, Requests, Announcements and the Audit log, and the same lists in the ship tabs: a
 * search, one filter, a sort and page 2, each from the URL alone (Crew and Office users: crew/CrewListFlowTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ListsFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final Instant START = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    ItemRequestRepository requestRepository;

    @Autowired
    AnnouncementRepository announcementRepository;

    @Autowired
    AuditService auditService;

    @MockitoBean
    MailService mailService;

    Ship alpha;
    Ship beta;
    User jane;
    User bob;

    @BeforeEach
    void ships() {
        alpha = ship("9074729", "MV Alpha");
        beta = ship("9176187", "MV Beta");
        jane = userService.createUser("jane@example.com", "Jane Cook", "secret123", Role.CHIEF_COOK, alpha, null);
        bob = userService.createUser("bob@example.com", "Bob Master", "secret123", Role.MASTER, beta, null);
    }

    // Certificates -------------------------------------------------------------------------------------------------

    // Jane (MV Alpha): "Cert 01" ... "Cert 27", due in 1 to 27 days. Bob (MV Beta): "Old Pass", expired, and
    // "Medical", due in 75 days. Default order: soonest expiry first.
    private void certificates() {
        IntStream.rangeClosed(1, 27).forEach(i -> certificate(jane, String.format("Cert %02d", i), i));
        certificate(bob, "Old Pass", -1);
        certificate(bob, "Medical", 75);
    }

    @Test
    void certificates_searchFilterSortAndPages() throws Exception {
        certificates();

        assertThat(names(html(get("/office/certificates").param("q", "medic")))).containsExactly("Medical");
        String expired = html(get("/office/certificates").param("status", "expired"));
        assertThat(names(expired)).containsExactly("Old Pass");
        assertThat(expired).contains("class=\"filter-chip\"", "Clear all");
        assertThat(names(html(get("/office/certificates").param("sort", "type").param("dir", "desc"))))
                .startsWith("Old Pass", "Medical", "Cert 27");

        String first = html(get("/office/certificates"));
        assertThat(names(first)).hasSize(25).startsWith("Old Pass", "Cert 01");
        assertThat(first).contains("1 to 25 of 29", "href=\"/office/certificates?page=2\"");
        String second = html(get("/office/certificates").param("page", "2"));
        assertThat(names(second)).containsExactly("Cert 25", "Cert 26", "Cert 27", "Medical");
        assertThat(second).contains("26 to 29 of 29");

        String none = html(get("/office/certificates").param("q", "nothing like it"));
        assertThat(names(none)).isEmpty();
        assertThat(none).contains("No results for these filters");
    }

    @Test
    void certificates_inTheShipTab() throws Exception {
        certificates();
        String tab = "/office/ships/" + beta.getId() + "/certificates/list";

        assertThat(names(html(get(tab)))).containsExactly("Old Pass", "Medical");
        assertThat(names(html(get(tab).param("q", "pass")))).containsExactly("Old Pass");
        assertThat(names(html(get(tab).param("status", "90")))).containsExactly("Medical");
        assertThat(names(html(get(tab).param("sort", "type")))).containsExactly("Medical", "Old Pass");

        String alphaTab = "/office/ships/" + alpha.getId() + "/certificates/list";
        assertThat(names(html(get(alphaTab).param("page", "2")))).containsExactly("Cert 26", "Cert 27");
    }

    // Items ----------------------------------------------------------------------------------------------------------

    @Test
    void items_searchFilterSortAndPages() throws Exception {
        // MV Alpha: "Item 01" ... "Item 26" (Dry), "Eggs" (Fresh), "Old flour" (Dry, inactive). MV Beta: "Coffee".
        IntStream.rangeClosed(1, 26).forEach(i -> item(alpha, String.format("Item %02d", i), "Dry", true));
        item(alpha, "Eggs", "Fresh", true);
        item(alpha, "Old flour", "Dry", false);
        item(beta, "Coffee", "Dry", true);
        String tab = "/office/ships/" + alpha.getId() + "/items";

        assertThat(names(html(get(tab).param("q", "EGG")))).containsExactly("Eggs");
        assertThat(names(html(get(tab).param("category", "Fresh")))).containsExactly("Eggs");
        assertThat(names(html(get(tab).param("status", "inactive")))).containsExactly("Old flour");
        assertThat(names(html(get(tab).param("sort", "name").param("dir", "desc"))))
                .startsWith("Old flour", "Item 26");

        String first = html(get(tab));
        assertThat(names(first)).hasSize(25).startsWith("Eggs", "Item 01").doesNotContain("Coffee");
        assertThat(first).contains("1 to 25 of 28");
        assertThat(names(html(get(tab).param("page", "2")))).containsExactly("Item 25", "Item 26", "Old flour");
        assertThat(html(get(tab).param("q", "caviar"))).contains("No results for these filters");
    }

    // Requests -------------------------------------------------------------------------------------------------------

    @Test
    void requests_searchFilterSortAndPages() throws Exception {
        // MV Alpha: 26 approved requests, one a day; MV Beta: one approved and exported, one still waiting.
        List<ItemRequest> alphaRequests = IntStream.range(0, 26)
                .mapToObj(i -> request(alpha, jane, RequestStatus.APPROVED, START.plus(i, ChronoUnit.DAYS), false))
                .toList();
        ItemRequest exported = request(beta, bob, RequestStatus.APPROVED, START.minus(1, ChronoUnit.DAYS), true);
        request(beta, bob, RequestStatus.PENDING, START, false);
        long firstId = alphaRequests.getFirst().getId();

        assertThat(names(html(get("/office/requests").param("q", "beta"))))
                .containsExactly(number(exported.getId()));
        assertThat(names(html(get("/office/requests").param("q", "#" + firstId)))).containsExactly(number(firstId));
        assertThat(names(html(get("/office/requests").param("notExported", "true"))))
                .hasSize(25).doesNotContain(number(exported.getId()));
        // Newest approval first; by number, lowest first when asked.
        assertThat(names(html(get("/office/requests"))).getFirst()).isEqualTo(number(alphaRequests.getLast().getId()));
        assertThat(names(html(get("/office/requests").param("sort", "number").param("dir", "asc"))).getFirst())
                .isEqualTo(number(firstId));

        String second = html(get("/office/requests").param("page", "2"));
        assertThat(names(second)).containsExactly(number(alphaRequests.get(0).getId()), number(exported.getId()));
        assertThat(second).contains("26 to 27 of 27");

        String tab = "/office/ships/" + beta.getId() + "/requests";
        assertThat(names(html(get(tab)))).containsExactly(number(exported.getId()));
        assertThat(html(get(tab).param("notExported", "true"))).contains("No results for these filters");
    }

    // Announcements --------------------------------------------------------------------------------------------------

    @Test
    void announcements_searchFilterSortAndPages() throws Exception {
        // "Notice 01" ... "Notice 26" to MV Alpha, one a day; "Fleet drill" to every ship, the oldest.
        IntStream.rangeClosed(1, 26).forEach(i ->
                announcement(String.format("Notice %02d", i), alpha, START.plus(i, ChronoUnit.DAYS)));
        announcement("Fleet drill", null, START);

        assertThat(names(html(get("/office/announcements").param("q", "drill")))).containsExactly("Fleet drill");
        assertThat(names(html(get("/office/announcements").param("shipId", beta.getId().toString()))))
                .containsExactly("Fleet drill");
        assertThat(names(html(get("/office/announcements").param("sort", "title"))))
                .startsWith("Fleet drill", "Notice 01");

        String first = html(get("/office/announcements"));
        assertThat(names(first)).hasSize(25).startsWith("Notice 26");
        assertThat(first).contains("1 to 25 of 27");
        assertThat(names(html(get("/office/announcements").param("page", "2"))))
                .containsExactly("Notice 01", "Fleet drill");

        String tab = "/office/ships/" + beta.getId() + "/announcements";
        assertThat(names(html(get(tab)))).containsExactly("Fleet drill");
        assertThat(html(get(tab).param("q", "notice"))).contains("No results for these filters");
    }

    // Audit log ------------------------------------------------------------------------------------------------------

    @Test
    void auditLog_searchFilterSortAndPages() throws Exception {
        // 26 entries for MV Alpha, one for MV Beta.
        IntStream.rangeClosed(1, 26).forEach(i ->
                auditService.record("ITEM_ADDED", alpha, String.format("p19 entry %02d", i), jane));
        auditService.record("SHIP_CREATED", beta, "p19 beta entry", bob);

        List<String> found = rows(html(get("/office/audit").param("q", "p19 beta")));
        assertThat(found).hasSize(1);
        assertThat(found.getFirst()).contains("p19 beta entry");
        assertThat(rows(html(get("/office/audit").param("shipId", beta.getId().toString()))))
                .anySatisfy(row -> assertThat(row).contains("p19 beta entry"))
                .noneSatisfy(row -> assertThat(row).contains("p19 entry"));
        // By who did it: Bob before Jane.
        List<String> byWho = rows(html(get("/office/audit").param("q", "p19").param("sort", "who")));
        assertThat(byWho.getFirst()).contains("p19 beta entry");

        String first = html(get("/office/audit").param("q", "p19"));
        assertThat(rows(first)).hasSize(25);
        assertThat(first).contains("1 to 25 of 27", "href=\"/office/audit?q=p19&amp;page=2\"");
        assertThat(rows(html(get("/office/audit").param("q", "p19").param("page", "2")))).hasSize(2);
        assertThat(html(get("/office/audit").param("q", "p19").param("from", TODAY.plusDays(1).toString())))
                .contains("No results for these filters");
    }

    // ----------------------------------------------------------------------------------------------------------------

    private String html(RequestBuilder request) throws Exception {
        return ListHtml.html(mockMvc, request);
    }

    private static String number(long id) {
        return "Request #" + id;
    }

    private Ship ship(String imoNumber, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imoNumber);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private void certificate(User user, String type, int days) {
        Certificate certificate = new Certificate();
        certificate.setUser(user);
        certificate.setType(type);
        certificate.setExpiresOn(TODAY.plusDays(days));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
    }

    private void item(Ship ship, String name, String category, boolean active) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit("kg");
        item.setCategory(category);
        item.setActive(active);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
    }

    private ItemRequest request(Ship ship, User by, RequestStatus status, Instant decidedAt, boolean exported) {
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(by);
        request.setStatus(status);
        request.setCreatedAt(decidedAt.minus(1, ChronoUnit.HOURS));
        if (status == RequestStatus.APPROVED) {
            request.setDecidedBy(by);
            request.setDecidedAt(decidedAt);
        }
        if (exported) {
            request.setExportedAt(decidedAt);
        }
        return requestRepository.save(request);
    }

    private void announcement(String title, Ship ship, Instant sentAt) {
        Announcement announcement = new Announcement();
        announcement.setTitle(title);
        announcement.setBody("Body of " + title);
        announcement.setShip(ship);
        announcement.setCreatedAt(sentAt);
        announcementRepository.save(announcement);
    }
}
