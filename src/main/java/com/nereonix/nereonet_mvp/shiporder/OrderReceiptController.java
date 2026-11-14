package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService.LineReceipt;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService.Receipt;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService.Result;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * "Confirm receipt" (P51): the Master of the ship says the items of a CONFIRMED order (delivery scheduled) arrived.
 * SecurityConfig limits /ship/orders/** to MASTER; an order of another ship is a 404. English only, as every crew page.
 * <pre>
 *   GET  /ship/orders/{id}/receipt                  the form: per line ordered and received, the receipt files, when
 *   POST /ship/orders/{id}/receipt                  Review: files uploaded and removed, then the review page (P10)
 *   POST /ship/orders/{id}/receipt/edit             Back to edit on the review page: the form with the values
 *   POST /ship/orders/{id}/receipt/confirm          Confirm receipt on the review page: the order is CLOSED
 *   POST /ship/orders/{id}/receipt/files            one file of the receipt, sent by js/receipt.js with a progress bar
 *   GET  /ship/orders/{id}/receipt/files/{fileId}   a file uploaded (opens inline; ?download=true to save it)
 * </pre>
 * Works without JavaScript: the form's files go with Review (at most 10 MB in one request; more comes back to the
 * form with "too large", the files sent before kept). With JavaScript each file goes alone first, photos made smaller,
 * with a progress bar, so a dropped connection loses one file at most. The form keeps a draft (P15), and the files
 * uploaded wait on the order, so nothing typed or sent is lost.
 */
@Controller
public class OrderReceiptController {

    static final String BASE = "/ship/orders/{id}/receipt";

    private final ShipOrderPageService pageService;
    private final OrderReceiptService receiptService;

    public OrderReceiptController(ShipOrderPageService pageService, OrderReceiptService receiptService) {
        this.pageService = pageService;
        this.receiptService = receiptService;
    }

    @GetMapping(BASE)
    public String form(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model,
                       RedirectAttributes redirectAttributes) {
        ShipOrder order = pageService.order(currentUser.getUsername(), id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notConfirmed(order, redirectAttributes);
        }
        return showForm(order, ReceiptForm.of(order), model);
    }

    // Review: removes the files ticked, uploads the files chosen (each checked: PDF, JPEG or PNG, at most 10 MB), then
    // the form again with errors, or the review page. Files are kept even when another field has an error.
    @PostMapping(BASE)
    public String review(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @ModelAttribute("form") ReceiptForm form, BindingResult bindingResult, Model model,
                         RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.order(email, id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notConfirmed(order, redirectAttributes);
        }
        User master = pageService.master(email);
        if (form.getRemoveFileIds() != null && !form.getRemoveFileIds().isEmpty()) {
            receiptService.removeFiles(master, id, form.getRemoveFileIds());
        }
        for (MultipartFile file : form.chosenFiles()) {
            String error = fileError(file);
            if (error == null && receiptService.stageFile(master, id, file, CertificateFileStorage.contentTypeOf(file))
                    == Result.TOO_MANY_FILES) {
                error = "order.receipt.error.tooManyFiles";
            }
            if (error != null) {
                bindingResult.rejectValue("files", error, new Object[] {file.getOriginalFilename()}, null);
            }
        }
        if (!bindingResult.hasFieldErrors("files") && receiptService.files(id).isEmpty()) {
            bindingResult.rejectValue("files", "order.receipt.error.fileRequired");
        }
        check(form, order, bindingResult);
        if (bindingResult.hasErrors()) {
            return showForm(order, form, model);
        }
        return showReview(order, form, model);
    }

    // Back to edit: the form with the values of the review page.
    @PostMapping(BASE + "/edit")
    public String edit(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @ModelAttribute("form") ReceiptForm form, Model model, RedirectAttributes redirectAttributes) {
        ShipOrder order = pageService.order(currentUser.getUsername(), id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notConfirmed(order, redirectAttributes);
        }
        return showForm(order, form, model);
    }

