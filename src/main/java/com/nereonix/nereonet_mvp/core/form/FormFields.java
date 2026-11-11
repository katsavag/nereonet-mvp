package com.nereonix.nereonet_mvp.core.form;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.util.DetailedError;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import jakarta.validation.metadata.PropertyDescriptor;

/**
 * Helpers for the form templates (P05), used as {@code @formFields} in Thymeleaf:
 * the character limit of a field, read from its {@code @Size(max)} in the form class (the counter under the field),
 * and the field id and order of each error (the error summary at the top of a form, layout/parts :: errorSummary).
 */
@Component("formFields")
public class FormFields {

    // Checks that are a method of the form rather than a field, and the field their error belongs to.
    private static final Map<String, String> FIELD_OF_CHECK = Map.of(
            "imoNumberValid", "imoNumber",
            "flagValid", "flag",
            "passwordConfirmed", "confirmPassword",
            "contentValid", "text",
            "fileTypeAllowed", "file",
            "fileSizeAllowed", "file");

    private final Validator validator;

    public FormFields(Validator validator) {
        this.validator = validator;
    }

    /** The {@code @Size(max)} of the property in the form's class, or null when it has none. */
    public Integer maxLength(Object form, String property) {
        PropertyDescriptor descriptor = validator.getConstraintsForClass(form.getClass())
                .getConstraintsForProperty(property);
        if (descriptor == null) {
            return null;
        }
        return descriptor.getConstraintDescriptors().stream()
                .filter(constraint -> constraint.getAnnotation() instanceof Size)
                .map(constraint -> ((Size) constraint.getAnnotation()).max())
                .filter(max -> max < Integer.MAX_VALUE)
                .min(Integer::compare)
                .orElse(null);
    }

    /**
     * The id of the field an error belongs to: the field name (as th:field writes it), or the field a check method
     * reports on, e.g. imoNumberValid -> imoNumber. prefix: for forms whose ids carry one, e.g. "edit" -> editName.
     */
    public String id(String property, String prefix) {
        String field = FIELD_OF_CHECK.getOrDefault(property, property);
        if (prefix == null || prefix.isEmpty()) {
            return field;
        }
        return prefix + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /**
     * The errors in the order of the fields in the form class, which is the order of the fields on the page;
     * errors not tied to a field first. Bean Validation reports them in no particular order.
     */
    public List<DetailedError> inFormOrder(Object form, List<DetailedError> errors) {
        List<String> fields = new ArrayList<>();
        for (Class<?> type = form.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                fields.add(field.getName());
            }
        }
        Comparator<DetailedError> byField = Comparator.comparingInt(error -> {
            if (error.isGlobal()) {
                return -1;
            }
            int index = fields.indexOf(FIELD_OF_CHECK.getOrDefault(error.getFieldName(), error.getFieldName()));
            return index < 0 ? fields.size() : index;
        });
        return errors.stream().sorted(byField).toList();
    }
}
