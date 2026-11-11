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

import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The ship page's Requests tab lists one ship's approved requests, with the same "Not exported yet" filter and export.
@Controller
public class OfficeRequestController {

    private final OfficeRequestService requestService;
    private final ShipFrame shipFrame;

    public OfficeRequestController(OfficeRequestService requestService, ShipFrame shipFrame) {
        this.requestService = requestService;
        this.shipFrame = shipFrame;
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

    @GetMapping("/office/ships/{id}/requests")
    public String shipList(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean notExported,
                           Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.REQUESTS);
        model.addAttribute("requests", requestService.findApproved(id, notExported));
        model.addAttribute("notExported", notExported);
        return "officerequest/list";
    }

    @GetMapping("/office/requests/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("request", requestService.getApproved(id));
        return "officerequest/detail";
    }

    // Writes the CSV download (then returns null: the response is complete),
    // or goes back to the list when nothing was checked: the ship's Requests tab when returnShipId is set.
    @PostMapping("/office/requests/export")
    public String export(@Valid RequestExportForm form, BindingResult result,
                         @RequestParam(required = false) Long returnShipId, HttpServletResponse response,
                         RedirectAttributes redirectAttributes) throws IOException {
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "request.export.noneSelected");
            return "redirect:" + (returnShipId == null ? "/office/requests" : ShipTab.REQUESTS.url(returnShipId));
        }
        OfficeRequestService.CsvFile csv = requestService.export(form.ids());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(csv.filename()).build().toString());
        response.getOutputStream().write(csv.content());
        return null;
    }
}
