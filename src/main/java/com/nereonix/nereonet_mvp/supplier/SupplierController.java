package com.nereonix.nereonet_mvp.supplier;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierService;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The office's suppliers (P44): /office/suppliers, the list (P19: search, status filter, sort, pages; P25 empty
// state), Add supplier and Edit supplier (full pages: six fields). /office/** is OFFICE_USER only (SecurityConfig).
@Controller
public class SupplierController {

    static final String LIST = "/office/suppliers";

    private static final ListSort[] SORTS = {ListSort.asc("name"), ListSort.asc("currency")};

    private final SupplierService supplierService;
    private final UserRepository userRepository;

    public SupplierController(SupplierService supplierService, UserRepository userRepository) {
        this.supplierService = supplierService;
        this.userRepository = userRepository;
    }

    // status: active or inactive; anything else filters nothing.
    @GetMapping(LIST)
    public String list(@RequestParam(required = false) String status, @ModelAttribute ListRequest listRequest,
                       Model model) {
        Boolean active = "active".equals(status) ? Boolean.TRUE : "inactive".equals(status) ? Boolean.FALSE : null;
        ListQuery list = ListQuery.of(LIST, listRequest, SORTS);
        if (active != null) {
            list.filterKey("status", status, "supplier.filter.status", "supplier.status." + status);
        }
        model.addAttribute("list", list);
        model.addAttribute("suppliers", supplierService.find(active, list));
        model.addAttribute("status", active == null ? null : status);
        return "supplier/list";
    }

    @GetMapping(LIST + "/new")
    public String newForm(Model model) {
        return showForm(new SupplierForm(), null, model);
    }

    @PostMapping(LIST)
    public String create(@Validated @ModelAttribute("form") SupplierForm form, BindingResult result,
                         @AuthenticationPrincipal CurrentUser currentUser, Model model,
                         RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showForm(form, null, model);
        }
        try {
            Supplier supplier = supplierService.create(user(currentUser), form.details());
            redirectAttributes.addFlashAttribute("successMessage", "supplier.added");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {supplier.getName()});
        } catch (SupplierService.DuplicateNameException e) {
            result.rejectValue("name", "supplier.error.nameTaken");
            return showForm(form, null, model);
        }
        return "redirect:" + LIST;
    }

    @GetMapping(LIST + "/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Supplier supplier = supplier(id);
        return showForm(SupplierForm.of(supplier), supplier, model);
    }

    @PostMapping(LIST + "/{id}")
    public String update(@PathVariable Long id, @Validated @ModelAttribute("form") SupplierForm form,
                         BindingResult result, @AuthenticationPrincipal CurrentUser currentUser, Model model,
                         RedirectAttributes redirectAttributes) {
        Supplier supplier = supplier(id);
        if (result.hasErrors()) {
            return showForm(form, supplier, model);
        }
        try {
            supplierService.update(user(currentUser), supplier, form.details());
            redirectAttributes.addFlashAttribute("successMessage", "supplier.saved");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {supplier.getName()});
        } catch (SupplierService.DuplicateNameException e) {
            result.rejectValue("name", "supplier.error.nameTaken");
            return showForm(form, supplier, model);
        }
        return "redirect:" + LIST;
    }

    private String showForm(SupplierForm form, Supplier supplier, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("supplier", supplier);
        return "supplier/form";
    }

    private Supplier supplier(Long id) {
        return supplierService.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }
}
