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
import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// Required documents, Administration in the sidebar: which certificate types each rank needs. P58: per vessel
// (?vessel=12; the first active vessel by name without one), each starting with its type's defaults. The row toggle
// per type, Delete row and "Reset to Bulk carrier defaults" act on the shown vessel only and ask first.
// A plain form of checkboxes, so it saves without JavaScript; Save comes back to the same vessel with "Required
// documents saved". ?add= shows a new type as the first row. A post for an unknown or inactive vessel changes nothing.
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
    public String grid(@RequestParam(required = false) String vessel, @RequestParam(required = false) String add,
                       Model model) {
        RequirementSelection selection = requirementService.select(vessel, LocaleContextHolder.getLocale());
        return show(selection, requirementService.grid(selection.ship(), add), model);
    }

    @PostMapping(PATH)
    public String save(@Valid @ModelAttribute("form") RequirementGridForm form, BindingResult result, Model model,
                       RedirectAttributes redirectAttributes) {
        Ship ship = requirementService.activeVessel(form.getVessel());
        if (ship == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "requirement.error.noVessel");
            return "redirect:" + PATH;
        }
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
            RequirementSelection selection = requirementService.select(form.getVessel(), LocaleContextHolder.getLocale());
            return show(selection, requirementService.grid(ship, form), model);
        }
        requirementService.save(ship, form);
        redirectAttributes.addFlashAttribute("successMessage", "requirement.saved");
        return redirect(ship);
    }

    // The confirmation page: where Delete row leads when js/confirm.js did not load.
    @GetMapping(PATH + "/delete")
    public String confirmDelete(@RequestParam String type, @RequestParam(required = false) String vessel, Model model) {
        Ship ship = requirementService.select(vessel, LocaleContextHolder.getLocale()).ship();
        if (ship == null) {
            return "redirect:" + PATH;
        }
        return confirmPages.danger()
                .title("requirement.row.delete.confirm.title", type)
                .body("requirement.row.delete.confirm.body", type, ship.getName())
                .confirmLabel("requirement.row.delete.confirm.submit")
                .action(PATH + "/delete", null)
                .fields(Map.of("type", type, "vessel", String.valueOf(ship.getId())))
                .cancelUrl(PATH + "?vessel=" + ship.getId())
                .crumb("requirement.title", PATH)
                .show(model);
    }

    @PostMapping(PATH + "/delete")
    public String delete(@RequestParam String type, @RequestParam(required = false) String vessel,
                         RedirectAttributes redirectAttributes) {
        Ship ship = requirementService.activeVessel(vessel);
        if (ship != null && requirementService.delete(ship, type)) {
            redirectAttributes.addFlashAttribute("successMessage", "requirement.row.removed");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {type, ship.getName()});
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "requirement.row.nothing");
        }
        return ship == null ? "redirect:" + PATH : redirect(ship);
    }

    // The confirmation page of "Reset to Bulk carrier defaults" without js/confirm.js, listing what will change.
    @GetMapping(PATH + "/reset")
    public String confirmReset(@RequestParam(required = false) String vessel, Model model) {
        Ship ship = requirementService.select(vessel, LocaleContextHolder.getLocale()).ship();
        if (ship == null) {
            return "redirect:" + PATH;
        }
        String defaults = requirementService.defaultsName(ship);
        var page = confirmPages.danger();
        if (defaults == null) {
            page.title("requirement.reset.confirm.title.none", ship.getName());
        } else {
            page.title("requirement.reset.confirm.title", ship.getName(), defaults);
        }
        var changes = requirementService.resetChanges(ship);
        return page.body(changes.isEmpty() ? "requirement.reset.confirm.body.same" : "requirement.reset.confirm.body",
                        ship.getName())
                .items(changes)
                .confirmLabel("requirement.reset.confirm.submit")
                .action(PATH + "/reset", null)
                .fields(Map.of("vessel", String.valueOf(ship.getId())))
                .cancelUrl(PATH + "?vessel=" + ship.getId())
                .crumb("requirement.title", PATH)
                .show(model);
    }

    @PostMapping(PATH + "/reset")
    public String reset(@RequestParam(required = false) String vessel, RedirectAttributes redirectAttributes) {
        Ship ship = requirementService.activeVessel(vessel);
        if (ship == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "requirement.error.noVessel");
            return "redirect:" + PATH;
        }
        requirementService.reset(ship);
        String defaults = requirementService.defaultsName(ship);
        redirectAttributes.addFlashAttribute("successMessage",
                defaults == null ? "requirement.reset.done.none" : "requirement.reset.done");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {ship.getName(), defaults});
        return redirect(ship);
    }

    private static String redirect(Ship ship) {
        return "redirect:" + PATH + "?vessel=" + ship.getId();
    }

    private String show(RequirementSelection selection, RequirementGrid grid, Model model) {
        model.addAttribute("grid", grid);
        model.addAttribute("selection", selection);
        model.addAttribute("defaultsName", requirementService.defaultsName(selection.ship()));
        if (!model.containsAttribute("noteErrors")) {
            model.addAttribute("noteErrors", Map.of());
        }
        return "requirement/grid";
    }
}
