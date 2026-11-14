package com.nereonix.nereonet_mvp.core.rfq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Amounts of money on screen and in emails (P44): the currency code, then the amount, "EUR 1,240.50" ("EUR
 * 1.240,50" on a Greek page). Never converted between currencies. Totals have two decimals; a unit price keeps up to
 * four ("EUR 0.4125") so a cheap item per gram still compares. Templates use the "money" bean (@money.total(...));
 * emails, always English, use the static methods.
 */
@Component("money")
public class Money {

    /** "EUR 1,240.50" in the page language. */
    public String total(String currency, BigDecimal amount) {
        return total(currency, amount, LocaleContextHolder.getLocale());
    }

    /** "EUR 2.40", "EUR 0.4125" in the page language. */
    public String price(String currency, BigDecimal amount) {
        return price(currency, amount, LocaleContextHolder.getLocale());
    }

    /** A quantity without needless zeros, "2.5", "1,200" in the page language. */
    public String quantity(BigDecimal amount) {
        return quantity(amount, LocaleContextHolder.getLocale());
    }

    public static String total(String currency, BigDecimal amount, Locale locale) {
        if (amount == null) {
            return "";
        }
        return currency + " " + format("#,##0.00", locale).format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    public static String price(String currency, BigDecimal amount, Locale locale) {
        if (amount == null) {
            return "";
        }
        return currency + " " + format("#,##0.00##", locale).format(amount.setScale(4, RoundingMode.HALF_UP));
    }

    public static String quantity(BigDecimal amount, Locale locale) {
        if (amount == null) {
            return "";
        }
        return format("#,##0.###", locale).format(amount);
    }

    private static DecimalFormat format(String pattern, Locale locale) {
        boolean greek = locale != null && "el".equals(locale.getLanguage());
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(greek ? '.' : ',');
        symbols.setDecimalSeparator(greek ? ',' : '.');
        DecimalFormat format = new DecimalFormat(pattern, symbols);
        format.setParseBigDecimal(true);
        return format;
    }
}
