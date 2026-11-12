package com.nereonix.nereonet_mvp.profile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.util.UriComponentsBuilder;

import com.nereonix.nereonet_mvp.core.profile.CrewProfile;
import com.nereonix.nereonet_mvp.core.profile.Languages;

/**
 * The Languages page's working list (P31), before Save. It lives in the page, never in the database: in the URL
 * (?languages=en,el&q=gre) and in the Save form's hidden field. Add, Remove, Move up and Move down are links to the
 * same page with the changed list, so they work without JavaScript and nothing is stored until Save; the search
 * ("Find") is a GET form that carries the list along. js/profile.js filters the results as the user types.
 *   chosen  ISO 639-1 codes in the person's order (unknown codes and repeats are dropped, at most five kept)
 *   q       the search, or "" for none
 */
public record LanguageChoice(List<String> chosen, String q) {

    static final String PATH = "/ship/profile/languages";

    public record Chosen(String code, String name, boolean first, boolean last) {
    }

    /** shown: matches the search (the others are in the page too, hidden, so js/profile.js can filter all of them). */
    public record Result(String code, String name, boolean chosen, boolean shown) {

        /** What js/profile.js matches the typed text against: the name folded as the server folds it, and the code. */
        public String search() {
            return fold(name) + " " + code;
        }
    }

    /** joined: the list as sent ("en,el"); q: the search, may be null. */
    public static LanguageChoice of(String joined, String q) {
        List<String> codes = Languages.parse(joined);
        return new LanguageChoice(codes.subList(0, Math.min(codes.size(), CrewProfile.MAX_LANGUAGES)),
                q == null ? "" : q.strip());
    }

    public int max() {
        return CrewProfile.MAX_LANGUAGES;
    }

    public boolean full() {
        return chosen.size() >= CrewProfile.MAX_LANGUAGES;
    }

    public String joined() {
        return Languages.join(chosen);
    }

    public List<Chosen> chosenRows() {
        List<Chosen> rows = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            rows.add(new Chosen(chosen.get(i), Languages.name(chosen.get(i), Locale.ENGLISH), i == 0,
                    i == chosen.size() - 1));
        }
        return rows;
    }

    /** Every language, sorted by name; shown when its English name contains the search (accents and case ignored) or
     *  the search is its code. */
    public List<Result> results() {
        String needle = fold(q);
        List<Result> results = new ArrayList<>();
        for (Map.Entry<String, String> language : Languages.all().entrySet()) {
            boolean shown = needle.isEmpty() || fold(language.getValue()).contains(needle)
                    || language.getKey().equals(needle);
            results.add(new Result(language.getKey(), language.getValue(), chosen.contains(language.getKey()), shown));
        }
        return results;
    }

    public long shownCount() {
        return results().stream().filter(Result::shown).count();
    }

    public String addUrl(String code) {
        List<String> next = new ArrayList<>(chosen);
        if (!next.contains(code) && !full()) {
            next.add(code);
        }
        return url(next, "yourLanguages");
    }

    public String removeUrl(String code) {
        List<String> next = new ArrayList<>(chosen);
        next.remove(code);
        return url(next, "yourLanguages");
    }

    public String upUrl(String code) {
        return move(code, -1);
    }

    public String downUrl(String code) {
        return move(code, 1);
    }

    private String move(String code, int by) {
        List<String> next = new ArrayList<>(chosen);
        int from = next.indexOf(code);
        int to = from + by;
        if (from >= 0 && to >= 0 && to < next.size()) {
            Collections.swap(next, from, to);
        }
        return url(next, "yourLanguages");
    }

    private String url(List<String> codes, String fragment) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(PATH).queryParam("languages", Languages.join(codes));
        if (!q.isEmpty()) {
            url.queryParam("q", q);
        }
        return url.fragment(fragment).encode().build().toUriString();
    }

    // Lower case without accents: "Français" and "francais" match.
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text.strip(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
