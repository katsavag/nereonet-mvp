package com.nereonix.nereonet_mvp.officerequest;

import java.io.IOException;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class OfficeRequestController {

    private final OfficeRequestService requestService;

    public OfficeRequestController(OfficeRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping("/office/requests")
    public String list(@RequestParam(required = false) Long shipId,
                       @RequestParam(defaultValue = "false") boolean notExported, Model model) {
        model.addAttribute("requests", requestService.findApproved(shipId, notExported));
        model.addAttribute("ships", requestService.findAllShips());
        model.addAttribute("shipId", shipId);
        model.addAttribute("notExported", notExported);
        return "officerequest/list";
    }

    @GetMapping("/office/requests/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("request", requestService.getApproved(id));
        return "officerequest/detail";
    }

    // Writes the CSV download (then returns null: the response is complete),
    // or goes back to the list when nothing was checked.
    @PostMapping("/office/requests/export")
    public String export(@Valid RequestExportForm form, BindingResult result, HttpServletResponse response,
                         RedirectAttributes redirectAttributes) throws IOException {
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "request.export.noneSelected");
            return "redirect:/office/requests";
        }
        OfficeRequestService.CsvFile csv = requestService.export(form.ids());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(csv.filename()).build().toString());
        response.getOutputStream().write(csv.content());
        return null;
    }
}
