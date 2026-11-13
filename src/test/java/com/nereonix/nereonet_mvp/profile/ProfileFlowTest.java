package com.nereonix.nereonet_mvp.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.profile.CrewProfile;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.profile.ProfilePhotoStorage;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P31, My profile: each of the four fields (languages, nationality, bio, photo) saved from its own page and back on My
 * profile with "Saved."; the limits (five languages, 280 characters, 5 MB, JPEG or PNG); the photo re-encoded without
 * any metadata and served only behind sign-in with a cache header; Delete my profile photo; every change recorded in
 * the audit log; only ever the signed-in person's own profile; the pages work without JavaScript (plain links and
 * forms); the office sees the four fields read only on the crew member's page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfileFlowTest {

    private static final Pattern SCRIPT = Pattern.compile("<script src=\"/([^\"]+?)(-[0-9a-f]{32})?\\.js\"");
    private static final Pattern PHOTO_URL = Pattern.compile("/ship/crew/(\\d+)/photo/([0-9a-f-]{36})/512");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    CrewProfileRepository profileRepository;

    @Autowired
    ProfilePhotoStorage photoStorage;

    @Autowired
    AuditRepository auditRepository;

    Ship ship;
    User master;
    User cook;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Role.MASTER, ship, Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Role.CHIEF_COOK, ship, Rank.CHIEF_COOK);
    }

    @Test
    void myProfileShowsTheOfficesDetailsAndTheFourFieldsAsNotGiven() throws Exception {
        String html = page(get("/ship/profile"), cook);
        assertThat(html).contains("<title>My profile · Nereonet</title>", ">Demo Cook</span>",
                "<p class=\"page-subtitle\">Chief Cook · MV Demo</p>",
                "Your crewmates and the office can see what you fill in.",
                "href=\"/ship/profile/languages\"", "href=\"/ship/profile/nationality\"",
                "href=\"/ship/profile/bio\"", "href=\"/ship/profile/photo\"")
                // No photo: the initials, in the header and (P43) in the sidebar's user block, the one shared fragment.
                .containsPattern("<span class=\"person-avatar person-avatar-lg\" aria-hidden=\"true\">\\s*DC\\s*</span>")
                .doesNotContain("cook@example.com");
        assertThat(sidebarUser(html)).containsPattern("<span class=\"person-avatar person-avatar-sm\" aria-hidden=\"true\">\\s*DC\\s*</span>")
                .doesNotContain("class=\"avatar\"");
        assertThat(count(html, "class=\"profile-row-value is-empty\">Not given<")).isEqualTo(4);
        // Name, rank and ship are never a field here: nothing to edit them with.
        assertThat(main(html)).doesNotContain("<input", "<select", "<textarea");
    }

    @Test
    void languagesAreAddedOrderedAndOnlyStoredOnSave() throws Exception {
        String html = page(get("/ship/profile/languages"), cook);
        assertThat(html).contains("No languages yet.", "0 of 5", "id=\"languageSearch\" name=\"q\"",
                "href=\"/ship/profile/languages?languages=el#yourLanguages\"");

        // The working list is in the URL: Add, Move up, Remove are links; the search carries the list along.
        html = page(get("/ship/profile/languages").param("languages", "en,el").param("q", "gree"), cook);
        assertThat(languageNames(html)).containsExactly("English", "Greek");
        assertThat(html).contains("2 of 5", "<input type=\"hidden\" name=\"languages\" value=\"en,el\">",
                "href=\"/ship/profile/languages?languages=el,en&amp;q=gree#yourLanguages\"",  // Greek up
                "href=\"/ship/profile/languages?languages=el&amp;q=gree#yourLanguages\"",     // English removed
                "Languages shown: 1")
                // Greek is chosen: "Added", no Add.
                .doesNotContain("languages=en,el,el");
        assertThat(profileRepository.findByUserId(cook.getId())).as("nothing stored before Save").isEmpty();

        mockMvc.perform(post("/ship/profile/languages").param("languages", "en,el").with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/profile"))
                .andExpect(flash().attribute("successMessage", "profile.saved"));
        assertThat(profile(cook).getLanguages()).containsExactly("en", "el");
        assertThat(page(get("/ship/profile"), cook)).contains("<span class=\"profile-row-value\">English, Greek</span>");
        assertThat(audit("PROFILE_LANGUAGES_UPDATED")).extracting(AuditEntry::getDetails).containsExactly("en, el");

        // The page opens with the saved list, in its order.
        assertThat(languageNames(page(get("/ship/profile/languages"), cook))).containsExactly("English", "Greek");
    }

    @Test
    void aSixthLanguageCannotBeAdded() throws Exception {
        String html = page(get("/ship/profile/languages").param("languages", "en,el,fr,de,es"), cook);
        assertThat(html).contains("5 of 5", "You have 5 languages, the most you can list. Remove one to add another.",
                "id=\"languagesFull\"")
                .containsPattern("<a class=\"btn btn-outline-secondary btn-sm disabled\"\\s+aria-disabled=\"true\" "
                        + "aria-describedby=\"languagesFull\">Add<span class=\"visually-hidden\"\\s*>: Italian</span></a>")
                .doesNotContain("languages=en,el,fr,de,es,");

        mockMvc.perform(post("/ship/profile/languages").param("languages", "en,el,fr,de,es,it")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("You can list up to 5 languages."));
        assertThat(profileRepository.findByUserId(cook.getId())).isEmpty();
        // A code that is not a language is refused too.
        mockMvc.perform(post("/ship/profile/languages").param("languages", "en,xx")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isOk());
        assertThat(profileRepository.findByUserId(cook.getId())).isEmpty();
    }

    @Test
    void nationalityIsOneCountryOrNotGiven() throws Exception {
        String html = page(get("/ship/profile/nationality"), cook);
        assertThat(html).containsPattern("<select [^>]*name=\"nationality\"[^>]*>\\s*"
                + "<option value=\"\">Not given</option>\\s*<option value=\"AF\">Afghanistan \\(AF\\)</option>");

        save("/ship/profile/nationality", "nationality", "GR", cook);
        assertThat(profile(cook).getNationality()).isEqualTo("GR");
        assertThat(page(get("/ship/profile"), cook)).containsPattern("<span class=\"profile-row-value\"><span class=\"country\"><img class=\"flag-icon\" src=\"/vendor/flags/gr-[0-9a-f]{32}\\.svg\"[^>]*><span>Greece</span></span></span>");
        assertThat(page(get("/ship/profile/nationality"), cook)).contains("<option value=\"GR\" selected=\"selected\">Greece (GR)</option>");

        mockMvc.perform(post("/ship/profile/nationality").param("nationality", "XX").with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("Choose a country from the list."));
        assertThat(profile(cook).getNationality()).isEqualTo("GR");

        save("/ship/profile/nationality", "nationality", "", cook);
        assertThat(profile(cook).getNationality()).isNull();
        assertThat(audit("PROFILE_NATIONALITY_UPDATED")).extracting(AuditEntry::getDetails).containsExactly("GR", "none");
    }

    @Test
    void bioIsPlainTextOfAtMost280CharactersWithLineBreaks() throws Exception {
        String html = page(get("/ship/profile/bio"), cook);
        assertThat(html).contains("data-draft=\"/ship/profile/bio\"", "Up to 280 characters", "data-max=\"280\"",
                "aria-describedby=\"bioHint bioCounter\"");

        // 281 characters: refused, nothing stored.
        mockMvc.perform(post("/ship/profile/bio").param("bio", "a".repeat(281)).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("The bio is longer than 280 characters.", "is-invalid"));
        assertThat(profileRepository.findByUserId(cook.getId())).isEmpty();

        // 280 with a line break sent as \r\n (one character, as the counter counts it), and markup kept as text.
        String head = "<a href=\"https://example.com\">Cook</a> from Piraeus.\r\n";
        String bio = head + "b".repeat(280 - (head.length() - 1));
        String stored = bio.replace("\r\n", "\n");
        assertThat(stored).hasSize(280);
        save("/ship/profile/bio", "bio", bio, cook);
        assertThat(profile(cook).getBio()).isEqualTo(stored);
        String profileHtml = page(get("/ship/profile"), cook);
        assertThat(profileHtml).contains("&lt;a href=&quot;https://example.com&quot;&gt;Cook&lt;/a&gt; from Piraeus.")
                .doesNotContain("<a href=\"https://example.com\"");
        assertThat(audit("PROFILE_BIO_UPDATED")).extracting(AuditEntry::getDetails).containsExactly("280 characters");

        // Empty: stored as nothing, "Not given".
        save("/ship/profile/bio", "bio", "  \r\n ", cook);
        assertThat(profile(cook).getBio()).isNull();
        assertThat(count(page(get("/ship/profile"), cook), "Not given<")).isEqualTo(4);
    }

    @Test
    void aPhotoIsReEncodedWithoutAnyMetadataAndShownEverywhere() throws Exception {
        String page = page(get("/ship/profile/photo"), cook);
        assertThat(page).contains("JPEG or PNG, at most 5 MB.", "accept=\"image/jpeg,image/png\"",
                "data-max-size=\"5242880\"", "enctype=\"multipart/form-data\"", "No photo: your initials are shown.")
                .doesNotContain("Delete my profile photo");

        // A 1200 x 900 camera JPEG with EXIF: orientation 6 (taken upright, stored on its side) and a GPS position.
        byte[] upload = withExif(jpeg(1200, 900));
        assertThat(new String(upload, StandardCharsets.ISO_8859_1)).contains("Exif", "GPSPOSITION");
        mockMvc.perform(multipart("/ship/profile/photo").file(new MockMultipartFile("photo", "IMG_0001.JPG", "image/jpeg", upload))
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/profile"))
                .andExpect(flash().attribute("successMessage", "profile.saved"));

        String key = profile(cook).getPhotoKey();
        assertThat(key).isNotNull();
        byte[] large = mockMvc.perform(get("/ship/crew/{id}/photo/{key}/512", cook.getId(), key).with(user(new CurrentUser(cook))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"))
                .andReturn().getResponse().getContentAsByteArray();
        // Upright (orientation applied) and at most 512 px: 384 x 512.
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(large));
        assertThat(image.getWidth()).isEqualTo(384);
        assertThat(image.getHeight()).isEqualTo(512);
        assertNoMetadata(large);
        byte[] small = mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", cook.getId(), key).with(user(new CurrentUser(cook))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(small));
        assertThat(thumb.getWidth()).isEqualTo(96);
        assertThat(thumb.getHeight()).isEqualTo(96);
        assertNoMetadata(small);

        // My profile, the crew home header, the Photo page and (P43) the sidebar's user block and More show it, with
        // the profile's version after the key (?v=, so a new photo shows at once); the audit entry has its size, no
        // image data.
        String photo96 = "/ship/crew/" + cook.getId() + "/photo/" + key + "/96?v=" + profile(cook).getUpdatedAt().getEpochSecond();
        String profilePage = page(get("/ship/profile"), cook);
        assertThat(profilePage).contains("<img src=\"" + photo96 + "\"", "<span class=\"profile-row-value\">Added</span>");
        assertThat(sidebarUser(profilePage)).contains("<img src=\"" + photo96 + "\"").doesNotContain("DC");
        assertThat(page(get("/ship"), cook)).contains("<div class=\"page-header-media\">", "<img src=\"" + photo96 + "\"");
        assertThat(page(get("/ship/more"), cook)).contains("<img src=\"" + photo96 + "\"");
        assertThat(page(get("/ship/profile/photo"), cook)).contains("/ship/crew/" + cook.getId() + "/photo/" + key + "/512?v=",
                "alt=\"Photo of Demo Cook\"", "Delete my profile photo");
        assertThat(mockMvc.perform(get(photo96).with(user(new CurrentUser(cook)))).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(audit("PROFILE_PHOTO_UPLOADED")).extracting(AuditEntry::getDetails).containsExactly("384x512 JPEG");

        // A new photo replaces it: a new key, the old URL is gone.
        upload(png(300, 300), "new.png", "image/png", cook).andExpect(redirectedUrl("/ship/profile"));
        String newKey = profile(cook).getPhotoKey();
        assertThat(newKey).isNotEqualTo(key);
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/512", cook.getId(), key).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongTypeTooLargeOrNoFileIsRefused() throws Exception {
        // Named and sent as a JPEG, but not one: the real content decides.
        upload("hello, not a photo".getBytes(StandardCharsets.UTF_8), "photo.jpg", "image/jpeg", cook)
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("This file is not a JPEG or PNG photo that can be read."));
        // A GIF is not accepted either.
        upload("GIF89a........".getBytes(StandardCharsets.US_ASCII), "photo.gif", "image/gif", cook)
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("This file is not a JPEG or PNG photo that can be read."));
        // Over 5 MB.
        byte[] big = Arrays.copyOf(jpeg(10, 10), 5 * 1024 * 1024 + 1);
        upload(big, "big.jpg", "image/jpeg", cook)
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("The photo is larger than 5 MB."));
        // Over the upload limit of the whole app: sent back by UploadTooLargeFilter.
        assertThat(page(get("/ship/profile/photo").param("fileTooLarge", ""), cook)).contains("The photo is larger than 5 MB.");
        // Nothing chosen.
        upload(new byte[0], "", "application/octet-stream", cook)
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("Choose a photo."));
        assertThat(profileRepository.findByUserId(cook.getId())).isEmpty();
        assertThat(audit("PROFILE_PHOTO_UPLOADED")).isEmpty();
    }

    @Test
    void deleteMyProfilePhotoAsksFirstAndGoesBackToInitials() throws Exception {
        upload(jpeg(600, 600), "me.jpg", "image/jpeg", cook).andExpect(redirectedUrl("/ship/profile"));
        String key = profile(cook).getPhotoKey();

        // With JavaScript: the confirmation dialog (neutral); without it: the plain confirmation page.
        assertThat(page(get("/ship/profile/photo"), cook)).contains("data-confirm-title=\"Delete your profile photo?\"",
                "data-confirm-tone=\"neutral\"", "action=\"/ship/profile/photo/delete\"");
        assertThat(page(get("/ship/profile/photo/delete"), cook)).contains("Delete your profile photo?",
                "Your initials are shown instead.", ">Delete photo</button>", "href=\"/ship/profile/photo\"");

        mockMvc.perform(post("/ship/profile/photo/delete").with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/profile"))
                .andExpect(flash().attribute("successMessage", "profile.photo.deleted"));
        assertThat(profile(cook).getPhotoKey()).isNull();
        // The files go once the change is committed; this test's transaction never commits, so commit them here.
        assertThat(photoStorage.load(key, 512)).as("kept until the commit").isNotNull();
        TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(s -> s.getClass().getEnclosingClass() == ProfilePhotoStorage.class)
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(photoStorage.load(key, 512)).as("the files are deleted").isNull();
        assertThat(photoStorage.load(key, 96)).isNull();
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", cook.getId(), key).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        assertThat(page(get("/ship/profile"), cook))
                .containsPattern("<span class=\"person-avatar person-avatar-lg\" aria-hidden=\"true\">\\s*DC\\s*</span>");
        assertThat(audit("PROFILE_PHOTO_DELETED")).hasSize(1);
    }

    @Test
    void onlyTheSignedInPersonsOwnProfileCanBeChanged() throws Exception {
        save("/ship/profile/bio", "bio", "Master of MV Demo.", master);
        // The cook saves: only the cook's profile changes, whatever id the request carries.
        mockMvc.perform(post("/ship/profile/bio").param("bio", "Cook.").param("userId", master.getId().toString())
                        .param("id", master.getId().toString()).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/profile"));
        assertThat(profile(master).getBio()).isEqualTo("Master of MV Demo.");
        assertThat(profile(cook).getBio()).isEqualTo("Cook.");
    }

    @Test
    @WithUserDetails("office@example.com") // seeded by BootstrapData
    void theOfficeSeesTheProfileReadOnlyAndCannotChangeIt() throws Exception {
        String html = page(get("/office/crew/{id}/edit", cook.getId()));
        assertThat(html).contains("id=\"profileCardTitle\">Profile</h2>");
        assertThat(count(html, ">Not given</span>")).isEqualTo(4);

        CrewProfile profile = new CrewProfile(cook);
        profile.setLanguages(List.of("en", "el"));
        profile.setNationality("GR");
        profile.setBio("Line one.\nLine two.");
        profileRepository.save(profile);
        html = page(get("/office/crew/{id}/edit", cook.getId()));
        // P43: the nationality with its flag (the image before the name, decorative).
        assertThat(html).contains("<span>English, Greek</span>", "<span>Greece</span>",
                "<span class=\"text-break text-pre-line\">Line one.\nLine two.</span>")
                .containsPattern("<img class=\"flag-icon\" src=\"/vendor/flags/gr-[0-9a-f]{32}\\.svg\" alt=\"\" width=\"20\" height=\"15\"");
        // In Greek on the Greek office screens.
        assertThat(page(get("/office/crew/{id}/edit", cook.getId()).param("lang", "el")))
                .contains("<span>Αγγλικά, Ελληνικά</span>", "<span>Ελλάδα</span>", ">Προφίλ</h2>");
        // On the seafarer's own My profile, the nationality row has the flag too.
        assertThat(page(get("/ship/profile"), cook)).containsPattern("<img class=\"flag-icon\" src=\"/vendor/flags/gr-[0-9a-f]{32}\\.svg\"[^>]*><span>Greece</span>");
        // Read only: no field of it in the form, and the crew's pages are not the office's.
        assertThat(html).doesNotContain("name=\"bio\"", "name=\"languages\"", "name=\"nationality\"");
        mockMvc.perform(post("/ship/profile/bio").param("bio", "Office text").with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/profile")).andExpect(status().isForbidden());
    }

    @Test
    void theOfficeSeesThePhotoTheCrewOfOtherShipsDoNot() throws Exception {
        upload(jpeg(400, 300), "me.jpg", "image/jpeg", cook).andExpect(redirectedUrl("/ship/profile"));
        String key = profile(cook).getPhotoKey();
        User office = userService.createUser("office2@example.com", "Office Two", "secret123", Role.OFFICE_USER, null, null);
        String officeUrl = "/office/crew/" + cook.getId() + "/photo/" + key + "/96";
        assertThat(page(get("/office/crew/{id}/edit", cook.getId()), office)).containsPattern("<img class=\"profile-fields-photo\"\\s+src=\"" + officeUrl + "\"");
        mockMvc.perform(get(officeUrl).with(user(new CurrentUser(office))))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));
        mockMvc.perform(get("/office/crew/{id}/photo/{key}/512", cook.getId(), "00000000-0000-0000-0000-000000000000")
                .with(user(new CurrentUser(office)))).andExpect(status().isNotFound());

        // Someone on another ship: 404 for the crew URL, 403 for the office's.
        Ship other = ship("9123456", "MV Other");
        User stranger = userService.createUser("other@example.com", "Other Crew", "secret123", Role.CREW_MEMBER, other,
                Rank.ABLE_SEAMAN);
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", cook.getId(), key).with(user(new CurrentUser(stranger))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(officeUrl).with(user(new CurrentUser(stranger)))).andExpect(status().isForbidden());
        // Signed out: the sign-in page, never the photo.
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", cook.getId(), key)).andExpect(status().is3xxRedirection());
        // A crewmate on the same ship sees it.
        mockMvc.perform(get("/ship/crew/{id}/photo/{key}/96", cook.getId(), key).with(user(new CurrentUser(master))))
                .andExpect(status().isOk());
    }

    @Test
    void everyFieldWorksWithoutJavaScript() throws Exception {
        // Each field page is a plain POST form with Save and Cancel back to My profile.
        for (String field : List.of("languages", "nationality", "bio", "photo")) {
            String html = page(get("/ship/profile/" + field), cook);
            assertThat(html).as(field).containsPattern("<form action=\"/ship/profile/" + field + "\" method=\"post\"")
                    .contains(">Save</button>", "<a class=\"btn btn-ghost\" href=\"/ship/profile\">Cancel</a>",
                            "<a href=\"/ship/profile\">My profile</a>");
        }
        // Languages: Find is a GET form; the search runs on the server ("gre" finds Greek, not English).
        String html = page(get("/ship/profile/languages").param("q", "gre"), cook);
        assertThat(html).contains("<form class=\"language-search\" action=\"/ship/profile/languages\" method=\"get\"",
                ">Find</button>")
                .containsPattern("<li class=\"language-result\"\\s+data-search=\"greek el\">")
                .containsPattern("<li class=\"language-result\"\\s+data-search=\"english en\" hidden=\"hidden\">");
        // Accents do not matter: "bokmal" finds "Norwegian Bokmål".
        assertThat(page(get("/ship/profile/languages").param("q", "bokmal"), cook))
                .containsPattern("<li class=\"language-result\"\\s+data-search=\"norwegian bokmal nb\">");
        // js/profile.js is on the Languages and Photo pages only, deferred; the layout's scripts do not include it.
        assertThat(scripts(html)).contains("js/profile");
        assertThat(scripts(page(get("/ship/profile/photo"), cook))).contains("js/profile");
        assertThat(scripts(page(get("/ship/profile"), cook))).doesNotContain("js/profile");
        assertThat(scripts(page(get("/ship/profile/bio"), cook))).doesNotContain("js/profile");
    }

    @Test
    void moreListsMyProfileMyCrewAndSignOut() throws Exception {
        String html = page(get("/ship/more"), cook);
        assertThat(html).contains("<title>More · Nereonet</title>", "Signed in as", ">Demo Cook</span>",
                "Chief Cook · MV Demo", "href=\"/ship/profile\"", "href=\"/ship/crew\"",
                "<form action=\"/logout\" method=\"post\">", ">Sign out</button>",
                // The tab bar's fifth item, active here; a normal link.
                "<a class=\"tab-bar-link active\" href=\"/ship/more\" aria-current=\"page\">");
        // My profile: More is the active tab; the sidebar's My profile entry is active.
        String profile = page(get("/ship/profile/bio"), cook);
        assertThat(profile).contains("<a class=\"tab-bar-link active\" href=\"/ship/more\" aria-current=\"page\">",
                "<a class=\"nav-link sidebar-link active\" href=\"/ship/profile\" aria-current=\"page\">");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void save(String url, String field, String value, User u) throws Exception {
        mockMvc.perform(post(url).param(field, value).with(user(new CurrentUser(u))).with(csrf()))
                .andExpect(redirectedUrl("/ship/profile"))
                .andExpect(flash().attribute("successMessage", "profile.saved"));
    }

    private org.springframework.test.web.servlet.ResultActions upload(byte[] bytes, String name, String type, User u)
            throws Exception {
        return mockMvc.perform(multipart("/ship/profile/photo").file(new MockMultipartFile("photo", name, type, bytes))
                .with(user(new CurrentUser(u))).with(csrf()));
    }

    private CrewProfile profile(User u) {
        return profileRepository.findByUserId(u.getId()).orElseThrow();
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
    }

    private String page(MockHttpServletRequestBuilder request, User u) throws Exception {
        return page(request.with(user(new CurrentUser(u))));
    }

    private static String main(String html) {
        Matcher m = Pattern.compile("(?s)<main.*?</main>").matcher(html);
        assertThat(m.find()).isTrue();
        return m.group();
    }

    // The sidebar's user block (P43): the person's picture, name and role.
    private static String sidebarUser(String html) {
        Matcher m = Pattern.compile("(?s)<div class=\"sidebar-user\">.*?</form>\\s*</div>").matcher(html);
        assertThat(m.find()).isTrue();
        return m.group();
    }

    private String page(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> languageNames(String html) {
        Matcher m = Pattern.compile("(?s)<ol class=\"language-list\">(.*?)</ol>").matcher(html);
        if (!m.find()) {
            return List.of();
        }
        return Pattern.compile("<span class=\"language-name\">([^<]+)</span>").matcher(m.group(1)).results()
                .map(r -> r.group(1)).toList();
    }

    private static int count(String html, String needle) {
        int n = 0;
        for (int i = html.indexOf(needle); i >= 0; i = html.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static List<String> scripts(String html) {
        return SCRIPT.matcher(html).results().map(m -> m.group(1)).toList();
    }

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }

    static byte[] jpeg(int width, int height) throws IOException {
        return image(width, height, "jpeg", BufferedImage.TYPE_INT_RGB);
    }

    static byte[] png(int width, int height) throws IOException {
        return image(width, height, "png", BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] image(int width, int height, String format, int type) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(16, 34, 58));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** The JPEG with an EXIF segment after its JFIF header: orientation 6 and a GPS IFD holding "GPSPOSITION". */
    static byte[] withExif(byte[] jpeg) {
        byte[] tiff = {
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,                 // big endian, IFD0 at 8
                0, 2,                                          // IFD0: 2 entries
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,      // Orientation = 6
                (byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 38, // GPS IFD at 38
                0, 0, 0, 0,                                    // no next IFD
                0, 1,                                          // GPS IFD: 1 entry
                0, 2, 0, 2, 0, 0, 0, 12, 0, 0, 0, 56,          // GPSLatitude (ASCII here), 12 bytes at 56
                0, 0, 0, 0,                                    // no next IFD
                'G', 'P', 'S', 'P', 'O', 'S', 'I', 'T', 'I', 'O', 'N', 0};
        byte[] header = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + header.length + tiff.length;
        byte[] segment = new byte[2 + length];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(header, 0, segment, 4, header.length);
        System.arraycopy(tiff, 0, segment, 4 + header.length, tiff.length);
        // After SOI and the JFIF APP0 segment.
        int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
        int at = 2 + 2 + app0Length;
        byte[] out = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, out, 0, at);
        System.arraycopy(segment, 0, out, at, segment.length);
        System.arraycopy(jpeg, at, out, at + segment.length, jpeg.length - at);
        return out;
    }

    // A JPEG with nothing but the image: no APP1 (EXIF, XMP), no comment, no "Exif", "GPS" or TIFF header anywhere.
    private static void assertNoMetadata(byte[] jpeg) {
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
        String text = new String(jpeg, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("Exif", "GPSPOSITION", "MM\0*", "II*\0", "http://ns.adobe.com");
        for (int i = 2; i + 4 <= jpeg.length; ) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA) {
                break;
            }
            assertThat(marker).as("segment marker").isNotIn(0xE1, 0xE2, 0xED, 0xFE);
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
    }
}
