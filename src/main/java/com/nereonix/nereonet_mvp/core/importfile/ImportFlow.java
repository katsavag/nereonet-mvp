package com.nereonix.nereonet_mvp.core.importfile;

import java.util.List;

import com.nereonix.nereonet_mvp.core.ship.Ship;

/**
 * What the shared import pages (templates/importfile, P24) need to know about one kind of import, model attribute
 * "flow".
 *
 * messagePrefix the kind's own texts: messagePrefix + ".title", ".heading", ".upload.intro", ...
 *               ("crewImport", "itemImport")
 * basePath      the upload page; the steps are under it: basePath + "/map", "/review", ...
 * listPath      where Cancel and the Done step lead
 * templatePath  the template download
 * ship          the ship of a ship-scoped import (the page header's eyebrow), or null
 * crumbs        the breadcrumbs above the import's own pages
 */
public record ImportFlow(String messagePrefix, String basePath, String listPath, String templatePath, Ship ship,
                         List<Crumb> crumbs) {

    /** One breadcrumb: its label is the message labelKey, or text when labelKey is null. */
    public record Crumb(String labelKey, String text, String url) {

        public static Crumb of(String labelKey, String url) {
            return new Crumb(labelKey, null, url);
        }

        public static Crumb text(String text, String url) {
            return new Crumb(null, text, url);
        }
    }

    public String path(String step) {
        return basePath + step;
    }

    public String key(String suffix) {
        return messagePrefix + "." + suffix;
    }
}
