package com.nereonix.nereonet_mvp.shiporder;

import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoice;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceStatus;
import com.nereonix.nereonet_mvp.core.order.InvoicePageStatus;
import com.nereonix.nereonet_mvp.core.order.InvoiceReadingService;
import com.nereonix.nereonet_mvp.core.order.InvoiceReadingService.Start;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFile;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService.Result;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * "Photograph the invoice", phone first: when a delivery arrives the vessel's Chief cook (Count delivery) or
 * Master (Confirm receipt, without a count of the cook to approve) photographs the supplier's invoice page by page;
 * the server reads it (InvoiceReadingService) and the browser goes straight on to the count (the cook's count page,
 * the Master's receipt). Nothing is matched to the order and nothing touches the store here. SecurityConfig gives
 * /ship/orders/{id}/invoice/** to MASTER and CHIEF_COOK; the services refuse anyone but the ship's enabled Master or
 * Chief cook as well; another ship's order is a 404. English only, as every crew page.
 * <pre>
 *   GET  /ship/orders/{id}/invoice                        the pages so far, Take a photo, Choose a file, Done
 *   POST /ship/orders/{id}/invoice/pages                  one page (replace=fileId: Retake), from js/invoice.js
 *                                                         (js=true: 204, 422 or 409 with the reason as text) or the
 *                                                         Upload button (back to the page)
 *   GET  /ship/orders/{id}/invoice/pages/{fileId}         a page (opens inline: the thumbnail)
 *   POST /ship/orders/{id}/invoice/pages/{fileId}/remove  Remove
 *   POST /ship/orders/{id}/invoice/pages/{fileId}/read    Read again, that page
 *   POST /ship/orders/{id}/invoice/read                   Done: start counting (again=true: Read again, every page)
 *   GET  /ship/orders/{id}/invoice/reading                "Reading page 1 of 2" (reloads itself), then on
 *   GET  /ship/orders/{id}/invoice/reading/status         the same as JSON, for js/invoice.js
 * </pre>
 * The pages are the order's staged receipt files (OrderReceiptService.stageInvoicePage): they are the Master's receipt
 * files whatever happens to the reading.
 */
@Controller
public class InvoiceController {

    static final String BASE = "/ship/orders/{id}/invoice";

    /** One page on the photo page: its file and number, and what the ship should know of its reading. */
    public record PageRow(OrderReceiptFile file, int number, boolean failed, boolean hard) {
    }

    /** The reading for js/invoice.js: done, the text to show, where to go once done. */
    public record ReadingStatus(boolean done, String text, String next) {
    }

    private final ShipOrderPageService pageService;
    private final OrderReceiptService receiptService;
    private final InvoiceReadingService readingService;

    public InvoiceController(ShipOrderPageService pageService, OrderReceiptService receiptService,
                             InvoiceReadingService readingService) {
        this.pageService = pageService;
        this.receiptService = receiptService;
        this.readingService = readingService;
    }

    @GetMapping(BASE)
    public String page(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @RequestParam(required = false) String fileTooLarge, Model model,
                       RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.invoiceOrder(email, id);
        User user = pageService.crew(email);
        String away = away(order, user, redirectAttributes);
        if (away != null) {
            return away;
        }
        readingService.giveUpIfStalled(id);
        DeliveryInvoice invoice = readingService.invoice(id).orElse(null);
        if (invoice != null && invoice.getStatus() == DeliveryInvoiceStatus.READING) {
            return "redirect:/ship/orders/" + id + "/invoice/reading";
        }
        List<OrderReceiptFile> files = receiptService.invoicePages(id);
        List<PageRow> pages = files.stream().map(f -> new PageRow(f, f.getInvoicePage(),
                f.getInvoicePageStatus() != null && f.getInvoicePageStatus().failed(),
                f.getInvoicePageStatus() == InvoicePageStatus.HARD_TO_READ)).toList();
        boolean read = invoice != null;
        // Nothing could be read at all (no network to the reader): one message, Read again later.
        boolean notReadNow = read && invoice.getStatus() == DeliveryInvoiceStatus.FAILED
                && pages.stream().anyMatch(PageRow::failed) && pages.stream().noneMatch(PageRow::hard)
                && files.stream().noneMatch(f -> f.getInvoicePageStatus() == InvoicePageStatus.READ);
        model.addAttribute("order", order);
        model.addAttribute("pages", pages);
        model.addAttribute("notReadNow", notReadNow);
        model.addAttribute("otherOrderReference", read && invoice.isOtherOrder() ? invoice.getOrderReference() : null);
        model.addAttribute("maxPages", OrderReceiptService.MAX_INVOICE_PAGES);
        model.addAttribute("canAdd", pages.size() < OrderReceiptService.MAX_INVOICE_PAGES
                && receiptService.files(id).size() < OrderReceiptService.MAX_FILES);
        model.addAttribute("maxFileSize", CertificateFileStorage.MAX_FILE_SIZE);
        model.addAttribute("next", next(order, user));
        model.addAttribute("fileTooLarge", fileTooLarge != null);
        return "shiporder/invoice";
    }

    /** One page from js/invoice.js: 204 once saved; 422 or 409 with the reason as plain text. */
    @PostMapping(value = BASE + "/pages", params = "js=true")
    @ResponseBody
    public ResponseEntity<String> uploadFromScript(@AuthenticationPrincipal CurrentUser currentUser,
                                                   @PathVariable Long id,
                                                   @RequestParam(required = false) MultipartFile file,
                                                   @RequestParam(required = false) Long replace) {
        Upload upload = stage(currentUser, id, file, replace);
        if (upload.error() == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(upload.conflict() ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.TEXT_PLAIN)
                .body(pageService.message(upload.error(), upload.name(), OrderReceiptService.MAX_INVOICE_PAGES));
    }

    /** One page with the Upload button (no script): back to the page, with why when it was not taken. */
    @PostMapping(BASE + "/pages")
    public String upload(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @RequestParam(required = false) MultipartFile file,
                         @RequestParam(required = false) Long replace, RedirectAttributes redirectAttributes) {
        Upload upload = stage(currentUser, id, file, replace);
        if (upload.error() != null) {
            String away = upload.conflict() ? away(upload.order(), upload.user(), redirectAttributes) : null;
            if (away != null) {
                return away;
            }
            redirectAttributes.addFlashAttribute("errorMessage", upload.error());
            redirectAttributes.addFlashAttribute("errorArgs",
                    new Object[] {upload.name(), OrderReceiptService.MAX_INVOICE_PAGES});
        }
        return "redirect:/ship/orders/" + id + "/invoice";
    }

    private record Upload(ShipOrder order, User user, String error, boolean conflict, String name) {
    }

    // Checks the file (JPEG, PNG, WebP, HEIC or PDF by its first bytes, at most 10 MB) and stages it.
    private Upload stage(CurrentUser currentUser, Long id, MultipartFile file, Long replace) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.invoiceOrder(email, id);
        User user = pageService.crew(email);
        String name = file == null || file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String contentType = OrderReceiptService.invoicePageContentType(file);
        if (file == null || file.isEmpty()) {
            return new Upload(order, user, "order.invoice.error.noFile", false, name);
        }
        if (file.getSize() > CertificateFileStorage.MAX_FILE_SIZE) {
            return new Upload(order, user, "order.invoice.error.tooLarge", false, name);
        }
        if (contentType == null) {
            return new Upload(order, user, "order.invoice.error.fileType", false, name);
        }
        Result result = receiptService.stageInvoicePage(user, id, file, contentType, replace);
        String error = switch (result) {
            case DONE -> null;
            case TOO_MANY_PAGES -> "order.invoice.error.tooManyPages";
            case TOO_MANY_FILES -> "order.receipt.error.tooManyFiles";
            case READING -> "order.invoice.error.reading";
            case COUNT_SENT -> "order.count.alreadySent";
            default -> "order.invoice.error.notConfirmed";
        };
        boolean conflict = result == Result.READING || result == Result.COUNT_SENT || result == Result.NOT_CONFIRMED;
        return new Upload(order, user, error, conflict, name);
    }

    // Where UploadTooLargeFilter sends a too large page (?fileTooLarge), and a GET after signing in again: the page.
    @GetMapping({BASE + "/pages", BASE + "/read", BASE + "/pages/{fileId}/remove", BASE + "/pages/{fileId}/read"})
    public String toPage(@PathVariable Long id, @RequestParam(required = false) String fileTooLarge) {
        return "redirect:/ship/orders/" + id + "/invoice" + (fileTooLarge != null ? "?fileTooLarge" : "");
    }

    @GetMapping(BASE + "/pages/{fileId}")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                         @PathVariable Long fileId) {
        ShipOrder order = pageService.invoiceOrder(currentUser.getUsername(), id);
        if (receiptService.invoicePages(id).stream().noneMatch(f -> f.getId().equals(fileId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return receiptService.file(order, fileId, true, false);
    }

    @PostMapping(BASE + "/pages/{fileId}/remove")
    public String remove(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @PathVariable Long fileId, RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.invoiceOrder(email, id);
        User user = pageService.crew(email);
        Result result = receiptService.removeInvoicePage(user, id, fileId);
        if (result == Result.READING) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.invoice.error.reading");
        } else if (result != Result.DONE) {
            return away(order, user, redirectAttributes);
        }
        return "redirect:/ship/orders/" + id + "/invoice";
    }

    // Read again, one page.
    @PostMapping(BASE + "/pages/{fileId}/read")
    public String readPage(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                           @PathVariable Long fileId, RedirectAttributes redirectAttributes) {
        return start(currentUser, id, fileId, false, redirectAttributes);
    }

    // Done: start counting (again=true: Read again, the whole invoice).
    @PostMapping(BASE + "/read")
    public String read(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @RequestParam(defaultValue = "false") boolean again, RedirectAttributes redirectAttributes) {
        return start(currentUser, id, null, !again, redirectAttributes);
    }

    @GetMapping(BASE + "/reading")
    public String reading(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model,
                          RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.invoiceOrder(email, id);
        User user = pageService.crew(email);
        String away = away(order, user, redirectAttributes);
        if (away != null) {
            return away;
        }
        ReadingStatus status = status(order, user);
        if (status.done()) {
            return "redirect:" + status.next();
        }
        model.addAttribute("order", order);
        model.addAttribute("text", status.text());
        model.addAttribute("statusUrl", "/ship/orders/" + id + "/invoice/reading/status");
        return "shiporder/invoice-reading";
    }

    @GetMapping(value = BASE + "/reading/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ReadingStatus readingStatus(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.invoiceOrder(email, id);
        return status(order, pageService.crew(email));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private String start(CurrentUser currentUser, Long id, Long fileId, boolean done,
                         RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = pageService.invoiceOrder(email, id);
        User user = pageService.crew(email);
        Start start = readingService.start(user, id, fileId, done);
        return switch (start) {
            case STARTED, ALREADY_READING -> "redirect:/ship/orders/" + id + "/invoice/reading";
            case NOTHING_TO_READ, NOT_CONFIGURED -> "redirect:" + next(order, user);
            case NO_PAGES -> {
                redirectAttributes.addFlashAttribute("errorMessage", "order.invoice.error.noPages");
                yield "redirect:/ship/orders/" + id + "/invoice";
            }
            default -> away(pageService.invoiceOrder(email, id), user, redirectAttributes);
        };
    }

    // Reading: "Reading page 1 of 2"; done: where to go (the count when something was read and nothing needs the
    // ship's attention, else back to the photo page).
    private ReadingStatus status(ShipOrder order, User user) {
        Long id = order.getId();
        readingService.giveUpIfStalled(id);
        DeliveryInvoice invoice = readingService.invoice(id).orElse(null);
        List<OrderReceiptFile> pages = receiptService.invoicePages(id);
        String photoPage = "/ship/orders/" + id + "/invoice";
        if (invoice == null) {
            return new ReadingStatus(true, "", photoPage);
        }
        if (invoice.getStatus() == DeliveryInvoiceStatus.READING) {
            int current = pages.stream().filter(p -> p.getInvoicePageStatus() == InvoicePageStatus.WAITING)
                    .mapToInt(OrderReceiptFile::getInvoicePage).min().orElse(pages.size());
            return new ReadingStatus(false, pageService.message("order.invoice.reading.page", current,
                    Math.max(pages.size(), current)), null);
        }
        boolean attention = invoice.getStatus() == DeliveryInvoiceStatus.FAILED || invoice.isOtherOrder()
                || pages.stream().anyMatch(p -> p.getInvoicePageStatus() != null
                        && (p.getInvoicePageStatus().failed() || p.getInvoicePageStatus() == InvoicePageStatus.HARD_TO_READ));
        return new ReadingStatus(true, pageService.message("order.invoice.reading.done"),
                attention ? photoPage : next(order, user));
    }

    // Where the photos lead: the Chief cook's count, the Master's receipt.
    private static String next(ShipOrder order, User user) {
        return "/ship/orders/" + order.getId() + (user.getRole() == Role.CHIEF_COOK ? "/count" : "/receipt");
    }

    // Away from the photo page when it cannot be used: the order is not waiting for its delivery, or the cook's count
    // is with the Master. Null when it can.
    private static String away(ShipOrder order, User user, RedirectAttributes redirectAttributes) {
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            redirectAttributes.addFlashAttribute("errorMessage", order.getStatus() == ShipOrderStatus.CLOSED
                    ? "order.receipt.alreadyReceived" : "order.invoice.error.notConfirmed");
            return "redirect:/ship/orders/" + order.getId();
        }
        if (user.getRole() == Role.CHIEF_COOK && !order.getDeliveryCountStatus().editable()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
            return "redirect:/ship/orders/" + order.getId() + "/count";
        }
        return null;
    }
}
