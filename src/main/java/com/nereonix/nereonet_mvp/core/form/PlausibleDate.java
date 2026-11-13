package com.nereonix.nereonet_mvp.core.form;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A date a person types must be a sensible one (P39): between yearsBefore years before today and yearsAfter years
 * after today (UTC), both days included, so a slip such as 0333 instead of 2033 is refused. Null is valid (use
 * {@code @NotNull} for a required date). The message gives the real limits: "Enter a date between 13 Nov 1996 and
 * 13 Nov 2056." The date input shows the same limits as its min and max ({@code @formFields.minDate},
 * {@code maxDate}), so the browser's picker does not offer other years. The limits of each date are in README.md.
 */
@Documented
@Constraint(validatedBy = PlausibleDateValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlausibleDate {

    /** How many years before today the earliest date is; 0: today. */
    int yearsBefore();

    /** How many years after today the latest date is; 0: today. */
    int yearsAfter();

    String message() default "{form.error.dateRange}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
