package com.nereonix.nereonet_mvp.mycrew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.profile.CrewProfile;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.profile.PhotoImages;
import com.nereonix.nereonet_mvp.core.profile.ProfilePhotoStorage;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

import jakarta.persistence.EntityManagerFactory;

/**
 * P31, My crew: who is on board the signed-in person's ship now (active users with a crew role; never invited or
 * deactivated people, nor other ships), by department, rank and name, with "· you" on one's own row; a crewmate's
 * page with what they shared, or "<First name> hasn't added anything to their profile yet."; 404 (never 403) for
 * anyone not on board now, and for their photo; no email or join date anywhere in the HTML; a fixed number of
 * queries whatever the size of the crew; no script of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MyCrewFlowTest {

    private static final Pattern SCRIPT = Pattern.compile("<script src=\"/([^\"]+?)(-[0-9a-f]{32})?\\.js\"");
    private static final Pattern GROUP = Pattern.compile(
            "(?s)<h2 class=\"crew-group-title\" id=\"crewGroup\\d+\">\\s*<span>([^<]+)</span>.*?<span class=\"num\">(\\d+)</span>");
    private static final Pattern NAME = Pattern.compile("<span class=\"crewmate-name\">([^<]+)<");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CrewProfileRepository profileRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    ProfilePhotoStorage photoStorage;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    Ship ship;
    Ship otherShip;
    User master;
    User cook;
    User anna;
    User bob;
    User engineer;
    User noRank;
    User deactivated;
    User stranger;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        otherShip = ship("9123456", "MV Other");
        // Created out of order: the list sorts them.
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        bob = crew("bob@example.com", "Bob Able", Rank.ABLE_SEAMAN, ship);
        noRank = crew("norank@example.com", "Nora Rankless", null, ship);
        engineer = crew("engineer@example.com", "Nikos Pappas", Rank.CHIEF_ENGINEER, ship);
        anna = crew("anna@example.com", "Anna Testa", Rank.ABLE_SEAMAN, ship);
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        deactivated = crew("gone@example.com", "Gone Sailor", Rank.BOSUN, ship);
        deactivated.setEnabled(false);
        stranger = crew("other@example.com", "Other Crew", Rank.ABLE_SEAMAN, otherShip);
        // Invited to MV Demo, not registered yet: an invitation, not a user.
        Invitation invitation = new Invitation();
        invitation.setEmail("ivy@example.com");
        invitation.setFullName("Ivy Invited");
        invitation.setRole(Role.CREW_MEMBER);
        invitation.setRank(Rank.ORDINARY_SEAMAN);
        invitation.setShip(ship);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        invitation.setCreatedAt(Instant.now());
        invitationRepository.save(invitation);
        userRepository.flush();
    }

    @AfterEach
    void statisticsOff() {
        statistics().setStatisticsEnabled(false);
    }

    @Test
    void listsTheCrewOnBoardNowByDepartmentRankAndName() throws Exception {
        String html = page(get("/ship/crew"), cook);
        assertThat(html).contains("<title>My crew · Nereonet</title>", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>")
                .containsPattern("<span>Crew on board</span>\\s*<span class=\"title-count\"><span aria-hidden=\"true\">·</span> "
                        + "<span class=\"num\">6</span>");
        // Deck: Master before Able Seamen, the two Able Seamen by name; then Engine, Galley, and no rank last.
        assertThat(GROUP.matcher(html).results().map(m -> m.group(1) + " " + m.group(2)).toList())
                .containsExactly("Deck 3", "Engine 1", "Galley 1", "Rank not set 1");
        assertThat(names(html)).containsExactly("Ship Master", "Anna Testa", "Bob Able", "Nikos Pappas", "Demo Cook",
                "Nora Rankless");
        // Never deactivated, invited or other ships' people.
        assertThat(html).doesNotContain("Gone Sailor", "Ivy Invited", "Other Crew");
        // Each row is one link to the person, with the rank ("Rank not set" without one).
        assertThat(html).contains("<a class=\"crewmate-row\" href=\"/ship/crew/" + anna.getId() + "\">",
                "<span class=\"crewmate-rank\">Able Seaman</span>", "<span class=\"crewmate-rank\">Rank not set</span>");
        // The sidebar's My crew is active, and More in the tab bar.
        assertThat(html).contains("<a class=\"nav-link sidebar-link active\" href=\"/ship/crew\" aria-current=\"page\">",
                "<a class=\"tab-bar-link active\" href=\"/ship/more\" aria-current=\"page\">");
        // Every crew role has the page.
        page(get("/ship/crew"), master);
        page(get("/ship/crew"), anna);
    }

    @Test
    void theSignedInPersonsRowSaysYouAndShowsTheirPhoto() throws Exception {
        String key = photo(cook);
        String html = page(get("/ship/crew"), cook);
        assertThat(count(html, "class=\"crewmate-you\"")).isEqualTo(1);
        Matcher row = Pattern.compile("(?s)<a class=\"crewmate-row\" href=\"/ship/crew/" + cook.getId() + "\">.*?</a>")
                .matcher(html);
        assertThat(row.find()).isTrue();
        assertThat(row.group()).contains("<img src=\"/ship/crew/" + cook.getId() + "/photo/" + key + "/96\" alt=\"\"",
                        "Demo Cook", "loading=\"lazy\"")
                .containsPattern("<span\\s+class=\"crewmate-you\"> <span aria-hidden=\"true\">·</span> you</span>");
        // Without a photo: the initials.
        assertThat(html).containsPattern("<span class=\"person-avatar person-avatar-sm\" aria-hidden=\"true\">\\s*AT\\s*</span>");
        // As someone else, the cook's row is not "you".
        String asAnna = page(get("/ship/crew"), anna);
        assertThat(asAnna).containsPattern("<span class=\"crewmate-name\">Anna Testa<span\\s+class=\"crewmate-you\">");
    }

    @Test
    void aCrewmateWithAFullProfile() throws Exception {
        CrewProfile profile = new CrewProfile(engineer);
        profile.setLanguages(List.of("el", "en", "tl"));
        profile.setNationality("GR");
        profile.setBio("Twenty years at sea.\nAsk me about the purifiers.");
        profileRepository.save(profile);
        String key = photo(engineer);

        String html = page(get("/ship/crew/{id}", engineer.getId()), cook);
        assertThat(html).contains("<title>Nikos Pappas · Nereonet</title>",
                "<p class=\"page-subtitle\">Chief Engineer · Engine</p>",
                "<a href=\"/ship/crew\">My crew</a>",
                "<img class=\"photo-large\" src=\"/ship/crew/" + engineer.getId() + "/photo/" + key + "/512\"",
                "alt=\"Photo of Nikos Pappas\" width=\"512\" height=\"512\">",
                "<dd>Greek, English, Tagalog</dd>", "<dd>Greece</dd>",
                "<dd class=\"text-break text-pre-line\">Twenty years at sea.\nAsk me about the purifiers.</dd>",
                "href=\"/ship/crew\">", "<span>Back to My crew</span>")
                .doesNotContain("hasn&#39;t added anything", "hasn't added anything");
    }

    @Test
    void aCrewmateWithAnEmptyProfile() throws Exception {
        String html = page(get("/ship/crew/{id}", bob.getId()), cook);
        assertThat(html).contains("<p class=\"page-subtitle\">Able Seaman · Deck</p>")
                .containsPattern("Bob hasn(&#39;|')t added anything to their profile yet.")
                .doesNotContain("<dl class=\"static-fields", "photo-large");
        // Only some of it: just that, no "hasn't added" line.
        CrewProfile profile = new CrewProfile(bob);
        profile.setNationality("PH");
        profileRepository.save(profile);
        html = page(get("/ship/crew/{id}", bob.getId()), cook);
        assertThat(html).contains("<dt>Nationality</dt>", "<dd>Philippines</dd>").doesNotContain("<dt>Languages</dt>",
                "<dt>Bio</dt>", "added anything");
        // Without a rank.
        assertThat(page(get("/ship/crew/{id}", noRank.getId()), cook)).contains("<p class=\"page-subtitle\">Rank not set</p>");
    }

    @Test
    void anyoneNotOnBoardNowIs404AndSoIsTheirPhoto() throws Exception {
        String strangerKey = photo(stranger);
        String goneKey = photo(deactivated);
        User office = userService.createUser("office2@example.com", "Office Two", "secret123", Role.OFFICE_USER, null, null);

        for (Long id : List.of(stranger.getId(), deactivated.getId(), office.getId(), 999_999L)) {
            mockMvc.perform(get("/ship/crew/{id}", id).with(user(new CurrentUser(cook)))).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", stranger.getId(), strangerKey).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/512", deactivated.getId(), goneKey).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        // Same ship, but not their current photo, or not a size there is.
        String annaKey = photo(anna);
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", anna.getId(), strangerKey).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/200", anna.getId(), annaKey).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", anna.getId(), "not-a-key").with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        // A path in the key never gets through (the firewall rejects the encoded slash; the key must be a UUID anyway).
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", anna.getId(), "../../etc").with(user(new CurrentUser(cook))))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", anna.getId(), annaKey).with(user(new CurrentUser(cook))))
                .andExpect(status().isOk());
        // The stranger cannot see MV Demo's crew either.
        mockMvc.perform(get("/ship/crew/{id}", anna.getId()).with(user(new CurrentUser(stranger)))).andExpect(status().isNotFound());
        assertThat(page(get("/ship/crew"), stranger)).doesNotContain("Anna Testa");
    }

    @Test
    void theHtmlNeverHoldsEmailAddressesOrJoinDates() throws Exception {
        CrewProfile profile = new CrewProfile(anna);
        profile.setBio("Hello.");
        profileRepository.save(profile);
        List<String> pages = List.of(page(get("/ship/crew"), cook), page(get("/ship/crew/{id}", anna.getId()), cook),
                page(get("/ship/crew/{id}", cook.getId()), cook));
        for (String html : pages) {
            assertThat(EMAIL.matcher(html).find()).as("an email address in the HTML").isFalse();
            for (User u : List.of(cook, anna, master)) {
                String iso = u.getCreatedAt().toString().substring(0, 10);
                assertThat(html).doesNotContain(iso, u.getCreatedAt().toString());
            }
            assertThat(html).doesNotContain("Certificates", "Expires", "<time");
        }
    }

    @Test
    void theOnlyOneOnBoard() throws Exception {
        String html = page(get("/ship/crew"), stranger);
        assertThat(html).containsPattern("You(&#39;|')re the only one on board in Nereonet so far.")
                .containsPattern("<span class=\"num\">1</span>")
                .doesNotContain("class=\"crewmate-row\"");
        // With a crewmate, no such line.
        assertThat(page(get("/ship/crew"), cook)).doesNotContain("only one on board");
    }

    @Test
    void aFixedNumberOfQueriesWhateverTheSizeOfTheCrew() throws Exception {
        long small = queries(cook);
        for (int i = 0; i < 12; i++) {
            User u = crew("extra" + i + "@example.com", "Extra Sailor " + i, Rank.ORDINARY_SEAMAN, ship);
            CrewProfile profile = new CrewProfile(u);
            profile.setLanguages(List.of("en"));
            profile.setBio("Bio " + i);
            profileRepository.save(profile);
        }
        userRepository.flush();
        long large = queries(cook);
        assertThat(names(page(get("/ship/crew"), cook))).hasSize(18);
        assertThat(large).isEqualTo(small);
    }

    @Test
    void myCrewPagesLoadNoScriptOfTheirOwn() throws Exception {
        List<String> shared = scripts(page(get("/ship/library"), cook));
        assertThat(scripts(page(get("/ship/crew"), cook))).isEqualTo(shared).doesNotContain("js/profile");
        assertThat(scripts(page(get("/ship/crew/{id}", anna.getId()), cook))).isEqualTo(shared);
        assertThat(page(get("/ship/crew"), cook).replaceAll("(?s)<script src=[^>]*></script>", ""))
                .doesNotContain("<script");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    // Statements prepared for one My crew page.
    private long queries(User u) throws Exception {
        Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        page(get("/ship/crew"), u);
        return statistics.getPrepareStatementCount();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // A stored photo for the user, as an upload would leave it.
    private String photo(User u) throws Exception {
        String key = photoStorage.save(PhotoImages.process(jpeg()));
        CrewProfile profile = profileRepository.findByUserId(u.getId()).orElseGet(() -> new CrewProfile(u));
        profile.setPhotoKey(key);
        profileRepository.save(profile);
        return key;
    }


    private static byte[] jpeg() throws java.io.IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(200, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        Role role = rank == null ? Role.CREW_MEMBER : rank.role();
        return userService.createUser(email, name, "secret123", role, s, rank);
    }

    private String page(MockHttpServletRequestBuilder request, User u) throws Exception {
        return mockMvc.perform(request.with(user(new CurrentUser(u)))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> names(String html) {
        return NAME.matcher(html).results().map(m -> m.group(1)).toList();
    }

    private static List<String> scripts(String html) {
        return SCRIPT.matcher(html).results().map(m -> m.group(1)).toList();
    }

    private static int count(String html, String needle) {
        int n = 0;
        for (int i = html.indexOf(needle); i >= 0; i = html.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }
}
