package com.nereonix.nereonet_mvp.shiporder;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

import com.nereonix.nereonet_mvp.core.ai.CountExtractor;
import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountService;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountService.Count;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountService.LineCount;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFile;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService.Result;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.stores.VoiceCountService;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.Confidence;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.Failure;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.Heard;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.VoiceCountException;

/**
 * "Count delivery" (P61): the vessel's Chief cook counts what arrived of a CONFIRMED order (delivery scheduled) and
 * sends it to the Master, who approves it by confirming receipt (OrderReceiptController). Nothing here touches the
 * store. SecurityConfig gives /ship/orders/{id}/count/** to CHIEF_COOK only; DeliveryCountService and
 * OrderReceiptService refuse anyone but the ship's enabled Chief cook as well; another ship's order is a 404.
 * English only, as every crew page.
 * <pre>
 *   GET  /ship/orders/{id}/count                  the count (or, once sent, what was sent)
 *   POST /ship/orders/{id}/count                  Save draft (allArrived=lineId: that line as ordered, then saved)
 *   POST /ship/orders/{id}/count/send             Send to Master
 *   POST /ship/orders/{id}/count/files            one photo of the delivery note, from js/receipt.js
 *   GET  /ship/orders/{id}/count/files/{fileId}   a photo he uploaded (opens inline)
 *   POST /ship/orders/{id}/count/voice            P57's Count by voice over the order's lines: JSON, nothing saved
 * </pre>
 * The count is a draft kept on the order (the server's, not the browser's): the cook saves and comes back to it.
 */
@Controller
public class DeliveryCountController {

    static final String BASE = "/ship/orders/{id}/count";

    /** The answer to js/stores-voice.js on this page: what was heard per line, or the failure in plain words. */
    public record HeardLine(Long lineId, String quantity, String words, String confidence, boolean low) {
    }

    public record VoiceHeard(List<HeardLine> lines, List<String> unmatched, String summary) {
    }

    public record VoiceAnswer(VoiceHeard heard, String error, boolean retry) {
    }

    private final ShipOrderPageService pageService;
    private final DeliveryCountService countService;
    private final OrderReceiptService receiptService;
    private final VoiceCountService voiceCountService;
    private final QuantityRules quantityRules;

    public DeliveryCountController(ShipOrderPageService pageService, DeliveryCountService countService,
                                   OrderReceiptService receiptService, VoiceCountService voiceCountService,
                                   QuantityRules quantityRules) {
        this.pageService = pageService;
        this.countService = countService;
        this.receiptService = receiptService;
        this.voiceCountService = voiceCountService;
        this.quantityRules = quantityRules;
    }

    @GetMapping(BASE)
    public String form(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model,
                       RedirectAttributes redirectAttributes) {
        ShipOrderPageService.OrderView view = pageService.countView(currentUser.getUsername(), id);
        ShipOrder order = view.order();
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        return show(currentUser, view, DeliveryCountForm.of(order), model);
    }

    // Save draft: the files first (kept even when a quantity is wrong), then the count if every quantity typed is valid.
    @PostMapping(BASE)
    public String save(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @ModelAttribute("form") DeliveryCountForm form, BindingResult bindingResult,
                       @RequestParam(required = false) Long allArrived, Model model,
                       RedirectAttributes redirectAttributes) {
        return post(currentUser, id, form, bindingResult, allArrived, false, model, redirectAttributes);
    }

    // Send to Master: as Save draft, and every line must be counted.
    @PostMapping(BASE + "/send")
    public String send(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @ModelAttribute("form") DeliveryCountForm form, BindingResult bindingResult, Model model,
                       RedirectAttributes redirectAttributes) {
        return post(currentUser, id, form, bindingResult, null, true, model, redirectAttributes);
    }

    // A GET of /send (signing in again, or a too large upload sent back by UploadTooLargeFilter): the count.
    @GetMapping({BASE + "/send", BASE + "/files"})
    public String toForm(@PathVariable Long id, @RequestParam(required = false) String fileTooLarge) {
        return "redirect:/ship/orders/" + id + "/count" + (fileTooLarge != null ? "?fileTooLarge" : "");
    }

