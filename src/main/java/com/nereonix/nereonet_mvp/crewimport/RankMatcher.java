package com.nereonix.nereonet_mvp.crewimport;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.importfile.ImportField;
import com.nereonix.nereonet_mvp.core.importfile.ImportNames;
import com.nereonix.nereonet_mvp.core.user.Rank;

// Matches the rank values of a crewing system's file to the ranks (P24). Exact: the rank's name in any spelling
// ("Chief Engineer", "CHIEF_ENGINEER", "chief engineer"). Suggested: the usual short forms ("C/E", "Ch. Eng.", "2/O"),
// or the one rank whose name starts with the value ("Chief Eng").
final class RankMatcher implements ImportField.ValueMatcher {

    static final RankMatcher INSTANCE = new RankMatcher();

    // ImportNames.compact of the short form -> the rank.
    private static final Map<String, Rank> SHORT_FORMS = new HashMap<>();

    static {
        add(Rank.MASTER, "capt", "captain", "cpt", "mstr", "mst", "mastermariner", "ms");
        add(Rank.CHIEF_OFFICER, "co", "chiefmate", "chmate", "choff", "chiefoff", "chofficer", "1o", "1stofficer",
                "firstofficer", "1stoff", "1stmate", "cmate");
        add(Rank.SECOND_OFFICER, "2o", "2ndofficer", "2ndoff", "secondmate", "2ndmate", "2off", "secoff");
        add(Rank.THIRD_OFFICER, "3o", "3rdofficer", "3rdoff", "thirdmate", "3rdmate", "3off");
        add(Rank.DECK_CADET, "dc", "cadetdeck", "dcdt", "deckcdt", "cadet");
        add(Rank.BOSUN, "boatswain", "bsn", "bosn");
        add(Rank.ABLE_SEAMAN, "ab", "abs", "ablebodiedseaman", "ablebodied", "able");
        add(Rank.ORDINARY_SEAMAN, "os", "ordseaman", "ordinary");
        add(Rank.CHIEF_ENGINEER, "ce", "cheng", "chiefeng", "chiefengr", "chengr", "chengineer");
        add(Rank.SECOND_ENGINEER, "2e", "2ndeng", "2ndengineer", "2ndengr", "secondeng", "2eng");
        add(Rank.THIRD_ENGINEER, "3e", "3rdeng", "3rdengineer", "3rdengr", "thirdeng", "3eng");
        add(Rank.FOURTH_ENGINEER, "4e", "4theng", "4thengineer", "4thengr", "fourtheng", "4eng");
        add(Rank.ELECTRO_TECHNICAL_OFFICER, "eto", "electrician", "elec", "electricalofficer", "eo", "electroofficer",
                "etoff");
        add(Rank.ENGINE_CADET, "ec", "cadetengine", "ecdt", "enginecdt");
        add(Rank.FITTER, "ftr");
        add(Rank.OILER, "motorman", "mm", "oil");
        add(Rank.WIPER, "wpr");
        add(Rank.CHIEF_COOK, "chcook", "chck", "cck", "headcook");
        add(Rank.COOK, "ck", "2ndcook", "messcook");
        add(Rank.STEWARD, "messman", "stwd", "std", "messboy");
    }

    private RankMatcher() {
    }

    private static void add(Rank rank, String... shortForms) {
        for (String shortForm : shortForms) {
            SHORT_FORMS.put(shortForm, rank);
        }
    }

    @Override
    public String exact(String raw) {
        String value = ImportNames.compact(raw);
        return Arrays.stream(Rank.values()).filter(rank -> compact(rank).equals(value)).findFirst().map(Rank::name)
                .orElse(null);
    }

    @Override
    public String suggest(String raw) {
        String value = ImportNames.compact(raw);
        Rank rank = SHORT_FORMS.get(value);
        if (rank != null) {
            return rank.name();
        }
        if (value.length() < 4) {
            return null;
        }
        // "Chief Eng" -> Chief Engineer, "Master Mariner" -> Master; only when one rank fits.
        List<Rank> fits = Arrays.stream(Rank.values())
                .filter(r -> compact(r).startsWith(value) || value.startsWith(compact(r))).toList();
        return fits.size() == 1 ? fits.getFirst().name() : null;
    }

    @Override
    public List<ImportField.ChoiceGroup> choices() {
        return Rank.groups().stream()
                .map(group -> new ImportField.ChoiceGroup("rank.group." + group.department(),
                        group.ranks().stream().map(r -> new ImportField.Choice(r.name(), "rank." + r)).toList()))
                .toList();
    }

    private static String compact(Rank rank) {
        return rank.name().replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }
}
