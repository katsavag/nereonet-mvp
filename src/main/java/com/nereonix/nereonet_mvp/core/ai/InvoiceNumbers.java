package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbers as invoices write them (P62): 1,234.56 and 1.234,56, 12,500 kg, 46.25 €.
 *   - Both '.' and ',': the last one is the decimal separator, the other groups thousands (groups of 3 digits).
 *   - One kind, more than once: thousands (1.234.567).
 *   - One kind, once, not followed by exactly 3 digits: the decimal separator (1,85 and 0.5).
 *   - One kind, once, followed by exactly 3 digits (12,500 or 1,234): the page's decimal separator decides
 *     (decimalSeparator: what most unambiguous numbers of the page use); without one, '.' is a decimal point and ','
 *     groups thousands, unless the whole part is 0 (0,750 is 0.750).
 * Currency (€, EUR, $) and spaces are dropped; a number with any other character in it is not read (null), so "4S,00"
 * is never 4.
 */
public final class InvoiceNumbers {

    private static final Pattern CURRENCY = Pattern.compile("(?i)€|\\$|\\beur\\b|\\beuro\\b|\\busd\\b");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9][0-9.,]*");
    private static final Pattern TOKEN = Pattern.compile("[0-9][0-9.,]*[0-9]|[0-9]");

    private InvoiceNumbers() {
    }

    /** The decimal separator most numbers of these texts use, when they say: '.', ',' or null. */
    public static Character decimalSeparator(Collection<String> texts) {
        int dots = 0;
        int commas = 0;
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = TOKEN.matcher(text);
            while (matcher.find()) {
                Character separator = unambiguousDecimal(matcher.group());
                if (separator == null) {
                    continue;
                }
                if (separator == '.') {
                    dots++;
                } else {
                    commas++;
                }
            }
        }
        return dots == commas ? null : dots > commas ? '.' : ',';
    }

    /** A cell as a number, or null when it is empty or not clearly a number. A unit after it is allowed ("25 kg"). */
    public static BigDecimal parseCell(String cell, Character decimalSeparator) {
        if (cell == null) {
            return null;
        }
        String text = CURRENCY.matcher(cell).replaceAll(" ").replace(' ', ' ').replace(' ', ' ').strip();
        // A unit after the number (letters only, short): "25 kg", "12,5 lt", "25kg"; one letter right after a digit
        // ("4S") is a misread digit, not a unit.
        text = text.replaceFirst("(?:\\s+[\\p{L}.]{1,6}|(?<=\\d)\\p{L}{2,6}\\.?)$", "").strip();
        // Spaces grouping thousands: "1 234,56".
        text = text.replaceAll("(?<=\\d) (?=\\d{3}\\b)", "");
        if (!NUMBER.matcher(text).matches()) {
            return null;
        }
        return parse(text, decimalSeparator);
    }

    /** The last number of a line of text ("VAT 13%: 369,02" is 369.02); null without one. */
    public static BigDecimal lastNumber(String text, Character decimalSeparator) {
        if (text == null) {
            return null;
        }
        String clean = CURRENCY.matcher(text).replaceAll(" ").replaceAll("(?<=\\d) (?=\\d{3}\\b)", "");
        Matcher matcher = Pattern.compile("-?" + TOKEN.pattern()).matcher(clean);
        String last = null;
        int end = -1;
        while (matcher.find()) {
            last = matcher.group();
            end = matcher.end();
        }
        if (last == null || (end < clean.length() && clean.charAt(end) == '%')) {
            return null;
        }
        return parse(last, decimalSeparator);
    }

    // A token of digits, '.' and ',' (and a leading '-').
    static BigDecimal parse(String token, Character decimalSeparator) {
        boolean negative = token.startsWith("-");
        String text = negative ? token.substring(1) : token;
        if (text.isEmpty() || !Character.isDigit(text.charAt(text.length() - 1))) {
            return null;
        }
        int dots = count(text, '.');
        int commas = count(text, ',');
        String plain;
        if (dots > 0 && commas > 0) {
            char decimal = text.lastIndexOf('.') > text.lastIndexOf(',') ? '.' : ',';
            char thousands = decimal == '.' ? ',' : '.';
            if (count(text, decimal) > 1) {
                return null;
            }
            String whole = text.substring(0, text.indexOf(decimal));
            if (!groupsOfThree(whole, thousands)) {
                return null;
            }
            plain = whole.replace(String.valueOf(thousands), "") + "." + text.substring(text.indexOf(decimal) + 1);
        } else if (dots + commas == 0) {
            plain = text;
        } else {
            char separator = dots > 0 ? '.' : ',';
            int at = text.indexOf(separator);
            if (dots + commas > 1) {
                if (!groupsOfThree(text, separator)) {
                    return null;
                }
                plain = text.replace(String.valueOf(separator), "");
            } else {
                String whole = text.substring(0, at);
                String fraction = text.substring(at + 1);
                boolean decimal;
                if (fraction.length() != 3 || whole.isEmpty()) {
                    decimal = true;
                } else if (decimalSeparator != null) {
                    decimal = decimalSeparator == separator;
                } else {
                    decimal = separator == '.' || whole.equals("0");
                }
                plain = decimal ? (whole.isEmpty() ? "0" : whole) + "." + fraction : whole + fraction;
            }
        }
        try {
            BigDecimal value = new BigDecimal(plain);
            return negative ? value.negate() : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // '.' or ',' when this token can only be read one way; null otherwise.
    private static Character unambiguousDecimal(String token) {
        int dots = count(token, '.');
        int commas = count(token, ',');
        if (dots > 0 && commas > 0) {
            return token.lastIndexOf('.') > token.lastIndexOf(',') ? '.' : ',';
        }
        if (dots + commas != 1) {
            return null;
        }
        char separator = dots == 1 ? '.' : ',';
        int fraction = token.length() - token.indexOf(separator) - 1;
        return fraction == 1 || fraction == 2 ? separator : null;
    }

    private static boolean groupsOfThree(String text, char separator) {
        String[] parts = text.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        if (parts[0].isEmpty() || parts[0].length() > 3) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() != 3) {
                return false;
            }
        }
        return true;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