    // Confirm receipt: checked again (a file removed in another tab, the time now in the past...), then CLOSED.
    @PostMapping(BASE + "/confirm")
    public String confirm(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          @ModelAttribute("form") ReceiptForm form, BindingResult bindingResult, Model model,
                          RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.order(email, id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notConfirmed(order, redirectAttributes);
        }
        check(form, order, bindingResult);
        if (bindingResult.hasErrors()) {
            return showForm(order, form, model);
        }
        Map<Long, LineReceipt> lines = new HashMap<>();
        for (ReceiptForm.Line line : form.getLines()) {
            lines.put(line.getLineId(), new LineReceipt(line.quantity(), line.getNote()));
        }
        Result result = receiptService.confirmReceipt(pageService.master(email), id,
                new Receipt(form.getReceivedAtInstant(), lines));
        switch (result) {
            case DONE -> redirectAttributes.addFlashAttribute("successMessage", "order.receipt.confirmed");
            case NO_FILE -> {
                bindingResult.rejectValue("files", "order.receipt.error.fileRequired");
                return showForm(order, form, model);
            }
            default -> {
                return notConfirmed(order, redirectAttributes);
            }
        }
        return "redirect:/ship/orders/" + id;
    }

    // The review and confirm URLs take only POST: a GET (signing in again after the session expired, P12) opens the
    // form, where the draft brings the values back.
    @GetMapping({BASE + "/edit", BASE + "/confirm"})
    public String toForm(@PathVariable Long id) {
        return "redirect:/ship/orders/" + id + "/receipt";
    }

