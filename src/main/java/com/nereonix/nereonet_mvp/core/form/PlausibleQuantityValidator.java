package com.nereonix.nereonet_mvp.core.form;

import java.math.BigDecimal;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Checks {@link PlausibleQuantity} with {@link QuantityRules}. Spring's validator creates it with its beans; the
 * message is resolved here (it has arguments: "Use whole numbers for pcs.") and reported on the quantity property.
 */
public class PlausibleQuantityValidator implements ConstraintValidator<PlausibleQuantity, Object> {

    // No upper limit (limit = false): only the database's 9 whole digits.
    private static final BigDecimal NO_LIMIT = new BigDecimal("999999999.999");

    private final QuantityRules quantityRules;
    private final MessageSource messageSource;
    private PlausibleQuantity annotation;

    public PlausibleQuantityValidator(QuantityRules quantityRules, MessageSource messageSource) {
        this.quantityRules = quantityRules;
        this.messageSource = messageSource;
    }

    @Override
    public void initialize(PlausibleQuantity annotation) {
        this.annotation = annotation;
    }

    @Override
    public boolean isValid(Object form, ConstraintValidatorContext context) {
        if (form == null) {
            return true;
        }
        BeanWrapperImpl bean = new BeanWrapperImpl(form);
        Object text = bean.getPropertyValue(annotation.quantity());
        if (text == null || text.toString().isBlank()) {
            return true;
        }
        Object unit = bean.getPropertyValue(annotation.unit());
        QuantityRules.Rule rule = quantityRules.forUnit(unit == null ? null : unit.toString(), annotation.use());
        if (!annotation.limit()) {
            rule = new QuantityRules.Rule(rule.unit(), rule.kind(), rule.use(), NO_LIMIT);
        }
        QuantityRules.Check check = rule.check(text.toString());
        if (check.ok()) {
            return true;
        }
        String message = messageSource.getMessage(check.message(), LocaleContextHolder.getLocale());
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(escape(message))
                .addPropertyNode(annotation.quantity())
                .addConstraintViolation();
        return false;
    }

    // The resolved text is not a template: { } $ and \ are literal.
    private static String escape(String message) {
        return message.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
    }
}
