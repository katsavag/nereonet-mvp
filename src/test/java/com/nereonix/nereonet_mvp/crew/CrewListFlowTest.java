package com.nereonix.nereonet_mvp.crew;

import static com.nereonix.nereonet_mvp.ListHtml.names;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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

import com.nereonix.nereonet_mvp.ListHtml;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * P19 on Crew, its ship tab and Office users: search, filters, sort and pages, all in the URL and queried in the
 * database. Users and pending invitations are one list.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class CrewListFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @MockitoBean
    MailService mailService;

    Ship alpha;
    Ship beta;
    Invitation zed;

    // MV Alpha: 30 crew members "Crew 01" ... "Crew 30" (Crew 30 inactive); MV Beta: Anna Smith (Master) and the
    // pending invitation of Zed Invitee, expired.
    @BeforeEach
    void crew() {
        alpha = ship("9074729", "MV Alpha");
        beta = ship("9176187", "MV Beta");
        IntStream.rangeClosed(1, 30).forEach(i -> user(String.format("crew%02d@example.com", i),
                String.format("Crew %02d", i), Role.CREW_MEMBER, alpha, Rank.ABLE_SEAMAN, i != 30));
        user("anna.smith@example.com", "Anna Smith", Role.MASTER, beta, Rank.MASTER, true);
        zed = invitation("zed@example.com", "Zed Invitee", beta, -2);
    }

    @Test
    void searchFindsUsersAndInvitationsByNameOrEmailIgnoringCase() throws Exception {
        assertThat(names(html(get("/office/crew").param("q", "ANNA")))).containsExactly("Anna Smith");
        assertThat(names(html(get("/office/crew").param("q", "zed@example")))).containsExactly("Zed Invitee");
        // % and _ are plain characters, not wildcards.
        assertThat(names(html(get("/office/crew").param("q", "crew_0")))).isEmpty();

        String page = html(get("/office/crew").param("q", "anna"));
        // The search stays in the field and shows as a chip that removes it.
        assertThat(page).contains("type=\"search\" id=\"listSearch\" name=\"q\" class=\"form-control\" value=\"anna\"",
                "Search: anna", "href=\"/office/crew\" aria-label=\"Remove filter Search: anna\"");
        // The count after the title is the filtered total.
        assertThat(page).containsPattern("Crew</span>\\s*<span class=\"title-count\"><span aria-hidden=\"true\">·</span> "
                + "<span class=\"num\">1</span>");
    }

    @Test
    void filtersAreChipsAndCombineWithTheSearch() throws Exception {
        String beta = html(get("/office/crew").param("shipId", this.beta.getId().toString()));
        assertThat(names(beta)).containsExactly("Anna Smith", "Zed Invitee");

        String expired = html(get("/office/crew").param("shipId", this.beta.getId().toString()).param("status", "expired"));
        assertThat(names(expired)).containsExactly("Zed Invitee");
        assertThat(expired).contains("Vessel: MV Beta", "Status: Invitation expired",
                "href=\"/office/crew?status=expired\"", "href=\"/office/crew?shipId=" + this.beta.getId() + "\"",
                ">Clear all</a>");

        assertThat(names(html(get("/office/crew").param("status", "inactive")))).containsExactly("Crew 30");
        assertThat(names(html(get("/office/crew").param("status", "active").param("q", "crew 1"))))
                .containsExactly("Crew 10", "Crew 11", "Crew 12", "Crew 13", "Crew 14", "Crew 15", "Crew 16",
                        "Crew 17", "Crew 18", "Crew 19");
    }

    @Test
    void columnHeadersSortOnTheServer() throws Exception {
        String desc = html(get("/office/crew").param("sort", "name").param("dir", "desc"));
        assertThat(names(desc)).startsWith("Zed Invitee", "Crew 30", "Crew 29");
        // The sorted column says so; its link sorts the other way, on page 1.
        assertThat(desc).contains("aria-sort=\"descending\"", "href=\"/office/crew?sort=name&amp;dir=asc\"",
                "href=\"/office/crew?sort=email&amp;dir=asc\"");
        assertThat(html(get("/office/crew"))).contains("aria-sort=\"ascending\"",
                "href=\"/office/crew?sort=name&amp;dir=desc\"");

        // By ship (MV Alpha first), by rank (Master first), by status (Active, Invited, Invitation expired, Inactive).
        assertThat(names(html(get("/office/crew").param("sort", "ship").param("dir", "desc")))).startsWith("Zed Invitee", "Anna Smith");
        assertThat(names(html(get("/office/crew").param("sort", "rank")))).first().isEqualTo("Anna Smith");
        assertThat(names(html(get("/office/crew").param("sort", "status").param("dir", "desc"))))
                .startsWith("Crew 30", "Zed Invitee");
    }

    @Test
    void twentyFivePerPageWithFiftyAsAnOption() throws Exception {
        String first = html(get("/office/crew"));
        assertThat(names(first)).hasSize(25).startsWith("Anna Smith", "Crew 01");
        assertThat(first).contains("1 to 25 of 32", "href=\"/office/crew?page=2\"", "href=\"/office/crew?size=50\"",
                "aria-disabled=\"true\"").doesNotContain("href=\"\"");

        String second = html(get("/office/crew").param("page", "2"));
        assertThat(names(second)).hasSize(7).startsWith("Crew 25").endsWith("Zed Invitee");
        // Previous goes back to page 1 (no page parameter); Next is disabled and has no link.
        assertThat(second).contains("26 to 32 of 32", "href=\"/office/crew\"").doesNotContain("href=\"\"");

        assertThat(names(html(get("/office/crew").param("size", "50")))).hasSize(32);
        // A page past the end shows the last page.
        assertThat(names(html(get("/office/crew").param("page", "9")))).hasSize(7);
        // The same URL shows the same rows again.
        RequestBuilder sorted = get("/office/crew").param("sort", "status").param("page", "2");
        assertThat(names(html(sorted))).isEqualTo(names(html(sorted)));
        // Sort and size go along with the filter form; the page does not.
        String paged = html(get("/office/crew").param("sort", "email").param("size", "50").param("page", "2"));
        String filterForm = paged.substring(paged.indexOf("data-list-filter"), paged.indexOf("</form>", paged.indexOf("data-list-filter")));
        assertThat(filterForm).contains("<input type=\"hidden\" name=\"sort\" value=\"email\">",
                        "<input type=\"hidden\" name=\"size\" value=\"50\">")
                .doesNotContain("name=\"page\"");
    }

    @Test
    void noResultsIsNotTheFirstUseEmptyState() throws Exception {
        String none = html(get("/office/crew").param("q", "nobody"));
        assertThat(none).contains("No results for these filters", "href=\"/office/crew\">Clear all</a>")
                .doesNotContain("No crew members found.", "<table");
    }

    @Test
    void theCrewTabSearchesFiltersSortsAndPagesItsShip() throws Exception {
        String tab = "/office/ships/" + alpha.getId() + "/crew";
        String first = html(get(tab));
        assertThat(names(first)).hasSize(25);
        assertThat(first).contains("1 to 25 of 30", "action=\"" + tab + "\"", "name=\"status\"")
                .doesNotContain("id=\"filterShipId\"");
        assertThat(names(html(get(tab).param("page", "2")))).containsExactly("Crew 26", "Crew 27", "Crew 28",
                "Crew 29", "Crew 30");
        assertThat(names(html(get(tab).param("q", "crew 0").param("sort", "name").param("dir", "desc"))))
                .startsWith("Crew 09", "Crew 08");
        assertThat(names(html(get(tab).param("status", "inactive")))).containsExactly("Crew 30");
    }

    @Test
    void resendReturnsToTheListAsItWas() throws Exception {
        mockMvc.perform(post("/office/invitations/{id}/resend", zed.getId()).param("returnTo", "crew")
                        .param("shipId", beta.getId().toString()).param("status", "expired").param("q", "zed")
                        .param("sort", "email").param("dir", "desc").param("page", "2").param("size", "50")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew?q=zed&shipId=" + beta.getId()
                        + "&status=expired&sort=email&dir=desc&size=50&page=2"));
        // The row's forms carry that state.
        assertThat(html(get("/office/crew").param("q", "zed").param("sort", "email")))
                .contains("<input type=\"hidden\" name=\"returnTo\" value=\"crew\">",
                        "<input type=\"hidden\" name=\"q\" value=\"zed\">",
                        "<input type=\"hidden\" name=\"sort\" value=\"email\">");
    }

    @Test
    void officeUsersSearchFilterSortAndPage() throws Exception {
        IntStream.rangeClosed(1, 26).forEach(i -> user(String.format("office%02d@example.com", i),
                String.format("Office %02d", i), Role.OFFICE_USER, null, null, i != 26));
        invitation("new.office@example.com", "New Office", null, 3);
        // 26 + the seeded office user + one invitation.
        String first = html(get("/office/users"));
        assertThat(names(first)).hasSize(25);
        assertThat(first).contains("1 to 25 of 28");
        assertThat(names(html(get("/office/users").param("page", "2")))).hasSize(3);

        assertThat(names(html(get("/office/users").param("q", "new.office")))).containsExactly("New Office");
        assertThat(names(html(get("/office/users").param("status", "invited")))).containsExactly("New Office");
        assertThat(names(html(get("/office/users").param("status", "inactive")))).containsExactly("Office 26");
        assertThat(names(html(get("/office/users").param("sort", "name").param("dir", "desc")))).first()
                .isEqualTo("Office 26");
        // Crew are not office users.
        assertThat(names(html(get("/office/users").param("q", "anna")))).isEmpty();
    }

    private String html(RequestBuilder request) throws Exception {
        return ListHtml.html(mockMvc, request);
    }

    private Ship ship(String imoNumber, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imoNumber);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private User user(String email, String fullName, Role role, Ship ship, Rank rank, boolean enabled) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        user.setShip(ship);
        user.setRank(rank);
        user.setEnabled(enabled);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    // A pending invitation that expires in this many days (negative: expired). A null ship: an office invitation.
    private Invitation invitation(String email, String fullName, Ship ship, int days) {
        Invitation invitation = new Invitation();
        invitation.setEmail(email);
        invitation.setFullName(fullName);
        invitation.setRole(ship == null ? Role.OFFICE_USER : Role.CREW_MEMBER);
        invitation.setShip(ship);
        invitation.setRank(ship == null ? null : Rank.COOK);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(Instant.now().plus(days, ChronoUnit.DAYS));
        invitation.setCreatedAt(Instant.now());
        return invitationRepository.save(invitation);
    }
}
