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
