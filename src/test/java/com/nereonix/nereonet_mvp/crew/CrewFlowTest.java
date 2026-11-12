package com.nereonix.nereonet_mvp.crew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class CrewFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    AuditService auditService;

    @Autowired
    AuditRepository auditRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void officeUserAddsCrewMember() throws Exception {
        Ship ship = ship("9074729", "MV Demo");

        mockMvc.perform(post("/office/crew/new")
                        .param("fullName", "Jane Cook")
                        .param("email", "jane.cook@example.com")
                        .param("rank", "CHIEF_COOK")
                        .param("shipId", ship.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        List<Invitation> invitations = invitationRepository.findAll();
        assertThat(invitations).hasSize(1);
        Invitation invitation = invitations.getFirst();
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.PENDING);
        assertThat(invitation.getRole()).isEqualTo(Role.CHIEF_COOK);
        assertThat(invitation.getShip().getId()).isEqualTo(ship.getId());
        assertThat(invitation.getRank()).isEqualTo(Rank.CHIEF_COOK);
        verify(mailService, times(1))
                .send(eq("jane.cook@example.com"), anyString(), contains("/register?token="), anyString());

        mockMvc.perform(get("/office/crew"))
                .andExpect(content().string(Matchers.containsString("jane.cook@example.com")));
    }

    @Test
    void officeUserMovesCrewMemberToAnotherShip() throws Exception {
        Ship from = ship("9074729", "MV Demo");
        Ship to = ship("9176187", "MV Aegean Spirit");
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, from,
                Rank.DECK_CADET);

        mockMvc.perform(post("/office/crew/" + crew.getId() + "/edit")
                        .param("fullName", "Crew Member")
                        .param("rank", "THIRD_OFFICER")
                        .param("shipId", to.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        User saved = userRepository.findById(crew.getId()).orElseThrow();
        assertThat(saved.getShip().getId()).isEqualTo(to.getId());
        assertThat(saved.getRank()).isEqualTo(Rank.THIRD_OFFICER);

        mockMvc.perform(get("/office/crew").param("shipId", to.getId().toString()))
                .andExpect(content().string(Matchers.containsString("crew@example.com")));
    }

    @Test
    void officeUserDeletesDeactivatedCrewMember() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        mockMvc.perform(post("/office/crew/new")
                        .param("fullName", "Crew Member")
                        .param("email", "crew@example.com")
                        .param("rank", "ABLE_SEAMAN")
                        .param("shipId", ship.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                null);
        auditService.record("USER_REGISTERED", ship, "crew@example.com", crew);
        mockMvc.perform(post("/office/users/" + crew.getId() + "/deactivate").with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        mockMvc.perform(post("/office/users/" + crew.getId() + "/delete").with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        assertThat(userRepository.findById(crew.getId())).isEmpty();
        assertThat(invitationRepository.findAll()).isEmpty();
        AuditEntry registered = auditRepository.findAll().stream()
                .filter(e -> e.getAction().equals("USER_REGISTERED")).findFirst().orElseThrow();
        assertThat(registered.getActor()).isNull();
        assertThat(registered.isActorDeleted()).isTrue();
    }

    // P17: the Status filter, a query parameter (/office/crew?status=expired), with the ship filter.
    @Test
    void crewListFiltersByStatus() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        Ship other = ship("9176187", "MV Other");
        userService.createUser("anna@example.com", "Anna Active", "secret123", Role.CREW_MEMBER, demo, null);
        User inactive = userService.createUser("ivan@example.com", "Ivan Inactive", "secret123", Role.CREW_MEMBER,
                demo, null);
        inactive.setEnabled(false);
        invitation("ingrid@example.com", "Ingrid Invited", demo, 3);
        invitation("erik@example.com", "Erik Expired", demo, -2);
        invitation("olaf@example.com", "Olaf Elsewhere", other, -2);

        assertThat(html(get("/office/crew").param("status", "active"))).contains("Anna Active")
                .doesNotContain("Ivan Inactive", "Ingrid Invited", "Erik Expired");
        assertThat(html(get("/office/crew").param("status", "inactive"))).contains("Ivan Inactive")
                .doesNotContain("Anna Active", "Ingrid Invited", "Erik Expired");
        assertThat(html(get("/office/crew").param("status", "invited")))
                .contains("Ingrid Invited", ">Invited</span>", "expires in 3 days")
                .doesNotContain("Anna Active", "Ivan Inactive", "Erik Expired");
        assertThat(html(get("/office/crew").param("status", "expired")))
                .contains("Erik Expired", "Olaf Elsewhere", ">Invitation expired</span>", "2 days ago",
                        "<option value=\"expired\" selected=\"selected\">Invitation expired</option>")
                .doesNotContain("Anna Active", "Ivan Inactive", "Ingrid Invited");
        // With the ship filter.
        assertThat(html(get("/office/crew").param("status", "expired").param("shipId", demo.getId().toString())))
                .contains("Erik Expired").doesNotContain("Olaf Elsewhere");
        // No or an unknown status: everyone.
        for (String status : new String[] {"", "unknown"}) {
            assertThat(html(get("/office/crew").param("status", status)))
                    .contains("Anna Active", "Ivan Inactive", "Ingrid Invited", "Erik Expired", "Olaf Elsewhere",
                            "<option value=\"\">All statuses</option>")
                    .doesNotContainPattern("<option value=\"(active|invited|expired|inactive)\" selected");
        }
        // The filter is labelled and offers the four statuses.
        assertThat(html(get("/office/crew"))).contains("<label for=\"filterStatus\"", "name=\"status\"",
                ">Active</option>", ">Invited</option>", ">Invitation expired</option>", ">Inactive</option>");
        assertThat(html(get("/office/crew").param("lang", "el"))).contains(">Όλες οι καταστάσεις</option>",
                ">Η πρόσκληση έληξε</option>");
    }

    // P17: Resend and Cancel invitation on the Crew list; both return to it with its filters.
    @Test
    void crewInvitationIsResentAndCancelledFromTheCrewList() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        Invitation expired = invitation("erik@example.com", "Erik Expired", demo, -2);
        String shipId = demo.getId().toString();
        String back = "/office/crew?shipId=" + shipId + "&status=expired";

        String list = html(get("/office/crew").param("shipId", shipId).param("status", "expired"));
        assertThat(list).contains("action=\"/office/invitations/" + expired.getId() + "/resend\"",
                "<input type=\"hidden\" name=\"returnTo\" value=\"crew\">",
                "<input type=\"hidden\" name=\"shipId\" value=\"" + shipId + "\">",
                "<input type=\"hidden\" name=\"status\" value=\"expired\">",
                ">Resend</button>", ">Cancel invitation</a>",
                "href=\"/office/invitations/" + expired.getId() + "/cancel?returnTo=crew&amp;shipId=" + shipId
                        + "&amp;status=expired\"",
                "data-confirm-tone=\"danger\" data-confirm-dismiss=\"Keep invitation\"");

        // One click resends an expired invitation; back to the filtered list.
        mockMvc.perform(post("/office/invitations/{id}/resend", expired.getId()).param("returnTo", "crew")
                        .param("shipId", shipId).param("status", "expired").with(csrf()))
                .andExpect(redirectedUrl(back));
        assertThat(invitationRepository.findById(expired.getId()).orElseThrow().getDisplayStatus())
                .isEqualTo(Invitation.DisplayStatus.PENDING);
        verify(mailService, times(1)).send(eq("erik@example.com"), anyString(), contains("/register?token="), anyString());

        // Cancel: the confirmation page (no script) keeps the filters; confirming returns to the list.
        assertThat(html(get("/office/invitations/{id}/cancel", expired.getId()).param("returnTo", "crew")
                .param("shipId", shipId).param("status", "expired")))
                .contains("<input type=\"hidden\" name=\"status\" value=\"expired\">",
                        "href=\"/office/crew?shipId=" + shipId + "&amp;status=expired\">Keep invitation</a>");
        mockMvc.perform(post("/office/invitations/{id}/cancel", expired.getId()).param("returnTo", "crew")
                        .param("shipId", shipId).param("status", "expired").with(csrf()))
                .andExpect(redirectedUrl(back));
        assertThat(invitationRepository.findById(expired.getId()).orElseThrow().getStatus())
                .isEqualTo(Invitation.Status.CANCELLED);
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().startsWith("INVITATION_"))
                .extracting(AuditEntry::getAction).containsExactlyInAnyOrder("INVITATION_RESENT", "INVITATION_CANCELLED");
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().startsWith("INVITATION_"))
                .allSatisfy(e -> assertThat(e.getShip().getId()).isEqualTo(demo.getId()));
    }

    // P18: the office picks a rank; the role is the rank's. A role sent with the form is ignored.
    @Test
    void theRankSetsTheRole() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        String[][] cases = {
                {"MASTER", "master@example.com"}, {"CHIEF_COOK", "cook@example.com"},
                {"CHIEF_ENGINEER", "chief.engineer@example.com"}, {"BOSUN", "bosun@example.com"},
                {"SECOND_ENGINEER", "second.engineer@example.com"}, {"STEWARD", "steward@example.com"}};
        for (String[] c : cases) {
            mockMvc.perform(post("/office/crew/new")
                            .param("fullName", "Crew " + c[0]).param("email", c[1]).param("rank", c[0])
                            // Not a field of the form any more: never bound.
                            .param("role", "MASTER")
                            .param("shipId", ship.getId().toString()).with(csrf()))
                    .andExpect(redirectedUrl("/office/crew"));
        }
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail, Invitation::getRole)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("master@example.com", Role.MASTER),
                        org.assertj.core.groups.Tuple.tuple("cook@example.com", Role.CHIEF_COOK),
                        // Chief Engineer has no role of its own.
                        org.assertj.core.groups.Tuple.tuple("chief.engineer@example.com", Role.CREW_MEMBER),
                        org.assertj.core.groups.Tuple.tuple("bosun@example.com", Role.CREW_MEMBER),
                        org.assertj.core.groups.Tuple.tuple("second.engineer@example.com", Role.CREW_MEMBER),
                        org.assertj.core.groups.Tuple.tuple("steward@example.com", Role.CREW_MEMBER));
        assertThat(Rank.values()).allSatisfy(rank -> assertThat(rank.role()).isEqualTo(switch (rank) {
            case MASTER -> Role.MASTER;
            case CHIEF_COOK -> Role.CHIEF_COOK;
            default -> Role.CREW_MEMBER;
        }));

        // Editing: the new rank's role, also when it takes one away.
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.SECOND_ENGINEER);
        edit(crew, "CHIEF_ENGINEER", ship).andExpect(redirectedUrl("/office/crew"));
        assertThat(userRepository.findById(crew.getId()).orElseThrow().getRole()).isEqualTo(Role.CREW_MEMBER);
        edit(crew, "CHIEF_COOK", ship).andExpect(redirectedUrl("/office/crew"));
        assertThat(userRepository.findById(crew.getId()).orElseThrow().getRole()).isEqualTo(Role.CHIEF_COOK);
        edit(crew, "FITTER", ship).andExpect(redirectedUrl("/office/crew"));
        assertThat(userRepository.findById(crew.getId()).orElseThrow().getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("USER_UPDATED"))
                .extracting(AuditEntry::getDetails)
                .containsExactly("crew@example.com: CREW_MEMBER, CHIEF_ENGINEER",
                        "crew@example.com: CHIEF_COOK, CHIEF_COOK, role CREW_MEMBER -> CHIEF_COOK",
                        "crew@example.com: CREW_MEMBER, FITTER, role CHIEF_COOK -> CREW_MEMBER");
    }

    @Test
    void theRankIsRequired() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        mockMvc.perform(post("/office/crew/new").param("fullName", "No Rank").param("email", "norank@example.com")
                        .param("role", "CREW_MEMBER").param("shipId", ship.getId().toString()).with(csrf()))
                .andExpect(model().attributeHasFieldErrorCode("addForm", "rank", "NotNull"))
                .andExpect(content().string(Matchers.containsString("Choose a rank.")));
        assertThat(invitationRepository.findAll()).isEmpty();

        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.BOSUN);
        edit(crew, "", ship).andExpect(model().attributeHasFieldErrorCode("form", "rank", "NotNull"));
        assertThat(userRepository.findById(crew.getId()).orElseThrow().getRank()).isEqualTo(Rank.BOSUN);
    }

    // One active Master and one active Chief Cook per ship; since P18 the error is on the rank field.
    @Test
    void oneMasterPerShipIsReportedOnTheRankField() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        userService.createUser("master@example.com", "Mara Master", "secret123", Role.MASTER, ship, Rank.MASTER);
        userService.createUser("cook@example.com", "Cora Cook", "secret123", Role.CHIEF_COOK, ship, Rank.CHIEF_COOK);

        for (String rank : new String[] {"MASTER", "CHIEF_COOK"}) {
            String html = mockMvc.perform(post("/office/crew/new").param("fullName", "Second One")
                            .param("email", "second@example.com").param("rank", rank)
                            .param("shipId", ship.getId().toString()).with(csrf()))
                    .andExpect(model().attributeHasFieldErrorCode("addForm", "rank", "user.error.shipRoleTaken"))
                    .andExpect(model().attributeErrorCount("addForm", 1))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).as(rank)
                    .containsPattern("<select class=\"form-select is-invalid\"[^>]*id=\"rank\" name=\"rank\"")
                    .contains("This ship already has an active crew member with this access.");
        }
        assertThat(invitationRepository.findAll()).isEmpty();

        // Editing a crew member to Master: the same error, on the same field.
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.CHIEF_OFFICER);
        edit(crew, "MASTER", ship)
                .andExpect(model().attributeHasFieldErrorCode("form", "rank", "user.error.shipRoleTaken"));
        assertThat(userRepository.findById(crew.getId()).orElseThrow().getRole()).isEqualTo(Role.CREW_MEMBER);
        // Another ship has no Master yet.
        Ship other = ship("9176187", "MV Other");
        edit(crew, "MASTER", other).andExpect(redirectedUrl("/office/crew"));
        assertThat(userRepository.findById(crew.getId()).orElseThrow().getRole()).isEqualTo(Role.MASTER);
    }

    // P18: no role picker; the rank picker is grouped by department and says what access the rank gives.
    @Test
    void noFormLetsTheOfficeChooseARole() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        User master = userService.createUser("master@example.com", "Mara Master", "secret123", Role.MASTER, ship,
                Rank.MASTER);
        for (String page : List.of("/office/crew", "/office/ships/" + ship.getId() + "/crew",
                "/office/crew/" + master.getId() + "/edit")) {
            String html = html(get(page));
            assertThat(html).as(page).doesNotContain("name=\"role\"", "Choose a role", ">Role<")
                    // Required, described by the access line, in either attribute order.
                    .containsPattern("<select class=\"form-select\" required(?=[^>]*aria-describedby=\"rankAccess)"
                            + "(?=[^>]*data-access-hint=\"rankAccess\")[^>]*id=\"rank\" name=\"rank\">")
                    .contains("<optgroup label=\"Deck\">", "<optgroup label=\"Engine\">", "<optgroup label=\"Catering\">")
                    .contains("<option value=\"CHIEF_ENGINEER\" data-role=\"CREW_MEMBER\">Chief Engineer</option>",
                            "<option value=\"FITTER\" data-role=\"CREW_MEMBER\">Fitter</option>")
                    .contains("data-access-master=\"Access: Master · can publish library items and see acknowledgements\"",
                            "data-access-crew_member=\"Access: Crew member · can read the library and acknowledge required items\"")
                    .doesNotContain("chief_engineer");
            // In the Rank order: Master first in Deck, Chief Engineer first in Engine, Steward last in Catering.
            assertThat(html.indexOf("value=\"MASTER\"")).isLessThan(html.indexOf("value=\"CHIEF_OFFICER\""));
            assertThat(html.indexOf("<optgroup label=\"Engine\">")).isLessThan(html.indexOf("value=\"CHIEF_ENGINEER\""));
            assertThat(html.indexOf("value=\"COOK\"")).isLessThan(html.indexOf("value=\"STEWARD\""));
        }
        // Before a rank is chosen, and for the saved rank.
        assertThat(html(get("/office/crew")))
                .containsPattern("aria-live=\"polite\"\\s+data-access-none=\"Access: set by the rank\"")
                .contains(">Access: set by the rank</p>");
        assertThat(html(get("/office/crew/" + master.getId() + "/edit")))
                .contains(">Access: Master · can publish library items and see acknowledgements</p>")
                .doesNotContain("Access now:");
        assertThat(html(get("/office/crew").param("lang", "el")))
                .contains("<optgroup label=\"Κατάστρωμα\">", "<optgroup label=\"Τροφοδοσία\">",
                        ">Πρόσβαση: ορίζεται από τον βαθμό</p>");
    }

    // P18: the Role column is gone; Master and Chief Cook have an access badge after the rank.
    @Test
    void listsShowTheRankAndAnAccessBadgeForTheRolesWithTheirOwnAccess() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        userService.createUser("master@example.com", "Mara Master", "secret123", Role.MASTER, ship, Rank.MASTER);
        userService.createUser("chief@example.com", "Chris Chief", "secret123", Role.CREW_MEMBER, ship,
                Rank.CHIEF_ENGINEER);
        userService.createUser("bosun@example.com", "Bo Bosun", "secret123", Role.CREW_MEMBER, ship, Rank.BOSUN);
        mockMvc.perform(post("/office/crew/new").param("fullName", "Cora Cook").param("email", "cook@example.com")
                .param("rank", "CHIEF_COOK").param("shipId", ship.getId().toString()).with(csrf()));

        for (String page : List.of("/office/crew", "/office/ships/" + ship.getId() + "/crew")) {
            String html = html(get(page));
            assertThat(html).as(page).doesNotContain("<th scope=\"col\">Role</th>")
                    .contains(">Master access</span>", ">Chief cook access</span>", "<span>Bosun</span>",
                            "<span>Chief Engineer</span>");
            assertThat(html.split("access</span>", -1)).as(page).hasSize(3);
        }
    }

    // P18: users from before whose rank and role disagree, or who have no rank, keep their role and are listed in a
    // notice on the Crew list; saving the edit form applies the rank's role.
    @Test
    void usersWhoseRankDoesNotSetTheirRoleAreListedForReview() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        User noRank = userService.createUser("nora@example.com", "Nora Norank", "secret123", Role.MASTER, ship, null);
        User mismatch = userService.createUser("milo@example.com", "Milo Mismatch", "secret123", Role.CHIEF_COOK, ship,
                Rank.BOSUN);
        User fine = userService.createUser("fiona@example.com", "Fiona Fine", "secret123", Role.CREW_MEMBER, ship,
                Rank.BOSUN);

        String list = html(get("/office/crew"));
        String notice = list.substring(list.indexOf("<section class=\"alert alert-warning rank-review\""),
                list.indexOf("</section>"));
        assertThat(notice).contains("Rank needs review", ">2</span>",
                "href=\"/office/crew/" + noRank.getId() + "/edit\">Nora Norank</a>",
                "Rank: not set · access now: Master",
                "href=\"/office/crew/" + mismatch.getId() + "/edit\">Milo Mismatch</a>",
                "Rank: Bosun · access now: Chief cook")
                .doesNotContain("Fiona Fine");
        // They keep their role until saved.
        assertThat(userRepository.findById(noRank.getId()).orElseThrow().getRole()).isEqualTo(Role.MASTER);
        assertThat(html(get("/office/crew").param("lang", "el"))).contains("Ο βαθμός θέλει έλεγχο");
        // Filtered to another ship or to invitations: not listed.
        Ship other = ship("9176187", "MV Other");
        assertThat(html(get("/office/crew").param("shipId", other.getId().toString()))).doesNotContain("rank-review");
        assertThat(html(get("/office/crew").param("status", "invited"))).doesNotContain("rank-review");

        // The edit form names the access they have now; no rank is chosen for Nora.
        assertThat(html(get("/office/crew/" + noRank.getId() + "/edit")))
                .contains("aria-describedby=\"rankAccess rankCurrentAccess\"",
                        "<p class=\"info-note\" id=\"rankCurrentAccess\">",
                        "Access now: Master. Saving applies the access of the rank above.",
                        ">Access: set by the rank</p>");
        assertThat(html(get("/office/crew/" + fine.getId() + "/edit"))).doesNotContain("rankCurrentAccess");

        // Saving applies the mapping.
        edit(noRank, "MASTER", ship).andExpect(redirectedUrl("/office/crew"));
        edit(mismatch, "BOSUN", ship).andExpect(redirectedUrl("/office/crew"));
        assertThat(userRepository.findById(noRank.getId()).orElseThrow().getRole()).isEqualTo(Role.MASTER);
        assertThat(userRepository.findById(mismatch.getId()).orElseThrow().getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(html(get("/office/crew"))).doesNotContain("rank-review");
    }

    private org.springframework.test.web.servlet.ResultActions edit(User user, String rank, Ship ship)
            throws Exception {
        return mockMvc.perform(post("/office/crew/" + user.getId() + "/edit")
                .param("fullName", user.getFullName()).param("rank", rank)
                .param("shipId", ship.getId().toString()).with(csrf()));
    }

    // A PENDING crew invitation that expires in this many days (negative: expired that many days ago).
    private Invitation invitation(String email, String fullName, Ship ship, int days) {
        Invitation invitation = new Invitation();
        invitation.setEmail(email);
        invitation.setFullName(fullName);
        invitation.setRole(Role.CREW_MEMBER);
        invitation.setShip(ship);
        invitation.setTokenId(java.util.UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        // Midday, so the hint's whole UTC days do not depend on the time the test runs.
        invitation.setExpiresAt(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(days)
                .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        invitation.setCreatedAt(Instant.now());
        return invitationRepository.save(invitation);
    }

    private String html(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Ship ship(String imoNumber, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imoNumber);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}