    /**
     * One file of the receipt, from js/receipt.js: 204 once saved; 422 with the message as plain text for a file that
     * is not a PDF, JPEG or PNG or is larger than 10 MB, or when the receipt has 10 files; 409 when the order is no
     * longer waiting for its receipt.
     */
    @PostMapping(BASE + "/files")
    @ResponseBody
    public ResponseEntity<String> upload(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                         @RequestParam(required = false) MultipartFile file) {
        String email = currentUser.getUsername();
        pageService.order(email, id);
        String error = file == null || file.isEmpty() ? "order.receipt.error.fileRequired" : fileError(file);
        if (error == null) {
            Result result = receiptService.stageFile(pageService.master(email), id, file,
                    CertificateFileStorage.contentTypeOf(file));
            if (result == Result.DONE) {
                return ResponseEntity.noContent().build();
            }
            if (result == Result.NOT_CONFIRMED) {
                return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_PLAIN)
                        .body(pageService.message("order.receipt.notConfirmed"));
            }
            error = "order.receipt.error.tooManyFiles";
        }
        return ResponseEntity.unprocessableEntity().contentType(MediaType.TEXT_PLAIN)
                .body(pageService.message(error, file == null ? "" : file.getOriginalFilename()));
    }

    // Where UploadTooLargeFilter sends a too large file (?fileTooLarge): the form, which says so.
    @GetMapping(BASE + "/files")
    public String uploadTooLarge(@PathVariable Long id, @RequestParam(required = false) String fileTooLarge) {
        return "redirect:/ship/orders/" + id + "/receipt" + (fileTooLarge != null ? "?fileTooLarge" : "");
    }

    @GetMapping(BASE + "/files/{fileId}")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                         @PathVariable Long fileId,
                                         @RequestParam(defaultValue = "false") boolean download) {
        return receiptService.file(pageService.order(currentUser.getUsername(), id), fileId, true, download);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** One line of the form or the review: the order's line and the form's. */
    public record LineRow(int index, ShipOrderLine line, ReceiptForm.Line form) {

        /** What arrived, as entered; null when it is not a valid quantity. */
        public BigDecimal received() {
            BigDecimal quantity = form.quantity();
            return OrderReceiptService.isValidReceivedQuantity(quantity) ? quantity : null;
        }

        /** How much less than ordered (the review's "Short by 5 kg"); null when nothing is short. */
        public BigDecimal shortBy() {
            BigDecimal received = received();
            return received == null || received.compareTo(line.getQuantity()) >= 0 ? null
                    : line.getQuantity().subtract(received);
        }
    }

    // Checks the lines (exactly the order's lines not removed, each with a quantity of zero or more and a short note)
    // and the time received (required, not in the future, not before the order was sent).
    private static void check(ReceiptForm form, ShipOrder order, BindingResult errors) {
        Set<Long> expected = order.getLines().stream().filter(line -> !line.isRemoved()).map(ShipOrderLine::getId)
                .collect(Collectors.toSet());
        Set<Long> given = form.getLines().stream().map(ReceiptForm.Line::getLineId).collect(Collectors.toSet());
        if (!expected.equals(given) || given.size() != form.getLines().size()) {
            // Not this order's lines: a page from before the office changed them, or a forged request.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        for (int i = 0; i < form.getLines().size(); i++) {
            ReceiptForm.Line line = form.getLines().get(i);
            if (!OrderReceiptService.isValidReceivedQuantity(line.quantity())) {
                errors.rejectValue("lines[" + i + "].received", "order.receipt.error.quantity");
            }
            if (line.getNote() != null && line.getNote().length() > ShipOrderLine.NOTE_MAX_LENGTH) {
                errors.rejectValue("lines[" + i + "].note", "order.receipt.error.noteTooLong");
            }
        }
        if (form.getReceivedAt() == null) {
            errors.rejectValue("receivedAt", "order.receipt.error.receivedAtRequired");
        } else {
            Instant receivedAt = form.getReceivedAtInstant();
            if (!OrderReceiptService.isPossibleReceivedAt(order, receivedAt)) {
                errors.rejectValue("receivedAt", receivedAt.isAfter(Instant.now())
                        ? "order.receipt.error.receivedAtFuture" : "order.receipt.error.receivedAtBeforeSent");
            }
        }
    }

    // null for a file the receipt takes: a PDF, JPEG or PNG (extension and first bytes) of at most 10 MB.
    private static String fileError(MultipartFile file) {
        if (file.getSize() > CertificateFileStorage.MAX_FILE_SIZE) {
            return "order.receipt.error.fileTooLarge";
        }
        return CertificateFileStorage.contentTypeOf(file) == null ? "order.receipt.error.fileType" : null;
    }

    private String showForm(ShipOrder order, ReceiptForm form, Model model) {
        model.addAttribute("order", order);
        model.addAttribute("form", form);
        model.addAttribute("rows", rows(order, form));
        model.addAttribute("files", receiptService.files(order.getId()));
        model.addAttribute("maxFiles", OrderReceiptService.MAX_FILES);
        model.addAttribute("maxFileSize", CertificateFileStorage.MAX_FILE_SIZE);
        model.addAttribute("noteMax", ShipOrderLine.NOTE_MAX_LENGTH);
        return "shiporder/receipt";
    }

    private String showReview(ShipOrder order, ReceiptForm form, Model model) {
        List<LineRow> rows = rows(order, form);
        model.addAttribute("order", order);
        model.addAttribute("form", form);
        model.addAttribute("rows", rows);
        model.addAttribute("shortLines", rows.stream().filter(row -> row.shortBy() != null).count());
        model.addAttribute("files", receiptService.files(order.getId()));
        return "shiporder/receipt-review";
    }

    // The form's lines with the order's, by category and item name (the form's order, which is the order's).
    private static List<LineRow> rows(ShipOrder order, ReceiptForm form) {
        Map<Long, ShipOrderLine> byId = new LinkedHashMap<>();
        order.getLines().forEach(line -> byId.put(line.getId(), line));
        List<LineRow> rows = new ArrayList<>();
        for (int i = 0; i < form.getLines().size(); i++) {
            ReceiptForm.Line line = form.getLines().get(i);
            ShipOrderLine orderLine = byId.get(line.getLineId());
            if (orderLine != null) {
                rows.add(new LineRow(i, orderLine, line));
            }
        }
        return rows;
    }

    private static String notConfirmed(ShipOrder order, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", order.getStatus() == ShipOrderStatus.CLOSED
                ? "order.receipt.alreadyReceived" : "order.receipt.notConfirmed");
        return "redirect:/ship/orders/" + order.getId();
    }
}
