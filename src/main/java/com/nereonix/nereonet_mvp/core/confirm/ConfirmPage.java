package com.nereonix.nereonet_mvp.core.confirm;

import java.util.List;

/**
 * What a confirmation asks (P10), for the plain confirmation page (confirm/confirm.html). The same texts are on the
 * action's form as data attributes (layout/parts :: confirmForm), where js/confirm.js shows them in the dialog; this
 * page is where the action leads when that script did not load. Every text is already in the user's language.
 *
 * @param title        names the record: "Deactivate MV Demo?"
 * @param body         the consequence, one sentence
 * @param confirmLabel the confirm button: "Deactivate"
 * @param dismissLabel the button that goes back without doing anything: "Cancel", or e.g. "Keep invitation" when the
 *                     confirm label itself says "Cancel"
 * @param tone         DANGER for what cannot be undone, NEUTRAL for what Activate undoes
 * @param action       the POST URL of the action
 * @param tab          the ship page tab the action returns to, or null
 * @param cancelUrl    where the dismiss button goes: the page the action was started from
 * @param crumbs       the breadcrumbs above the heading (the heading itself is added by the page header)
 */
public record ConfirmPage(String title, String body, String confirmLabel, String dismissLabel, Tone tone,
                          String action, String tab, String cancelUrl, List<Crumb> crumbs) {

    public enum Tone {
        DANGER, NEUTRAL;

        /** The value of data-confirm-tone and of the page's tone: 'danger' or 'neutral'. */
        public String key() {
            return name().toLowerCase();
        }
    }

    /** One breadcrumb: its text and an app path. */
    public record Crumb(String label, String url) {
    }

    public boolean danger() {
        return tone == Tone.DANGER;
    }
}
