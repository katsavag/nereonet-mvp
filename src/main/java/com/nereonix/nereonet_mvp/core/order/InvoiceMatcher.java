package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Kind;

/**
 * Matching the lines read from a supplier's invoice to the order, pure Java. A wrong automatic match is the
 * costly error, so it is strict: when in doubt CHECK rather than MATCHED, and PICK rather than CHECK.
 *
 * Names are normalised (case, accents, punctuation; plural "s" dropped) into words; pack sizes such as 500G, 10KG,
 * 5 LT or 6X1.5LT are kept apart (Pack) and do not count as words. A candidate (an order line's or an item's name)
 * scores by the share of its words found on the invoice line, weighed by the share of the line's words it explains:
 * score = covered * (0.5 + 0.5 * explained). The best candidate is
 * <ul>
 *   <li>MATCHED when every word of its name is on the line, its score is at least MATCH_MIN and every other candidate
 *       is MATCH_LEAD behind it (or is a shorter name inside it: "Tomato" against "Tomato paste");</li>
 *   <li>CHECK when its score is at least CHECK_MIN and the next is CHECK_LEAD behind (or a shorter name inside it);</li>
 *   <li>otherwise nothing (PICK): a new item's row.</li>
 * </ul>
 * conversion works out how many of the item's unit one invoice unit holds (a 10 kg bag for an item in kg: 10).
 * productName and newItemPack say what a line matched to nothing is as a new item: its name without the pack size, and
 * the pack as its conversion.
 */
public final class InvoiceMatcher {

    /** MATCHED: at least this score, every word of the name on the line, and this far ahead of every other. */
    public static final double MATCH_MIN = 0.6;
    public static final double MATCH_LEAD = 0.25;

    /** CHECK: at least this score, and this far ahead of the next. */
    public static final double CHECK_MIN = 0.4;
    public static final double CHECK_LEAD = 0.1;

    /** A pack factor larger than this is not believed. */
    private static final BigDecimal MAX_FACTOR = new BigDecimal("100000");

    private InvoiceMatcher() {
    }

    /** Something an invoice line may be: an order line or an item, by its id, and its name. */
    public record Candidate(Long id, String name) {
    }

    /** The outcome for one invoice line: MATCHED or CHECK with the candidate's id, or PICK (id null). */
    public record Outcome(InvoiceMatch match, Long id, double score) {

        static final Outcome NONE = new Outcome(InvoiceMatch.PICK, null, 0);

        public boolean found() {
            return id != null;
        }
    }

    /** A pack size read from a description: count × size unit ("6X1.5LT": 6, 1.5, lt; "10KG": 1, 10, kg). */
    public record Pack(int count, BigDecimal size, String unit) {

        /** "10 kg", "6 × 1.5 lt". */
        public String label() {
            return (count > 1 ? count + " × " : "") + plain(size) + " " + unit;
        }

        String token() {
            return (count > 1 ? count + "x" : "") + plain(size) + unit;
        }

        Kind kind() {
            return QuantityRules.kindOf(unit);
        }

        /** The whole pack in the base of its dimension (g or ml). */
        BigDecimal base() {
            return size.multiply(BigDecimal.valueOf(count)).multiply(scale(kind()));
        }
    }

    /** A normalised name: its key (to remember it by), its words (stemmed) and its pack sizes. */
    public record Name(String key, Set<String> words, List<Pack> packs) {
    }

    /**
     * How the invoice's unit converts to the item's: one invoice unit is factor of the item's unit. pack ("10 kg"),
     * unitOne and unitMany ("bag", "bags") name the invoice's unit when it differs; all null when the invoice counts in
     * the item's own unit (factor 1). converted: the factor was worked out (the office reviews it).
     */
    public record Conversion(BigDecimal factor, String pack, String unitOne, String unitMany, boolean converted) {

        static final Conversion SAME = new Conversion(BigDecimal.ONE, null, null, null, false);

        /** The invoice counts in another unit than the item's. */
        public boolean packed() {
            return unitOne != null;
        }
    }

