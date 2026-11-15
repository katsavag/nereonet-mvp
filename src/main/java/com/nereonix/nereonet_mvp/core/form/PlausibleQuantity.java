package com.nereonix.nereonet_mvp.core.form;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A quantity typed as text must follow the rule of its unit (P60, {@link QuantityRules}): on a form class, quantity
 * names the text property and unit the property holding the unit, both of the same form. Blank is valid (use
 * {@code @NotBlank} for a required one). The error goes on the quantity property, e.g. "Use whole numbers for pcs.".
 * Used by the item form's "Maximum per request", which has no upper limit of its own (limit = false).
 */
@Documented
@Constraint(validatedBy = PlausibleQuantityValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlausibleQuantity {

    /** The text property with the quantity. */
    String quantity();

    /** The property with the unit. */
    String unit();

    /** Where the quantity is typed (its minimum and maximum). */
    QuantityRules.Use use() default QuantityRules.Use.REQUEST;

    /** False: only the number, the minimum and the decimals are checked, not the maximum. */
    boolean limit() default true;

    String message() default "{quantity.error.number}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
