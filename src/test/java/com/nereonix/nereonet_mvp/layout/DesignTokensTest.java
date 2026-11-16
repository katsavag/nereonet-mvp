package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The visual system lives in app.css as tokens; templates do not style themselves. */
class DesignTokensTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    void appCssPutsEverySurfaceOnTheTokens() throws IOException {
        String css = Files.readString(APP_CSS);
        // Three surface levels; tables, list groups, modals and menus are surfaces, not the page colour.
        assertThat(css).contains("--nn-page:", "--nn-surface:", "--nn-field:")
                .contains("--bs-table-bg: var(--nn-surface)", "--bs-list-group-bg: var(--nn-surface)",
                        "--bs-modal-bg: var(--nn-surface)", "--bs-dropdown-bg: var(--nn-surface)",
                        "--bs-card-bg: var(--nn-surface)", "--bs-form-check-bg: var(--nn-field)");
        // One blue: Bootstrap's primary, links and focus ring point at the brand navy.
        assertThat(css).contains("--bs-primary: var(--nn-blue)", "--bs-link-color: var(--nn-blue)",
                "--bs-focus-ring-color: var(--nn-focus-color)");
        assertThat(css.toLowerCase()).doesNotContain("#0d6efd", "13, 110, 253", "13,110,253");
    }

    @Test
    // Card headers and table header rows share one header surface, darker than the card, with the text colour
    // on it at 4.5:1 or more and a visible step next to the surface.
    void headerSurfaceIsATokenWithReadableText() throws IOException {
        String css = Files.readString(APP_CSS);
        String head = token(css, "--nn-head-bg");
        String surface = token(css, "--nn-surface");
        String text = token(css, "--nn-text");
        assertThat(contrast(text, head)).isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(head, surface)).isGreaterThanOrEqualTo(1.15);
        assertThat(luminance(head)).isLessThan(luminance(token(css, "--nn-cream")));
        assertThat(css).contains("--bs-card-cap-bg: var(--nn-head-bg)", "--bs-table-bg: var(--nn-head-bg)",
                "border-bottom: 1px solid var(--nn-divider)");
        // Table header labels in the text colour, not the secondary one.
        String th = css.substring(css.indexOf(".data-table > thead > tr > th {"));
        assertThat(th.substring(0, th.indexOf('}'))).contains("color: var(--nn-text);")
                .doesNotContain("--nn-text-secondary");
        // No card header is made transparent, in the stylesheet or the templates.
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                assertThat(Files.readString(file)).as(file.toString())
                        .doesNotContainPattern("card-header[^\"]*bg-transparent");
            }
        }
    }

    private static String token(String css, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)
                + ":\\s*(#[0-9a-fA-F]{6})").matcher(css);
        assertThat(m.find()).as(name).isTrue();
        return m.group(1);
    }

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(String a, String b) {
        double x = luminance(a);
        double y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    @Test
    void appCssDefinesThreeButtonSizesAndFourLevels() throws IOException {
        String css = Files.readString(APP_CSS);
        assertThat(css).contains("--nn-btn-height: 2.5rem", "--nn-btn-height-sm: 2rem", "--nn-btn-height-lg: 3rem",
                "min-height: var(--nn-btn-height)", "--nn-touch-target: 44px", "--bs-btn-disabled-opacity: .5",
                "cursor: not-allowed", ".btn-outline-secondary {", ".btn-ghost {", ".btn-outline-danger {",
                ".page-actions {", ".row-menu");
    }

    @Test
    void largeButtonsOnlyWhereTheyAreFullWidth() throws IOException {
        // Sign-in, registration, forgot password and signed-out error pages (w-100; the error pages' button is in
        // layout/parts :: errorPage).
        Path templates = Path.of("src/main/resources/templates");
        List<String> allowed = List.of("login/login.html", "registration/register.html", "registration/invalid.html",
                "passwordreset/forgot.html", "passwordreset/sent.html", "passwordreset/reset.html", "passwordreset/invalid.html",
                "layout/parts.html");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(templates)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                String name = templates.relativize(file).toString().replace('\\', '/');
                if (html.contains("btn-lg") && !allowed.contains(name)) {
                    found.add(file.toString());
                }
                if (html.matches("(?s).*\\bbtn-danger\\b.*")) {
                    found.add(file + " (filled btn-danger; use btn-outline-danger in a dialog or overflow menu)");
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    // Except the emails (templates/email): mail clients ignore stylesheets, so emails are styled inline.
    void templatesHaveNoInlineStyles() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html"))
                    .filter(f -> !f.toString().replace('\\', '/').contains("templates/email/")).toList()) {
                if (Files.readString(file).matches("(?s).*\\sstyle=\".*")) {
                    found.add(file.toString());
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