    // ---- Names -----------------------------------------------------------------------------------------------------

    private static final String UNIT = "(kgs?|kgr|grs?|g|ltrs?|lts?|l|ml|cl)";
    // A pack size: a multi pack (6X1.5LT: groups 1-3) or a single one (10KG: groups 4-5).
    private static final String PACK_SIZE =
            "(?<![\\p{L}\\d.,])(\\d{1,3})\\s*[x×*]\\s*(\\d+(?:[.,]\\d+)?)\\s*" + UNIT + "(?![\\p{L}\\d])"
            + "|(?<![\\p{L}\\d.,])(\\d+(?:[.,]\\d+)?)\\s*" + UNIT + "(?![\\p{L}\\d])";
    // One token at a time: a pack size, or a word.
    private static final Pattern TOKEN = Pattern.compile(PACK_SIZE + "|[\\p{L}\\p{Nd}]+");
    // The pack sizes of a description as it is written (productName).
    private static final Pattern PACK_SIZE_AS_WRITTEN = Pattern.compile(PACK_SIZE,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    // Words that say nothing of the product: joining words and the names of units and packs.
    private static final Set<String> STOP = Set.of("and", "with", "of", "the", "for", "in", "x", "και", "με", "pcs",
            "pc", "pce", "pack", "pk", "pkt", "pkg", "bag", "box", "can", "tin", "btl", "bottle", "ctn", "carton",
            "kg", "kgs", "gr", "lt", "ltr", "ml", "approx", "per", "each", "ea");

    // Units that mean "one piece".
    private static final Set<String> PIECES = Set.of("pcs", "pc", "pce", "pces", "piece", "pieces", "ea", "each",
            "unit", "units", "nos", "no", "τεμ", "τεμαχιο", "τεμαχια", "τμχ");

    // The invoice's pack units as the count says them ("Invoice: 12 bags").
    private static final List<String[]> NOUNS = List.of(
            new String[] {"bag", "bag", "bags", "sack", "sacks"},
            new String[] {"pack", "pack", "packs", "pk", "pkt", "pkts", "pkg", "pkgs", "pcs", "pc", "pce", "piece",
                    "pieces", "ea", "each", "unit", "units", "nos", "no", "τεμ", "τμχ"},
            new String[] {"can", "can", "cans", "tin", "tins"},
            new String[] {"box", "box", "boxes", "bx"},
            new String[] {"bottle", "bottle", "bottles", "btl", "btls"},
            new String[] {"carton", "carton", "cartons", "ctn", "ctns"},
            new String[] {"case", "case", "cases", "cs"},
            new String[] {"jar", "jar", "jars"},
            new String[] {"tray", "tray", "trays"},
            new String[] {"bucket", "bucket", "buckets"});

    /** The normalised name of a description or a product. */
    public static Name name(String text) {
        List<Pack> packs = new ArrayList<>();
        Set<String> words = new LinkedHashSet<>();
        StringBuilder key = new StringBuilder();
        Matcher m = TOKEN.matcher(fold(text));
        while (m.find()) {
            String token;
            if (m.group(1) != null || m.group(4) != null) {
                Pack pack = m.group(1) != null ? pack(Integer.parseInt(m.group(1)), m.group(2), m.group(3))
                        : pack(1, m.group(4), m.group(5));
                packs.add(pack);
                token = pack.token();
            } else {
                token = m.group();
                if (token.length() >= 2 && !STOP.contains(token) && token.chars().anyMatch(Character::isLetter)) {
                    words.add(stem(token));
                }
            }
            key.append(key.isEmpty() ? "" : " ").append(token);
        }
        String k = key.toString();
        return new Name(k.length() > 300 ? k.substring(0, 300) : k, words, packs);
    }

    /**
     * The product of a description, the name of a new item: the description without its pack sizes, in sentence case
     * ("BLACK PEPPER 500G": "Black pepper"; "WATER 6X1.5LT": "Water"; "TEA BAGS 100S", not a weight or a volume,
     * stays "Tea bags 100s"). The whole description when nothing is left without the pack.
     */
    public static String productName(String description) {
        String text = description == null ? "" : description.strip();
        String product = PACK_SIZE_AS_WRITTEN.matcher(text).replaceAll(" ").replaceAll("\\(\\s*\\)", " ")
                .replaceAll("\\s+", " ").replaceAll(" ([,;])", "$1").replaceAll("^[\\s,;:/*-]+|[\\s,;:/*-]+$", "");
        if (product.chars().noneMatch(Character::isLetterOrDigit)) {
            product = text;
        }
        return product.isEmpty() ? product
                : product.substring(0, 1).toUpperCase(Locale.ROOT) + product.substring(1).toLowerCase(Locale.ROOT);
    }

    /** A description as Claude is given it: the product, then its pack sizes ("Black pepper, 500 g"). */
    public static String productAndPack(String description) {
        StringBuilder text = new StringBuilder(productName(description));
        name(description).packs().forEach(pack -> text.append(", ").append(pack.label()));
        return text.toString();
    }

    /** The key a description is remembered by (SupplierItemName.description). */
    public static String key(String description) {
        return name(description).key();
    }

    // Lower case, no accents, Greek final sigma as any other.
    static String fold(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace('ς', 'σ');
    }

    private static Pack pack(int count, String size, String unit) {
        BigDecimal value = new BigDecimal(size.replace(',', '.'));
        String u = switch (unit) {
            case "kg", "kgs", "kgr" -> "kg";
            case "g", "gr", "grs" -> "g";
            case "ml" -> "ml";
            case "cl" -> "cl";
            default -> "lt";
        };
        if (u.equals("cl")) {
            value = value.multiply(BigDecimal.TEN);
            u = "ml";
        }
        return new Pack(Math.max(count, 1), value, u);
    }

    // tomatoes -> tomato, cherries -> cherry, eggs -> egg; "glass" stays.
    static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 4 && word.endsWith("oes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    // ---- Matching --------------------------------------------------------------------------------------------------

    /** The score of a candidate's words against an invoice line's: 0 (nothing shared) to 1 (the same words). */
    static double score(Set<String> line, Set<String> candidate) {
        if (line.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        long shared = candidate.stream().filter(line::contains).count();
        double covered = (double) shared / candidate.size();
        double explained = (double) shared / line.size();
        return covered * (0.5 + 0.5 * explained);
    }

    /** The best candidate for an invoice line's description: MATCHED, CHECK, or none (PICK). */
    public static Outcome best(String description, List<Candidate> candidates) {
        Name line = name(description);
        if (line.words().isEmpty() || candidates.isEmpty()) {
            return Outcome.NONE;
        }
        record Scored(Candidate candidate, Set<String> words, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Set<String> words = name(candidate.name()).words();
            scored.add(new Scored(candidate, words, score(line.words(), words)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        Scored best = scored.getFirst();
        if (best.score() <= 0) {
            return Outcome.NONE;
        }
        boolean whole = line.words().containsAll(best.words());
        // Is every other candidate within this lead a shorter name inside the best one ("Tomato" in "Tomato paste")?
        java.util.function.DoublePredicate clear = lead -> scored.stream().skip(1)
                .filter(other -> other.score() > best.score() - lead)
                .allMatch(other -> whole && best.words().containsAll(other.words())
                        && best.words().size() > other.words().size());
        if (best.score() >= MATCH_MIN && whole && clear.test(MATCH_LEAD)) {
            return new Outcome(InvoiceMatch.MATCHED, best.candidate().id(), best.score());
        }
        if (best.score() >= CHECK_MIN && clear.test(CHECK_LEAD)) {
            return new Outcome(InvoiceMatch.CHECK, best.candidate().id(), best.score());
        }
        return Outcome.NONE;
    }

    /**
     * The supplier an invoice's supplier name is, among the candidates (the suppliers' ids and names): the same words
     * once the company form is left out ("Kestrel Ship Supply S.A." is "Kestrel Ship Supply"), or all the words of a
     * name of two words or more. Null when none, or more than one, is.
     */
    public static Long supplier(String read, List<Candidate> suppliers) {
        Set<String> words = companyWords(read);
        if (words.isEmpty()) {
            return null;
        }
        Long found = null;
        for (Candidate supplier : suppliers) {
            Set<String> other = companyWords(supplier.name());
            boolean same = other.equals(words)
                    || (other.size() >= 2 && words.containsAll(other))
                    || (words.size() >= 2 && other.containsAll(words));
            if (same && !other.isEmpty()) {
                if (found != null) {
                    return null;
                }
                found = supplier.id();
            }
        }
        return found;
    }

    private static final Set<String> COMPANY_FORMS = Set.of("sa", "ae", "ltd", "limited", "llc", "inc", "gmbh", "bv",
            "nv", "srl", "spa", "ike", "oe", "ee", "co", "company", "corp", "plc", "ag", "as", "ab", "oy", "epe");

    private static Set<String> companyWords(String name) {
        Set<String> words = new LinkedHashSet<>();
        // S.A., Ε.Π.Ε.: the dots go first, so the letters make one word.
        for (String token : NOT_WORD.split(fold(name).replace(".", ""))) {
            if (token.length() >= 2 && !COMPANY_FORMS.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    /** The normalised key of a supplier's name, for a supplier not in the list (SupplierItemName.supplierName). */
    public static String supplierKey(String name) {
        return String.join(" ", companyWords(name));
    }

    // ---- Units -----------------------------------------------------------------------------------------------------

    /**
     * How one unit of the invoice converts to the item's unit, or null when it cannot be worked out (then the row is
     * counted in the item's own unit, without an invoice quantity).
     * <ul>
     *   <li>The same unit (KG for kg, PCS for pcs), or no unit on the invoice and no pack size in the description:
     *       factor 1, nothing converted.</li>
     *   <li>Another unit of the same measure (G for kg): its factor (0.001).</li>
     *   <li>A pack (BAG, PCS, CAN...) of an item weighed or measured: the pack size of the description ("RICE 10KG,
     *       BAG" for kg: 10; "WATER 6X1.5LT, PACK" for lt: 9; "SUGAR 1KG, PCS": 1).</li>
     *   <li>A pack of an item counted in another unit: the number in a multi pack ("6X1.5LT, PACK" for bottles: 6).</li>
     * </ul>
     */
    public static Conversion conversion(String description, String invoiceUnit, String itemUnit) {
        String invoice = unit(invoiceUnit);
        String item = unit(itemUnit);
        List<Pack> packs = name(description).packs();
        if (invoice.isEmpty()) {
            return packs.isEmpty() ? Conversion.SAME : null;
        }
        if (invoice.equals(item) || (PIECES.contains(invoice) && PIECES.contains(item))) {
            return Conversion.SAME;
        }
        Kind invoiceKind = QuantityRules.kindOf(invoice);
        Kind itemKind = QuantityRules.kindOf(item);
        if (measured(invoiceKind)) {
            if (!measured(itemKind) || dimension(invoiceKind) != dimension(itemKind)) {
                return null;
            }
            BigDecimal factor = scale(invoiceKind).divide(scale(itemKind), 6, RoundingMode.HALF_UP).stripTrailingZeros();
            return factor.compareTo(BigDecimal.ONE) == 0 ? Conversion.SAME
                    : new Conversion(factor, null, invoice, invoice, true);
        }
        String[] noun = noun(invoice);
        if (measured(itemKind)) {
            for (Pack pack : packs) {
                if (dimension(pack.kind()) == dimension(itemKind)) {
                    BigDecimal factor = pack.base().divide(scale(itemKind), 6, RoundingMode.HALF_UP).stripTrailingZeros();
                    return believable(factor) ? new Conversion(factor, pack.label(), noun[0], noun[1], true) : null;
                }
            }
            return null;
        }
        for (Pack pack : packs) {
            if (pack.count() > 1) {
                return new Conversion(BigDecimal.valueOf(pack.count()), pack.label(), noun[0], noun[1], true);
            }
        }
        return null;
    }

    /** A new item counted by the pack: its unit (the measure: "kg" or "lt") and how one invoice unit converts to it. */
    public record NewItemPack(String itemUnit, Conversion conversion) {
    }

    /**
     * How a new item is counted when its invoice line counts packs (PCS, CAN, BOX...) and the description gives the
     * weight or the volume of one: the item is kept in kg or lt and one invoice unit is the pack ("BLACK PEPPER 500G",
     * PCS: kg, 0.5, "500 g", counted in pcs; "WATER 6X1.5LT", PACK: lt, 9, counted in packs). Null when the invoice
     * counts in a measure, has no unit, or the description has no pack size: the item is then in the invoice's unit.
     */
    public static NewItemPack newItemPack(String description, String invoiceUnit) {
        String invoice = unit(invoiceUnit);
        List<Pack> packs = name(description).packs();
        if (invoice.isEmpty() || measured(QuantityRules.kindOf(invoice)) || packs.isEmpty()) {
            return null;
        }
        String itemUnit = dimension(packs.getFirst().kind()) == 0 ? "kg" : "lt";
        Conversion conversion = conversion(description, invoiceUnit, itemUnit);
        if (conversion == null) {
            return null;
        }
        // Pieces stay "pcs", as the invoice counts them (a matched line says "pack").
        return new NewItemPack(itemUnit, !PIECES.contains(invoice) ? conversion
                : new Conversion(conversion.factor(), conversion.pack(), "pcs", "pcs", true));
    }

    /**
     * The conversion of a description matched before (SupplierItemName): its factor as learned (null: as worked out,
     * or none when it cannot be), never marked for review again.
     */
    public static Conversion learned(String description, String invoiceUnit, String itemUnit, BigDecimal factor) {
        Conversion worked = conversion(description, invoiceUnit, itemUnit);
        if (factor == null || (worked != null && worked.factor().compareTo(factor) == 0)) {
            return worked == null ? null
                    : new Conversion(worked.factor(), worked.pack(), worked.unitOne(), worked.unitMany(), false);
        }
        String invoice = unit(invoiceUnit);
        if (invoice.isEmpty() || !believable(factor)) {
            return null;
        }
        String[] noun = measured(QuantityRules.kindOf(invoice)) ? new String[] {invoice, invoice} : noun(invoice);
        return new Conversion(factor.stripTrailingZeros(), worked != null ? worked.pack() : null, noun[0], noun[1],
                false);
    }

    private static boolean believable(BigDecimal factor) {
        return factor.signum() > 0 && factor.compareTo(MAX_FACTOR) <= 0;
    }

    private static String unit(String unit) {
        return fold(QuantityRules.normalize(unit));
    }

    // The singular and plural the count says ("bag", "bags"); an unknown unit as written ("crate", "crates").
    private static String[] noun(String unit) {
        for (String[] noun : NOUNS) {
            for (int i = 1; i < noun.length; i++) {
                if (noun[i].equals(unit)) {
                    return new String[] {noun[1], noun[2]};
                }
            }
        }
        if (unit.endsWith("s")) {
            return new String[] {unit, unit};
        }
        return new String[] {unit, unit.endsWith("x") || unit.endsWith("ch") || unit.endsWith("sh") ? unit + "es"
                : unit + "s"};
    }

    private static boolean measured(Kind kind) {
        return kind != Kind.COUNTABLE;
    }

    // 0: mass (kg, g); 1: volume (lt, ml); 2: countable.
    private static int dimension(Kind kind) {
        return switch (kind) {
            case KG, G -> 0;
            case LT, ML -> 1;
            case COUNTABLE -> 2;
        };
    }

    private static BigDecimal scale(Kind kind) {
        return switch (kind) {
            case KG, LT -> new BigDecimal(1000);
            default -> BigDecimal.ONE;
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
