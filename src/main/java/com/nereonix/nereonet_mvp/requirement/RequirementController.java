package com.nereonix.nereonet_mvp.requirement;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// Required documents (P42), Administration in the sidebar: which certificate types each rank needs, on all vessels or
// on one vessel type. P43: the combined filter (?vesselType=all, TANKER, ... or ship-12: that vessel's type), a row
// toggle per type, and Delete row, which asks first (P10) and removes the type's requirement for the chosen vessel
// type only. A plain form of checkboxes, so it saves without JavaScript; Save comes back to the same selection with
// "Required documents saved". ?add= shows a new type as the first row.
@Controller
public class RequirementController {

    static final String PATH = "/office/requirements";
    private static final Pattern NOTE_FIELD = Pattern.compile("rows\\[(\\d{1,4})]\\.note");

    private final RequirementService requirementService;
    private final ConfirmPages confirmPages;

    public RequirementController(RequirementService requirementService, ConfirmPages confirmPages) {
        this.requirementService = requirementService;
        this.confirmPages = confirmPages;
    }

    @GetMapping(PATH)
    public String grid(@RequestParam(required = false) String vesselType, @RequestParam(required = false) String add,
                       Model model) {
        RequirementSelection selection = requirementService.select(vesselType, LocaleContextHolder.getLocale());
        return show(selection, requirementService.grid(selection.type(), add), model);
    }

    @PostMapping(PATH)
    public String save(@Valid @ModelAttribute("form") RequirementGridForm form, BindingResult result, Model model,
                       RedirectAttributes redirectAttributes) {
        ShipType type = RequirementService.vesselType(form.getVesselType());
        if (result.hasErrors()) {
            // The rows whose note is too long, by type, for the message under each.
            Map<String, String> noteErrors = new HashMap<>();
            for (FieldError error : result.getFieldErrors()) {
                Matcher row = NOTE_FIELD.matcher(error.getField());
                if (row.matches() && Integer.parseInt(row.group(1)) < form.getRows().size()) {
                    String rowType = form.getRows().get(Integer.parseInt(row.group(1))).getType();
                    noteErrors.put(rowType == null ? "" : CertificateTypes.sameTypeKey(rowType), error.getDefaultMessage());
                }
            }
            model.addAttribute("noteErrors", noteErrors);
            model.addAttribute("errorMessage", "requirement.error.notSaved");
            RequirementSelection selection = requirementService.select(selectionOf(form.getSelection(), type),
                    LocaleContextHolder.getLocale());
            return show(selection, requirementService.grid(type, form), model);
        }
        requirementService.save(type, form);
        redirectAttributes.addFlashAttribute("successMessage", "requirement.saved");
        return "redirect:" + PATH + "?vesselType=" + selectionOf(form.getSelection(), type);
    }

    // The confirmation page: where Delete row leads when js/confirm.js did not load (P10).
    @GetMapping(PATH + "/delete")
    public String confirmDelete(@RequestParam String type, @RequestParam(required = false) String vesselType,
                                @RequestParam(required = false) String selection, Model model) {
        RequirementSelection chosen = requirementService.select(selection == null ? vesselType : selection,
                LocaleContextHolder.getLocale());
        return confirmPages.danger()
                .title("requirement.row.delete.confirm.title", type)
                .body(chosen.type() == null ? "requirement.row.delete.confirm.body.all" : "requirement.row.delete.confirm.body.type",
                        type, typeLabel(chosen.type()))
                .confirmLabel("requirement.row.delete.confirm.submit")
                .action(PATH + "/delete", null)
                .fields(Map.of("type", type, "vesselType", chosen.typeKey(), "selection", chosen.key()))
                .cancelUrl(PATH + "?vesselType=" + chosen.key())
                .crumb("requirement.title", PATH)
                .show(model);
    }

    @PostMapping(PATH + "/delete")
    public String delete(@RequestParam String type, @RequestParam(required = false) String vesselType,
                         @RequestParam(required = false) String selection, RedirectAttributes redirectAttributes) {
        ShipType shipType = RequirementService.vesselType(vesselType);
        if (requirementService.delete(shipType, type)) {
            redirectAttributes.addFlashAttribute("successMessage", "requirement.row.removed");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {type});
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "requirement.row.nothing");
        }
        return "redirect:" + PATH + "?vesselType=" + selectionOf(selection, shipType);
    }

    // The selection to come back to: the one the form had, else the vessel type.
    private static String selectionOf(String selection, ShipType type) {
        return selection == null || selection.isBlank() ? RequirementService.key(type) : selection;
    }

    private String typeLabel(ShipType type) {
        return type == null ? "" : requirementService.label("ship.type." + type.name());
    }

    private static String show(RequirementSelection selection, RequirementGrid grid, Model model) {
        model.addAttribute("grid", grid);
        model.addAttribute("selection", selection);
        if (!model.containsAttribute("noteErrors")) {
            model.addAttribute("noteErrors", Map.of());
        }
        return "requirement/grid";
    }
}
