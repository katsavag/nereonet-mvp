package com.nereonix.nereonet_mvp.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.config.DevData;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

/**
 * P47: on the crew side the office's announcements are "News" everywhere. With the dev data and two news items sent
 * to the fleet (one of them opened), every crew page a crew member can reach by following links from Home, More and
 * Notifications is rendered, as the Master and as the Chief cook, and none says "Announcement" in what a person reads
 * or hears: the text, the tab title and the attributes a screen reader or a tooltip shows. (Class names, URLs and the
 * template comments are not read by anyone.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData: the office sends the news; pages use .with(user(...))
class CrewNewsWordingTest {

    private static final List<String> CREW = List.of("master.demo@example.com", "cook.demo@example.com");
    private static final List<String> START = List.of("/ship", "/ship/more", "/ship/news", "/notifications",
            "/ship/profile", "/ship/library", "/ship/crew");
    private static final int MAX_PAGES = 120;

    private static final Pattern HREF = Pattern.compile("href=\"(/(?:ship|notifications)[^\"#]*)\"");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|svg)\\b.*?</\\1>");
    private static final Pattern READ_ATTRIBUTE = Pattern.compile(
            "\\s(?:title|aria-label|alt|placeholder|value|content|data-[a-z-]*(?:text|label|title|message))=\"([^\"]*)\"");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementService announcementService;
    @Autowired ShipRepository shipRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired LibraryItemRepository libraryItemRepository;
    @Autowired LibraryItemVersionRepository versionRepository;
    @Autowired CrewProfileRepository profileRepository;
    @Autowired RequestService requestService;
    @Autowired VettingService vettingService;
    @Autowired SireService sireService;
    @Autowired SupplierRepository supplierRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void noCrewPageSaysAnnouncement() throws Exception {
        new DevData(shipRepository, userRepository, userService, itemRepository, requestRepository,
                libraryItemRepository, versionRepository, profileRepository, requestService, vettingService, sireService,
                supplierRepository).run(null);
        announcementService.create("Safety drill", "Drill on Friday.\nMuster at the lifeboat station.", true, List.of(),
                "office@example.com");
        AnnouncementService.Sent second = announcementService.create("Bunkering plan", "Bunkering on Monday.", true,
                List.of(), "office@example.com");
        for (String email : CREW) {
            CurrentUser me = new CurrentUser(userRepository.findByEmail(email).orElseThrow());
            // One read, one new, so the list shows both groups (opening it reads it, P39).
            mockMvc.perform(get("/ship/news/{id}", second.announcement().getId()).with(user(me)));
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(START);
            List<String> offending = new ArrayList<>();
            while (!queue.isEmpty() && seen.size() < MAX_PAGES) {
                String url = queue.poll();
                if (!seen.add(url)) {
                    continue;
                }
                MockHttpServletResponse response = mockMvc.perform(get(url).with(user(me))).andReturn().getResponse();
                assertThat(response.getStatus()).as("%s as %s", url, email).isLessThan(500);
                String type = response.getContentType();
                if (response.getStatus() != 200 || type == null || !type.startsWith("text/html")) {
                    continue;
                }
                String html = response.getContentAsString(StandardCharsets.UTF_8);
                String read = readText(html);
                if (read.toLowerCase(Locale.ROOT).contains("announcement")) {
                    offending.add(url + ": " + around(read, "announcement"));
                }
                Matcher links = HREF.matcher(html);
                while (links.find()) {
                    String link = links.group(1).replace("&amp;", "&");
                    if (!seen.contains(link) && !link.contains("/download") && !link.contains("/file")) {
                        queue.add(link);
                    }
                }
            }
            // The crawl reached the pages that matter.
            assertThat(seen).as(email).contains("/ship", "/ship/news", "/ship/more", "/notifications")
                    .anyMatch(u -> u.matches("/ship/news/\\d+"));
            assertThat(offending).as("crew pages that say Announcement, as " + email).isEmpty();
        }
    }

    /** What a person reads or hears: the text and the attributes shown or spoken, without comments and scripts. */
    private static String readText(String html) {
        String s = COMMENT.matcher(html).replaceAll(" ");
        s = SCRIPT.matcher(s).replaceAll(" ");
        StringBuilder attributes = new StringBuilder();
        Matcher m = READ_ATTRIBUTE.matcher(s);
        while (m.find()) {
            attributes.append(' ').append(m.group(1));
        }
        return TAG.matcher(s).replaceAll(" ").replaceAll("\\s+", " ") + " |" + attributes;
    }

    private static String around(String text, String word) {
        int i = text.toLowerCase(Locale.ROOT).indexOf(word);
        return text.substring(Math.max(0, i - 60), Math.min(text.length(), i + 60));
    }
}
