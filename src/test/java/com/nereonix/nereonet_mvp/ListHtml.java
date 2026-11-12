package com.nereonix.nereonet_mvp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** Reading an office list page in the P19 flow tests: the page's HTML and the rows of its table. */
public final class ListHtml {

    private static final Pattern TBODY = Pattern.compile("(?s)<tbody>(.*?)</tbody>");
    private static final Pattern PRIMARY_CELL = Pattern.compile("(?s)<td class=\"cell-primary[^\"]*\"[^>]*>(.*?)</td>");
    private static final Pattern ROW = Pattern.compile("(?s)<tr\\b.*?</tr>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private ListHtml() {
    }

    public static String html(MockMvc mockMvc, RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The text of each row's primary cell (the record's name), in the order shown; empty without a table. */
    public static List<String> names(String html) {
        var body = TBODY.matcher(html);
        if (!body.find()) {
            return List.of();
        }
        return PRIMARY_CELL.matcher(body.group(1)).results()
                .map(m -> TAG.matcher(m.group(1)).replaceAll("").replaceAll("\\s+", " ").strip())
                .toList();
    }

    /** The rows of the table's body, as HTML; empty without a table. */
    public static List<String> rows(String html) {
        var body = TBODY.matcher(html);
        if (!body.find()) {
            return List.of();
        }
        return ROW.matcher(body.group(1)).results().map(MatchResult::group).toList();
    }
}
