package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Frontend assets come from the app itself, with content-hashed URLs and a one-year cache. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticAssetsFlowTest {

    private static final String ONE_YEAR = "max-age=31536000";
    private static final Pattern HASHED_ASSET =
            Pattern.compile("(?:href|src|srcset)=\"(/(?:css|js|vendor|fonts|img)/[^\"]+-[0-9a-f]{32}\\.[a-z0-9]+)\"");

    @Autowired
    MockMvc mockMvc;

    @Test
    void signInPageLinksOnlyHashedLocalAssetsAndTheyAreCachedForAYear() throws Exception {
        String html = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("cdn.jsdelivr.net");
        List<String> assets = hashedAssets(html);
        assertThat(assets).anyMatch(u -> u.startsWith("/vendor/bootstrap/bootstrap.min-"))
                .anyMatch(u -> u.startsWith("/vendor/bootstrap/bootstrap.bundle.min-"))
                .anyMatch(u -> u.startsWith("/css/app-"))
                .anyMatch(u -> u.startsWith("/js/local-time-"))
                .anyMatch(u -> u.startsWith("/img/brand/favicon-32-"))
                .anyMatch(u -> u.startsWith("/img/brand/apple-touch-icon-"))
                // P30: the mark of the sign-in panel; the large logo (49 KB) is only for emails now.
                .anyMatch(u -> u.startsWith("/img/brand/nereonix-mark-192-"))
                .noneMatch(u -> u.startsWith("/img/brand/nereonix-logo-on-dark-600-"));

        // Signed out: the sign-in page must be fully styled, so every asset (hashed URL included) is public.
        for (String asset : assets) {
            mockMvc.perform(get(asset))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString(ONE_YEAR)));
        }
    }

    @Test
    @WithUserDetails("office@example.com") // seeded by BootstrapData
    void officePageLinksTheSameLocalAssets() throws Exception {
        String html = mockMvc.perform(get("/office"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("cdn.jsdelivr.net");
        assertThat(hashedAssets(html)).anyMatch(u -> u.startsWith("/vendor/bootstrap/bootstrap.bundle.min-"));
    }

    @Test
    void appCssLoadsTheInterFontsThroughHashedUrls() throws Exception {
        String html = mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        String appCss = hashedAssets(html).stream().filter(u -> u.startsWith("/css/app-")).findFirst().orElseThrow();

        String css = mockMvc.perform(get(appCss)).andReturn().getResponse().getContentAsString();
        // Spring rewrites url("../fonts/x.woff2") to the hashed "/fonts/x-<md5>.woff2".
        Matcher fonts = Pattern.compile("/fonts/(inter-(?:latin|greek)-wght-normal-[0-9a-f]{32}\\.woff2)").matcher(css);
        List<String> fontFiles = new ArrayList<>();
        while (fonts.find()) {
            fontFiles.add(fonts.group(1));
        }
        assertThat(fontFiles).hasSize(2);
        for (String font : fontFiles) {
            mockMvc.perform(get("/fonts/" + font))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString(ONE_YEAR)));
        }
    }

    // Our own templates and CSS never point at another host. XML namespaces are identifiers, not requests.
    @Test
    void templatesAndAppCssHaveNoExternalUrls() throws IOException {
        Pattern url = Pattern.compile("https?://[^\\s\"'()<>]+");
        List<String> found = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/resources/templates"), Path.of("src/main/resources/static/css"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Matcher m = url.matcher(Files.readString(file));
                    while (m.find()) {
                        String u = m.group();
                        if (!u.startsWith("http://www.thymeleaf.org") && !u.equals("http://www.w3.org/2000/svg")) {
                            found.add(file + ": " + u);
                        }
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }

    private static List<String> hashedAssets(String html) {
        Matcher m = HASHED_ASSET.matcher(html);
        List<String> urls = new ArrayList<>();
        while (m.find()) {
            urls.add(m.group(1));
        }
        return urls;
    }
}
