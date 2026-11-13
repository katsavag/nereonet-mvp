package com.nereonix.nereonet_mvp.requirement;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.nereonix.nereonet_mvp.core.ship.ShipType;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// Required documents (P42), Administration in the sidebar: which certificate types each rank needs, on all vessels or
// on one vessel type (?vesselType=all, TANKER, ...). A plain form of checkboxes, so it saves without JavaScript; Save
// comes back to the same vessel type with "Required documents saved". ?add= shows a new type as the first row.
@Controller
public class RequirementController {

    private static final Pattern NOTE_FIELD = Pattern.compile("rows\\[(\\d{1,4})]\\.note");

    private final RequirementService requirementService;

    public RequirementController(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    @GetMapping("/office/requirements")
    public String grid(@RequestParam(required = false) String vesselType, @RequestParam(required = false) String add,
                       Model model) {
        ShipType type = RequirementService.vesselType(vesselType);
        return show(requirementService.grid(type, add), model);
    }

    @PostMapping("/office/requirements")
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
            return show(requirementService.grid(type, form), model);
        }
        requirementService.save(type, form);
        redirectAttributes.addFlashAttribute("successMessage", "requirement.saved");
        return "redirect:/office/requirements?vesselType=" + RequirementService.key(type);
    }

    private static String show(RequirementGrid grid, Model model) {
        model.addAttribute("grid", grid);
        if (!model.containsAttribute("noteErrors")) {
            model.addAttribute("noteErrors", Map.of());
        }
        model.addAttribute("vesselTypes", RequirementService.vesselTypeKeys());
        return "requirement/grid";
    }
}
