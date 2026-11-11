package com.nereonix.nereonet_mvp.core.confirm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPage.Crumb;
import com.nereonix.nereonet_mvp.core.confirm.ConfirmPage.Tone;

/**
 * Builds the plain confirmation page (P10) from the same message keys as the action's dialog. A controller answers a
 * GET on the action's own URL with it, e.g. GET /office/ships/3/deactivate:
 * <pre>
 * return confirmPages.neutral()
 *         .title("ship.deactivate.confirm.title", ship.getName())
 *         .body("ship.deactivate.confirm.body", ship.getName())
 *         .confirmLabel("ship.deactivate")
 *         .action("/office/ships/3/deactivate", tab)
 *         .cancelUrl("/office/ships")
 *         .crumb("nav.ships", "/office/ships")
 *         .show(model);
 * </pre>
 */
@Component
public class ConfirmPages {

    public static final String VIEW = "confirm/confirm";

    private final MessageSource messageSource;

    public ConfirmPages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** For what cannot be undone: delete, retire, cancel an invitation. */
    public Builder danger() {
        return new Builder(Tone.DANGER);
    }

    /** For what Activate undoes: deactivate a ship, a user, an item. */
    public Builder neutral() {
        return new Builder(Tone.NEUTRAL);
    }

    private String text(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    public final class Builder {

        private final Tone tone;
        private final List<Crumb> crumbs = new ArrayList<>();
        private String title;
        private String body;
        private String confirmLabel;
        private String dismissLabel;
        private String action;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private String cancelUrl;

        private Builder(Tone tone) {
            this.tone = tone;
        }

        public Builder title(String key, Object... args) {
            title = text(key, args);
            return this;
        }

        public Builder body(String key, Object... args) {
            body = text(key, args);
            return this;
        }

        public Builder confirmLabel(String key) {
            confirmLabel = text(key);
            return this;
        }

        /** Only when the confirm label is itself a "Cancel …"; otherwise the dismiss button says "Cancel". */
        public Builder dismissLabel(String key) {
            dismissLabel = text(key);
            return this;
        }

        /** The POST URL, and the ship page tab it returns to (null for none). */
        public Builder action(String url, String tab) {
            this.action = url;
            if (tab != null) {
                fields.put("tab", tab);
            }
            return this;
        }

        /** More hidden fields the action is posted with, e.g. the list an invitation's Cancel returns to. */
        public Builder fields(Map<String, String> more) {
            fields.putAll(more);
            return this;
        }

        public Builder cancelUrl(String url) {
            this.cancelUrl = url;
            return this;
        }

        /** A breadcrumb whose label is a message key. */
        public Builder crumb(String key, String url) {
            crumbs.add(new Crumb(text(key), url));
            return this;
        }

        /** A breadcrumb whose label is a record's name. */
        public Builder crumbText(String label, String url) {
            crumbs.add(new Crumb(label, url));
            return this;
        }

        /** Adds the page to the model and returns its view name. */
        public String show(Model model) {
            model.addAttribute("confirm", new ConfirmPage(title, body, confirmLabel,
                    dismissLabel == null ? text("form.cancel") : dismissLabel, tone, action, Collections.unmodifiableMap(new LinkedHashMap<>(fields)), cancelUrl,
                    List.copyOf(crumbs)));
            return VIEW;
        }
    }
}