    /** One photo of the delivery note, from js/receipt.js: 204, 422 (not taken, with why), 409 (no longer counting). */
    @PostMapping(BASE + "/files")
    @ResponseBody
    public ResponseEntity<String> upload(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                         @RequestParam(required = false) MultipartFile file) {
        String email = currentUser.getUsername();
        pageService.countView(email, id);
        String error = file == null || file.isEmpty() ? "order.receipt.error.fileRequired" : fileError(file);
        if (error == null) {
            Result result = receiptService.stageFile(pageService.cook(email), id, file,
                    CertificateFileStorage.contentTypeOf(file));
            if (result == Result.DONE) {
                return ResponseEntity.noContent().build();
            }
            if (result == Result.NOT_CONFIRMED || result == Result.COUNT_SENT) {
                return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_PLAIN)
                        .body(pageService.message(result == Result.COUNT_SENT ? "order.count.alreadySent"
                                : "order.count.notConfirmed"));
            }
            error = "order.receipt.error.tooManyFiles";
        }
        return ResponseEntity.unprocessableEntity().contentType(MediaType.TEXT_PLAIN)
                .body(pageService.message(error, file == null ? "" : file.getOriginalFilename()));
    }

    // Only the photos he uploaded himself, while the order waits for its receipt.
    @GetMapping(BASE + "/files/{fileId}")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                         @PathVariable Long fileId) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.countView(email, id).order();
        User cook = pageService.cook(email);
        boolean own = ownFiles(order, cook).stream().anyMatch(f -> f.getId().equals(fileId));
        if (!own || order.getStatus() != ShipOrderStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return receiptService.file(order, fileId, true, false);
    }

    /**
     * Count by voice (P57) on this page: the recording against the order's lines (CountExtractor over their items).
     * The answer is what was heard per line (the quantity checked by the receipt's rule, else it goes to the phrases
     * not matched), for js/stores-voice.js to fill in; nothing is saved until the cook saves or sends.
     */
    @PostMapping(value = BASE + "/voice", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<VoiceAnswer> voice(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                             @RequestParam(required = false) MultipartFile audio) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.countView(email, id).order();
        User cook = pageService.cook(email);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED || !order.getDeliveryCountStatus().editable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new VoiceAnswer(null,
                    pageService.message(order.getStatus() == ShipOrderStatus.CONFIRMED ? "order.count.alreadySent"
                            : "order.count.notConfirmed"), false));
        }
        Map<Long, ShipOrderLine> byItem = new LinkedHashMap<>();
        order.getLines().stream().filter(line -> !line.isRemoved())
                .forEach(line -> byItem.put(line.getItem().getId(), line));
        List<CountExtractor.Item> items = byItem.values().stream()
                .map(line -> new CountExtractor.Item(line.getItem().getId(), line.getItem().getName(),
                        line.getItem().getUnit()))
                .toList();
        try {
            byte[] bytes;
            if (audio == null || audio.isEmpty()) {
                throw new VoiceCountException(Failure.NO_AUDIO);
            }
            try {
                bytes = audio.getBytes();
            } catch (IOException e) {
                throw new VoiceCountException(Failure.NO_AUDIO);
            }
            Heard heard = voiceCountService.listen(cook, bytes, audio.getContentType(), items);
            List<HeardLine> lines = new ArrayList<>();
            List<String> unmatched = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (CountExtractor.Line line : heard.extraction().lines()) {
                ShipOrderLine orderLine = byItem.get(line.itemId());
                QuantityRules.Check check = orderLine == null || line.quantity() == null ? null
                        : quantityRules.forItem(orderLine.getItem(), Use.RECEIPT)
                                .check(line.quantity(), orderLine.getQuantity());
                if (check == null || !check.ok() || !seen.add(line.itemId())) {
                    if (line.words() != null && !line.words().isBlank()) {
                        unmatched.add(line.words());
                    }
                    continue;
                }
                Confidence confidence = Confidence.of(line.confidence());
                lines.add(new HeardLine(orderLine.getId(), check.value().stripTrailingZeros().toPlainString(),
                        line.words(), pageService.message(confidence.labelKey()), confidence == Confidence.LOW));
            }
            unmatched.addAll(heard.extraction().unmatched());
            if (lines.isEmpty() && unmatched.isEmpty()) {
                throw new VoiceCountException(Failure.NOTHING_UNDERSTOOD);
            }
            String summary = pageService.message("order.count.voice.filled", lines.size());
            return ResponseEntity.ok(new VoiceAnswer(new VoiceHeard(lines, unmatched, summary), null, false));
        } catch (VoiceCountException e) {
            Failure failure = e.failure();
            HttpStatus status = switch (failure) {
                case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                case PROVIDER_ERROR -> HttpStatus.BAD_GATEWAY;
                case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.UNPROCESSABLE_ENTITY;
            };
            boolean retry = failure == Failure.TIMEOUT || failure == Failure.PROVIDER_ERROR;
            return ResponseEntity.status(status)
                    .body(new VoiceAnswer(null, pageService.message(failure.messageKey()), retry));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** One line of the count page: its index in the form, the order's line and the form's. */
    public record LineRow(int index, ShipOrderLine line, DeliveryCountForm.Line form) {
    }

    /** One category's rows, as the order groups them. */
    public record RowGroup(ItemCategory category, List<LineRow> rows) {
    }

    private String post(CurrentUser currentUser, Long id, DeliveryCountForm form, BindingResult bindingResult,
                        Long allArrived, boolean sending, Model model, RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrderPageService.OrderView view = pageService.countView(email, id);
        ShipOrder order = view.order();
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        if (!order.getDeliveryCountStatus().editable()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
            return "redirect:/ship/orders/" + id + "/count";
        }
        Map<Long, ShipOrderLine> orderLines = order.getLines().stream()
                .collect(Collectors.toMap(ShipOrderLine::getId, Function.identity()));
        checkLines(form, order);
        // All arrived without JavaScript: that line as ordered.
        if (allArrived != null) {
            form.getLines().stream().filter(line -> allArrived.equals(line.getLineId())).findFirst()
                    .ifPresent(line -> line.setCounted(orderLines.get(allArrived).getQuantity()
                            .stripTrailingZeros().toPlainString()));
        }
        User cook = pageService.cook(email);
        if (form.getRemoveFileIds() != null && !form.getRemoveFileIds().isEmpty()) {
            receiptService.removeFiles(cook, id, form.getRemoveFileIds());
        }
        for (MultipartFile file : form.chosenFiles()) {
            String error = fileError(file);
            if (error == null && receiptService.stageFile(cook, id, file, CertificateFileStorage.contentTypeOf(file))
                    == Result.TOO_MANY_FILES) {
                error = "order.receipt.error.tooManyFiles";
            }
            if (error != null) {
                bindingResult.rejectValue("files", error, new Object[] {file.getOriginalFilename()}, null);
            }
        }
        Map<Long, LineCount> counts = new HashMap<>();
        for (int i = 0; i < form.getLines().size(); i++) {
            DeliveryCountForm.Line line = form.getLines().get(i);
            ShipOrderLine orderLine = orderLines.get(line.getLineId());
            BigDecimal counted = null;
            if (line.isBlank()) {
                if (sending) {
                    bindingResult.rejectValue("lines[" + i + "].counted", "order.count.error.required",
                            new Object[] {orderLine.getItem().getName()}, null);
                }
            } else {
                // P60: the receipt's rule of the item's unit; 0 is "nothing arrived"; the quantity ordered is always
                // accepted, even above the maximum.
                QuantityRules.Check check = quantityRules.forItem(orderLine.getItem(), Use.RECEIPT)
                        .check(line.getCounted(), orderLine.getQuantity());
                check.reject(bindingResult, "lines[" + i + "].counted");
                counted = check.ok() ? check.value() : null;
            }
            if (line.getNote() != null && line.getNote().length() > ShipOrderLine.NOTE_MAX_LENGTH) {
                bindingResult.rejectValue("lines[" + i + "].note", "order.receipt.error.noteTooLong");
            }
            counts.put(line.getLineId(), new LineCount(counted, line.getNote()));
        }
        if (form.getNote() != null && form.getNote().length() > ShipOrder.COUNT_NOTE_MAX_LENGTH) {
            bindingResult.rejectValue("note", "order.count.error.noteTooLong",
                    new Object[] {ShipOrder.COUNT_NOTE_MAX_LENGTH}, null);
        }
        if (bindingResult.hasErrors()) {
            return show(currentUser, pageService.countView(email, id), form, model);
        }
        Count count = new Count(counts, form.getNote());
        DeliveryCountService.Result result = sending ? countService.send(cook, id, count)
                : countService.saveDraft(cook, id, count);
        return switch (result) {
            case DONE -> {
                redirectAttributes.addFlashAttribute("successMessage", sending ? "order.count.sent" : "order.count.saved");
                yield "redirect:/ship/orders/" + id + "/count";
            }
            case WRONG_STATE -> {
                redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
                yield "redirect:/ship/orders/" + id + "/count";
            }
            default -> notCountable(order, redirectAttributes);
        };
    }

    // Exactly the order's lines not removed, each once: else a stale page or a forged request.
    private static void checkLines(DeliveryCountForm form, ShipOrder order) {
        Set<Long> expected = order.getLines().stream().filter(line -> !line.isRemoved()).map(ShipOrderLine::getId)
                .collect(Collectors.toSet());
        Set<Long> given = form.getLines().stream().map(DeliveryCountForm.Line::getLineId).collect(Collectors.toSet());
        if (!expected.equals(given) || given.size() != form.getLines().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private String show(CurrentUser currentUser, ShipOrderPageService.OrderView view, DeliveryCountForm form,
                        Model model) {
        ShipOrder order = view.order();
        User cook = pageService.cook(currentUser.getUsername());
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < form.getLines().size(); i++) {
            index.put(form.getLines().get(i).getLineId(), i);
        }
        // The rows by category, as the order shows its lines; removed lines are not counted.
        List<RowGroup> groups = new ArrayList<>();
        for (ShipOrderPageService.LineGroup group : view.groups()) {
            List<LineRow> rows = new ArrayList<>();
            for (ShipOrderPageService.LineView lineView : group.lines()) {
                Integer i = index.get(lineView.line().getId());
                if (i != null) {
                    rows.add(new LineRow(i, lineView.line(), form.getLines().get(i)));
                }
            }
            if (!rows.isEmpty()) {
                groups.add(new RowGroup(group.category(), rows));
            }
        }
        model.addAttribute("order", order);
        model.addAttribute("view", view);
        model.addAttribute("form", form);
        model.addAttribute("groups", groups);
        model.addAttribute("editable", order.getDeliveryCountStatus().editable());
        model.addAttribute("files", ownFiles(order, cook));
        // P62: the invoice's pages are not among his photos here (they have their own page).
        model.addAttribute("invoicePages", receiptService.invoicePages(order.getId()).size());
        model.addAttribute("otherFiles", receiptService.files(order.getId()).size() - ownFiles(order, cook).size());
        model.addAttribute("maxFiles", OrderReceiptService.MAX_FILES);
        model.addAttribute("maxFileSize", CertificateFileStorage.MAX_FILE_SIZE);
        model.addAttribute("noteMax", ShipOrderLine.NOTE_MAX_LENGTH);
        model.addAttribute("countNoteMax", ShipOrder.COUNT_NOTE_MAX_LENGTH);
        model.addAttribute("voiceEnabled", voiceCountService.configured());
        return "shiporder/count";
    }

    private List<OrderReceiptFile> ownFiles(ShipOrder order, User cook) {
        return receiptService.files(order.getId()).stream()
                .filter(f -> !f.isInvoicePage())
                .filter(f -> f.getUploadedBy() != null && f.getUploadedBy().getId().equals(cook.getId()))
                .toList();
    }

    // null for a file the receipt takes: a PDF, JPEG or PNG (extension and first bytes) of at most 10 MB.
    private static String fileError(MultipartFile file) {
        if (file.getSize() > CertificateFileStorage.MAX_FILE_SIZE) {
            return "order.receipt.error.fileTooLarge";
        }
        return CertificateFileStorage.contentTypeOf(file) == null ? "order.receipt.error.fileType" : null;
    }

    private static String notCountable(ShipOrder order, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", order.getStatus() == ShipOrderStatus.CLOSED
                ? "order.count.received" : "order.count.notConfirmed");
        return "redirect:/ship/orders/" + order.getId();
    }
}
