package com.nereonix.nereonet_mvp.officerequest;

import java.util.Arrays;
import java.util.List;

/**
 * The "Export status" filter of the approved requests list (?exported=, P21): no, yes or all. Without it, or with a
 * value it does not know (an old ?notExported=true link), the list shows the requests not exported yet: what the
 * office has to do. A link that means that list says so (?exported=no, the Overview's), so it does not depend on
 * the default.
 */
public enum ExportState {

    NOT_EXPORTED("no"),
    EXPORTED("yes"),
    ALL("all");

    /** The options of the filter after the default one. */
    public static final List<ExportState> OPTIONS = List.of(EXPORTED, ALL);

    private final String param;

    ExportState(String param) {
        this.param = param;
    }

    public static ExportState of(String value) {
        return Arrays.stream(values()).filter(s -> s.param.equals(value)).findFirst().orElse(NOT_EXPORTED);
    }

    /** The URL value: "no", "yes", "all". */
    public String param() {
        return param;
    }

    /** The URL value the list keeps in its links and chips: null for the default. */
    public String key() {
        return isDefault() ? null : param;
    }

    public boolean isDefault() {
        return this == NOT_EXPORTED;
    }

    /** The option's text: request.filter.exported.no, .yes, .all. */
    public String labelKey() {
        return "request.filter.exported." + param;
    }
}
