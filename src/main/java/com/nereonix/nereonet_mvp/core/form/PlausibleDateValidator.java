package com.nereonix.nereonet_mvp.core.form;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;
import org.springframework.context.i18n.LocaleContextHolder;

import com.nereonix.nereonet_mvp.core.time.Dates;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link PlausibleDate}: the date is within the limits, counted from today (UTC). */
public class PlausibleDateValidator implements ConstraintValidator<PlausibleDate, LocalDate> {

    private PlausibleDate limits;

    @Override
    public void initialize(PlausibleDate limits) {
        this.limits = limits;
    }

    @Override
    public boolean isValid(LocalDate date, ConstraintValidatorContext context) {
        if (date == null) {
            return true;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate earliest = earliest(limits, today);
        LocalDate latest = latest(limits, today);
        if (!date.isBefore(earliest) && !date.isAfter(latest)) {
            return true;
        }
        // The limits in the message, in the app's date format and the page language.
        DateTimeFormatter format = DateTimeFormatter.ofPattern(Dates.DATE_PATTERN, LocaleContextHolder.getLocale());
        context.unwrap(HibernateConstraintValidatorContext.class)
                .addMessageParameter("earliest", format.format(earliest))
                .addMessageParameter("latest", format.format(latest));
        return false;
    }

    /** The earliest date these limits accept. */
    public static LocalDate earliest(PlausibleDate limits, LocalDate today) {
        return today.minusYears(limits.yearsBefore());
    }

    /** The latest date these limits accept. */
    public static LocalDate latest(PlausibleDate limits, LocalDate today) {
        return today.plusYears(limits.yearsAfter());
    }
}
