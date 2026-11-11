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
    void appCssDefinesThreeButtonSizesAndFourLevels() throws IOException {
        String css = Files.readString(APP_CSS);
        assertThat(css).contains("--nn-btn-height: 2.5rem", "--nn-btn-height-sm: 2rem", "--nn-btn-height-lg: 3rem",
                "min-height: var(--nn-btn-height)", "--nn-touch-target: 44px", "--bs-btn-disabled-opacity: .5",
                "cursor: not-allowed", ".btn-outline-secondary {", ".btn-ghost {", ".btn-outline-danger {",
                ".page-actions {", ".row-menu");
    }

    @Test
    void largeButtonsOnlyWhereTheyAreFullWidthOrIHaveReadThis() throws IOException {
        // Sign-in, registration and error pages (w-100), and the crew "I have read this" button.
        List<String> allowed = List.of("login.html", "register.html", "invalid.html", "error.html", "item.html");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                if (html.contains("btn-lg") && !allowed.contains(file.getFileName().toString())) {
                    found.add(file.toString());
                }
                if (html.matches("(?s).*\\bbtn-danger\\b.*")) {
                    found.add(file + " (filled btn-danger; use btn-outline-danger in a dialog or overflow menu)");
                }
            }
        }
        assertThat(found).isEmpty();
        assertThat(Files.readString(Path.of("src/main/resources/templates/library/item.html")))
                .contains("btn btn-primary btn-lg\" th:text=\"#{library.acknowledge}\"");
    }

    @Test
    void templatesHaveNoInlineStyles() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                if (Files.readString(file).matches("(?s).*\\sstyle=\".*")) {
                    found.add(file.toString());
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
