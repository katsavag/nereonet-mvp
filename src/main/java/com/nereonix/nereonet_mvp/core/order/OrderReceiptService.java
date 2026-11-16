package com.nereonix.nereonet_mvp.core.order;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The receipt of an order on board. Only the vessel can say the items arrived: while the order is CONFIRMED
 * (the office scheduled the delivery) the Master uploads the delivery receipt, one file at a time or several at once
 * (stageFile; they wait on the order, only the Master sees them, and can be removed again), then confirms receipt with
 * what arrived of each line and when (confirmReceipt): the order is CLOSED, the files are its receipt, and the office
 * is notified. Lines received short keep the order in the office's Overview until an office user marks the shortage
 * handled (markShortageHandled). Every change locks the order's row, so two tabs never confirm twice.
 * What arrived of the provisions goes into the Chief cook's store (StockService.receive) in the same transaction.
 * A confirmed receipt never changes afterwards (CLOSED: no page or service changes its lines, files or time), so the
 * store gets each line once and needs no correction.
 * The vessel's Chief cook may count the delivery first (DeliveryCountService) and add photos of the delivery note
 * (stageFile, removeFiles: his own files, while his count is not with the Master); they are the Master's staged files.
 * Confirming receipt of a count sent to the Master approves it: the cook is the receipt's "counted by".
 * The pages of the supplier's invoice, photographed on "Photograph the invoice" by the Chief cook or the Master,
 * are staged receipt files too (stageInvoicePage, removeInvoicePage): numbered 1, 2, ... without gaps, at most
 * MAX_INVOICE_PAGES, counting against MAX_FILES. Removing or replacing a page drops the invoice lines read from it.
 * A count sent to the Master is approved in one step (approveCount): the count's rows (CountSheet) are the
 * receipt, in the item's unit; invoice lines matched to a vessel item not on the order become lines added on receipt;
 * each line keeps what the invoice said and the office's marks.
 * An order received with a read invoice is TO_MATCH for the office (InvoiceComparisonService.refresh): matching it
 * replaces markShortageHandled, which stays for the orders without one.
 */
@Service
public class OrderReceiptService {

    /** A receipt has at most this many files. */
    public static final int MAX_FILES = 10;

    /** An invoice has at most this many pages. */
    public static final int MAX_INVOICE_PAGES = 6;

    // The kinds of file an invoice page may be, by their first bytes.
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F'};
    private static final List<String> HEIC_BRANDS = List.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1",
            "msf1");

    /** The receipt time may be this far ahead of the server's clock (a ship's clock a little fast). */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(5);

    private final OrderReceiptFileRepository fileRepository;
    private final UserRepository userRepository;
    private final LibraryFileStorage fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final StockService stockService;
    private final EntityManager entityManager;
    private final DeliveryInvoiceRepository invoiceRepository;
    private final DeliveryInvoiceLineRepository invoiceLineRepository;
    private final InvoiceMatchService matchService;
    private final ShipOrderLineRepository orderLineRepository;
    private final InvoiceComparisonService comparisonService;
    private final RequestService requestService;

    public OrderReceiptService(OrderReceiptFileRepository fileRepository, UserRepository userRepository,
                               LibraryFileStorage fileStorage, AuditService auditService,
                               NotificationService notificationService, MessageSource messageSource,
                               StockService stockService, EntityManager entityManager,
                               DeliveryInvoiceRepository invoiceRepository,
                               DeliveryInvoiceLineRepository invoiceLineRepository,
                               InvoiceMatchService matchService, ShipOrderLineRepository orderLineRepository,
                               InvoiceComparisonService comparisonService, RequestService requestService) {
        this.requestService = requestService;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.stockService = stockService;
        this.entityManager = entityManager;
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.matchService = matchService;
        this.orderLineRepository = orderLineRepository;
        this.comparisonService = comparisonService;
    }

    /** What a change of the receipt did. */
    public enum Result {
        DONE,
        // The order is not CONFIRMED (not scheduled yet, or already received): nothing changed.
        NOT_CONFIRMED,
        // stageFile: the receipt already has MAX_FILES files.
        TOO_MANY_FILES,
        // confirmReceipt: no file uploaded.
        NO_FILE,
        // StageFile and removeFiles by the Chief cook: his count is with the Master (or approved). Nothing changed.
        COUNT_SENT,
        // StageInvoicePage: the invoice already has MAX_INVOICE_PAGES pages.
        TOO_MANY_PAGES,
        // StageInvoicePage and removeInvoicePage: its pages are being read; nothing changed.
        READING,
        // ApproveCount: no count of the Chief cook waits for the Master; nothing changed.
        NO_COUNT
    }

    /** True for a received quantity: zero or more, at most 3 decimals and 9 whole digits. */
    public static boolean isValidReceivedQuantity(BigDecimal quantity) {
        return quantity != null && (quantity.signum() == 0 || ShipOrderService.isValidQuantity(quantity));
    }

    /** The receipt's files so far, oldest first (while CONFIRMED, the ones the Master uploaded). */
    @Transactional(readOnly = true)
    public List<OrderReceiptFile> files(Long orderId) {
        return fileRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }

    /**
     * The Master uploads a file of the delivery receipt of a CONFIRMED order of their ship (already checked: PDF, JPEG
     * or PNG, contentType, at most 10 MB). Nothing is recorded or notified until the receipt is confirmed.
     * The vessel's enabled Chief cook may too, while his delivery count is not with the Master (COUNT_SENT); his
     * files are staged for the Master like the Master's own.
     * NOT_CONFIRMED, TOO_MANY_FILES: nothing saved. 404 for an order of another ship; AccessDeniedException when the
     * user is not an enabled Master or Chief cook with a ship.
     */
    @Transactional
    public Result stageFile(User uploader, Long orderId, MultipartFile file, String contentType) {
        ShipOrder order = lockOrder(uploader, orderId, true);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        if (uploader.getRole() == Role.CHIEF_COOK && !order.getDeliveryCountStatus().editable()) {
            return Result.COUNT_SENT;
        }
        if (fileRepository.countByOrderId(orderId) >= MAX_FILES) {
            return Result.TOO_MANY_FILES;
        }
        save(order, uploader, file, contentType, null);
        return Result.DONE;
    }

    /**
     * One photographed page of the supplier's invoice (already checked: invoicePageContentType, at most 10 MB),
     * staged like stageFile's files, by the vessel's enabled Chief cook (while his count is not with the Master) or
     * Master. A new page is numbered after the others; replacing (replaceFileId, Retake) keeps that page's number and
     * drops the old file and the lines read from it. The page waits to be read (WAITING).
     * NOT_CONFIRMED, COUNT_SENT, READING (its pages are being read), TOO_MANY_PAGES, TOO_MANY_FILES: nothing saved.
     * 404 for an order of another ship or a replaceFileId that is not one of its invoice pages.
     */
    @Transactional
    public Result stageInvoicePage(User user, Long orderId, MultipartFile file, String contentType, Long replaceFileId) {
        ShipOrder order = lockOrder(user, orderId, true);
        Result refused = invoicePagesLocked(order, user);
        if (refused != null) {
            return refused;
        }
        Integer page;
        if (replaceFileId != null) {
            OrderReceiptFile old = invoicePage(orderId, replaceFileId);
            page = old.getInvoicePage();
            dropInvoiceLines(orderId, page);
            fileRepository.delete(old);
            fileStorage.deleteLater(old.getStoragePath());
            fileRepository.flush();
        } else {
            long pages = fileRepository.countByOrderIdAndInvoicePageNotNull(orderId);
            if (pages >= MAX_INVOICE_PAGES) {
                return Result.TOO_MANY_PAGES;
            }
            if (fileRepository.countByOrderId(orderId) >= MAX_FILES) {
                return Result.TOO_MANY_FILES;
            }
            page = (int) pages + 1;
        }
        save(order, user, file, contentType, page);
        return Result.DONE;
    }

    /**
     * Removes one page of the invoice (Remove on "Photograph the invoice"); the pages after it move up and so do
     * their lines; the lines read from it are dropped. The same rules as stageInvoicePage.
     */
    @Transactional
    public Result removeInvoicePage(User user, Long orderId, Long fileId) {
        ShipOrder order = lockOrder(user, orderId, true);
        Result refused = invoicePagesLocked(order, user);
        if (refused != null) {
            return refused;
        }
        OrderReceiptFile page = invoicePage(orderId, fileId);
        fileRepository.delete(page);
        fileStorage.deleteLater(page.getStoragePath());
        pageRemoved(orderId, page.getInvoicePage());
        return Result.DONE;
    }

    /** The invoice's pages, by page number. */
    @Transactional(readOnly = true)
    public List<OrderReceiptFile> invoicePages(Long orderId) {
        return fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId);
    }

    /**
     * The content type of a file an invoice page may be, by its first bytes: image/jpeg, image/png, image/webp,
     * image/heic (HEIC and HEIF, as iPhones take them) or application/pdf; null for anything else. (Phones do not
     * always give the file a name with an extension, so only the content decides.)
     */
    public static String invoicePageContentType(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(12);
        } catch (IOException e) {
            return null;
        }
        if (startsWith(head, JPEG)) {
            return "image/jpeg";
        }
        if (startsWith(head, PNG)) {
            return "image/png";
        }
        if (startsWith(head, PDF)) {
            return "application/pdf";
        }
        if (head.length == 12 && new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) {
            return "image/webp";
        }
        if (head.length == 12 && new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")
                && HEIC_BRANDS.contains(new String(head, 8, 4, StandardCharsets.ISO_8859_1))) {
            return "image/heic";
        }
        return null;
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    // Why the invoice's pages cannot change now; null when they can.
    private Result invoicePagesLocked(ShipOrder order, User user) {
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        if (user.getRole() == Role.CHIEF_COOK && !order.getDeliveryCountStatus().editable()) {
            return Result.COUNT_SENT;
        }
        boolean reading = invoiceRepository.findByOrderId(order.getId())
                .filter(invoice -> invoice.getStatus() == DeliveryInvoiceStatus.READING).isPresent();
        return reading ? Result.READING : null;
    }

    private OrderReceiptFile invoicePage(Long orderId, Long fileId) {
        return fileRepository.findByIdAndOrderId(fileId, orderId).filter(OrderReceiptFile::isInvoicePage)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // A page gone: the lines read from it are dropped, the pages after it (files and lines) move up by one.
    private void pageRemoved(Long orderId, int page) {
        dropInvoiceLines(orderId, page);
        fileRepository.flush();
        for (OrderReceiptFile later : fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId)) {
            if (later.getInvoicePage() > page) {
                later.setInvoicePage(later.getInvoicePage() - 1);
            }
        }
        invoiceRepository.findByOrderId(orderId)
                .ifPresent(invoice -> invoiceLineRepository.movePagesUp(invoice.getId(), page));
    }

    private void dropInvoiceLines(Long orderId, int page) {
        invoiceRepository.findByOrderId(orderId).ifPresent(invoice ->
                invoiceLineRepository.deleteAll(invoiceLineRepository.findAllByInvoiceIdAndPage(invoice.getId(), page)));
    }

    private void save(ShipOrder order, User uploader, MultipartFile file, String contentType, Integer invoicePage) {
        OrderReceiptFile receiptFile = new OrderReceiptFile();
        receiptFile.setOrder(order);
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().strip();
        receiptFile.setFileName(name.isEmpty() ? "receipt" : name.length() > 255 ? name.substring(name.length() - 255) : name);
        receiptFile.setFileContentType(contentType);
        receiptFile.setFileSize(file.getSize());
        try (InputStream in = file.getInputStream()) {
            receiptFile.setStoragePath(fileStorage.save(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        receiptFile.setUploadedBy(uploader);
        receiptFile.setUploadedAt(Instant.now());
        if (invoicePage != null) {
            receiptFile.setInvoicePage(invoicePage);
            receiptFile.setInvoicePageStatus(InvoicePageStatus.WAITING);
        }
        fileRepository.save(receiptFile);
    }

    /**
     * The Master removes files they uploaded to a CONFIRMED order's receipt (ids of other orders are ignored); the
     * files are deleted once this commits. NOT_CONFIRMED: nothing removed (a received order's receipt never changes).
     * The Chief cook removes only the files he uploaded, and only while his count is not with the Master
     * (COUNT_SENT).
     */
    @Transactional
    public Result removeFiles(User user, Long orderId, Collection<Long> fileIds) {
        ShipOrder order = lockOrder(user, orderId, true);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        boolean cook = user.getRole() == Role.CHIEF_COOK;
        if (cook && !order.getDeliveryCountStatus().editable()) {
            return Result.COUNT_SENT;
        }
        for (Long id : fileIds) {
            fileRepository.findByIdAndOrderId(id, orderId)
                    .filter(receiptFile -> !cook || (receiptFile.getUploadedBy() != null
                            && receiptFile.getUploadedBy().getId().equals(user.getId())))
                    .ifPresent(receiptFile -> {
                fileRepository.delete(receiptFile);
                fileStorage.deleteLater(receiptFile.getStoragePath());
                // An invoice page removed with the receipt's files.
                if (receiptFile.isInvoicePage()) {
                    pageRemoved(orderId, receiptFile.getInvoicePage());
                }
            });
        }
        return Result.DONE;
    }

    /** What arrived of one line: the quantity (0: not delivered) and the Master's note (optional). */
    public record LineReceipt(BigDecimal received, String note) {
    }

    /** The receipt: when the items arrived, and what arrived of each line by the line's id. */
    public record Receipt(Instant receivedAt, Map<Long, LineReceipt> lines) {
    }

    /**
     * The Master confirms receipt of a CONFIRMED order of their ship: each line not removed by the office gets
     * its received quantity and note, the order gets receivedAt, receivedBy and the number of lines received short,
     * and is CLOSED; the files uploaded are its receipt. Recorded as "Receipt confirmed"; every enabled office user is
     * notified: "MV Demo confirmed receipt of order 12 (2 lines short)". Each PROVISIONS line received above zero
     * adds to the store (StockService.receive; personal items never do), and when any did the ship's enabled Chief
     * cook is told: "Order 12 received: stock updated for 5 items", linking to Stores. NOT_CONFIRMED or NO_FILE:
     * nothing changed. When the Chief cook's count was sent to the Master, confirming approves it (APPROVED; the
     * cook stays the order's countedBy): the audit entry and each RECEIPT movement's note say "Counted by Demo Cook".
     * IllegalArgumentException for a line without a valid received quantity, a too long note, or a receipt time in
     * the future or before the order was sent; 404 and AccessDeniedException as stageFile.
     */
    @Transactional
    public Result confirmReceipt(User master, Long orderId, Receipt receipt) {
        ShipOrder order = lockOrder(master, orderId, false);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        checkReceivedAt(order, receipt.receivedAt());
        List<OrderReceiptFile> receiptFiles = fileRepository.findAllByOrderIdOrderByIdAsc(orderId);
        if (receiptFiles.isEmpty()) {
            return Result.NO_FILE;
        }
        // An invoice read but never matched (no count): matched now, so the office can compare it.
        matchService.ensureMatched(order);
        close(order, master, receipt, receiptFiles);
        return Result.DONE;
    }

    /**
     * Approve: the Master approves the Chief cook's count sent to them in one step, as confirmReceipt: what
     * arrived of each row is the Master's change or the cook's count (a row not counted: nothing arrived), in the
     * item's unit, with the cook's note; receivedAt (the plain receipt's rules), by default when the cook sent the count. An
     * invoice line matched to a vessel item not on the order becomes a line added on receipt (ordered 0); so does a
     * new item's row that arrived (above 0), after its item is made on the vessel (newItem). Each line
     * keeps for the office what the invoice said (invoicedQuantity, invoiceLine) and the marks "match to review" and
     * "conversion to review"; the uncertain matches the cook confirmed are learned for the supplier (the office may correct
     * them). Then CLOSED, stocked and notified as confirmReceipt. NOT_CONFIRMED, NO_COUNT, NO_FILE: nothing changed.
     */
    @Transactional
    public Result approveCount(User master, Long orderId, Instant receivedAt) {
        ShipOrder order = lockOrder(master, orderId, false);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        if (order.getDeliveryCountStatus() != DeliveryCountStatus.SENT_TO_MASTER) {
            return Result.NO_COUNT;
        }
        Instant when = receivedAt != null ? receivedAt : order.getCountSentAt();
        checkReceivedAt(order, when);
        List<OrderReceiptFile> receiptFiles = fileRepository.findAllByOrderIdOrderByIdAsc(orderId);
        if (receiptFiles.isEmpty()) {
            return Result.NO_FILE;
        }
        CountSheet sheet = matchService.sheet(order);
        Map<Long, LineReceipt> lines = new java.util.HashMap<>();
        Map<Long, ShipOrderLine> added = new java.util.HashMap<>();
        for (CountSheet.Row row : sheet.getRows()) {
            BigDecimal received = row.getResultItem() == null ? BigDecimal.ZERO : row.getResultItem();
            boolean newItem = row.isNewItem();
            if (newItem && received.signum() == 0) {
                // A new item that did not arrive: no item, no line; the invoice line stays not on the order.
                continue;
            }
            Item item = newItem ? newItem(order, row, master) : row.getItem();
            // A new item whose name the vessel had may be on the order already (the rows of new items come last).
            ShipOrderLine line = !newItem ? row.getOrderLine() : order.getLines().stream()
                    .filter(l -> !l.isRemoved() && l.getItem().getId().equals(item.getId())).findFirst().orElse(null);
            if (line == null) {
                // An item not on the order: one line per item, added on receipt.
                line = added.computeIfAbsent(item.getId(), itemId -> {
                    ShipOrderLine created = new ShipOrderLine();
                    created.setOrder(order);
                    created.setItem(item);
                    created.setQuantity(BigDecimal.ZERO);
                    created.setAddedOnReceipt(true);
                    order.getLines().add(orderLineRepository.save(created));
                    return created;
                });
            }
            if (row.getExtra() != null) {
                row.getExtra().setOrderLine(line);
                line.setCountedQuantity(add(line.getCountedQuantity(), row.getCountedItem()));
            }
            LineReceipt before = lines.get(line.getId());
            String note = row.getNote();
            if (before != null) {
                received = received.add(before.received());
                note = before.note() == null ? note : note == null ? before.note() : before.note() + "; " + note;
            }
            lines.put(line.getId(), new LineReceipt(received, note == null || note.length() <= ShipOrderLine.NOTE_MAX_LENGTH
                    ? note : note.substring(0, ShipOrderLine.NOTE_MAX_LENGTH)));
            // For the office: the invoice's quantity in the item's unit, its line, the marks.
            BigDecimal invoiced = row.getSource() == CountSheet.Source.INVOICE ? row.toItem(row.getInvoiced()) : null;
            line.setInvoicedQuantity(before == null ? invoiced : add(line.getInvoicedQuantity(), invoiced));
            if (line.getInvoiceLine() == null && !row.getInvoiceLines().isEmpty()) {
                line.setInvoiceLine(row.getInvoiceLines().getFirst());
            }
            line.setMatchToReview(line.isMatchToReview() || row.isMatchToReview());
            line.setConversionToReview(line.isConversionToReview() || row.isConversionToReview());
            // An uncertain match the cook confirmed is learned for the supplier.
            if (row.hasCount() && order.getCountedBy() != null) {
                for (DeliveryInvoiceLine invoiceLine : row.getInvoiceLines()) {
                    if (invoiceLine.getMatch() == InvoiceMatch.CHECK) {
                        matchService.learn(order, invoiceLine, row.getItem(), InvoiceMatcher.learned(
                                invoiceLine.getDescription(), invoiceLine.getUnit(), row.getItem().getUnit(),
                                invoiceLine.getPackFactor()), order.getCountedBy());
                    }
                }
            }
        }
        close(order, master, new Receipt(when, lines), receiptFiles);
        return Result.DONE;
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.add(b);
    }

    // The item of a new item's row that arrived: the vessel's item with that name, or a new one (as the approval of a
    // request makes it: PROVISIONS, active, no storage place, ITEM_CREATED). The invoice line is linked to it, one
    // invoice unit being one of the item's.
    private Item newItem(ShipOrder order, CountSheet.Row row, User master) {
        Item item = requestService.itemFor(order.getShip(), row.getItem().getName(), row.getItem().getUnit(),
                row.getItem().getCategory(), master);
        DeliveryInvoiceLine line = row.getExtra();
        line.setMatch(InvoiceMatch.CHOSEN);
        line.setItem(item);
        line.setPackFactor(BigDecimal.ONE);
        return item;
    }

    // The receipt confirmed: the lines' received quantities and notes, CLOSED, the count approved, the store, the
    // audit entry and the notifications (confirmReceipt, approveCount).
    private void close(ShipOrder order, User master, Receipt receipt, List<OrderReceiptFile> receiptFiles) {
        int shortLines = 0;
        for (ShipOrderLine line : order.getLines()) {
            if (line.isRemoved()) {
                continue;
            }
            LineReceipt lineReceipt = receipt.lines().get(line.getId());
            if (lineReceipt == null || !isValidReceivedQuantity(lineReceipt.received())) {
                throw new IllegalArgumentException("Every line needs a received quantity of zero or more");
            }
            String note = lineReceipt.note() == null || lineReceipt.note().isBlank() ? null : lineReceipt.note().strip();
            if (note != null && note.length() > ShipOrderLine.NOTE_MAX_LENGTH) {
                throw new IllegalArgumentException("A note is at most " + ShipOrderLine.NOTE_MAX_LENGTH + " characters");
            }
            line.setReceivedQuantity(lineReceipt.received());
            line.setReceiptNote(note);
            if (line.getShortBy() != null) {
                shortLines++;
            }
        }
        order.setReceivedAt(receipt.receivedAt().truncatedTo(ChronoUnit.MINUTES));
        order.setReceivedBy(master);
        order.setReceiptConfirmedAt(Instant.now());
        order.setShortLines(shortLines);
        order.setStatus(ShipOrderStatus.CLOSED);
        // Received with a read invoice: the office compares order, delivery and invoice (TO_MATCH).
        comparisonService.refresh(order);
        // A count sent to the Master is approved by confirming; a draft the cook never sent is left as it was.
        String countedBy = null;
        if (order.getDeliveryCountStatus() == DeliveryCountStatus.SENT_TO_MASTER) {
            order.setDeliveryCount(DeliveryCountStatus.APPROVED);
            countedBy = order.getCountedBy() == null ? null : order.getCountedBy().getFullName();
        }
        String stockNote = countedBy == null ? null : "Counted by " + countedBy;

        int stocked = 0;
        for (ShipOrderLine line : order.getLines()) {
            if (!line.isRemoved() && line.getItem().getCategory() == ItemCategory.PROVISIONS
                    && line.getReceivedQuantity().signum() > 0) {
                stockService.receive(order, line, master, stockNote);
                stocked++;
            }
        }

        Ship ship = order.getShip();
        auditService.record("ORDER_RECEIVED", ship, "Order #" + order.getId() + ", received " + order.getReceivedAt()
                + ", " + shortLines + (shortLines == 1 ? " line" : " lines") + " short, " + receiptFiles.size()
                + (receiptFiles.size() == 1 ? " receipt file" : " receipt files")
                + (countedBy == null ? "" : ", counted by " + countedBy), master);
        String text = messageSource.getMessage(shortLines == 0 ? "order.notification.received"
                        : "order.notification.receivedShort",
                new Object[] {ship.getName(), order.getId(), shortLines}, Locale.ENGLISH);
        for (User officeUser : userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER)) {
            if (officeUser.isEnabled()) {
                notificationService.notify(officeUser, NotificationType.ORDER, ship.getName(), text,
                        "/office/orders/" + order.getId());
            }
        }
        if (stocked > 0) {
            String stockText = messageSource.getMessage("order.notification.stocked",
                    new Object[] {order.getId(), stocked}, Locale.ENGLISH);
            for (User cook : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, Role.CHIEF_COOK)) {
                notificationService.notify(cook, NotificationType.ORDER, ship.getName(), stockText, "/ship/stores");
            }
        } else if (countedBy != null && order.getCountedBy().isEnabled()) {
            // The cook hears his count was approved even when nothing went into the store.
            notificationService.notify(order.getCountedBy(), NotificationType.ORDER, ship.getName(),
                    messageSource.getMessage("order.notification.countApproved", new Object[] {order.getId()},
                            Locale.ENGLISH), "/ship/orders/" + order.getId());
        }
    }

    /** What markShortageHandled did. */
    public enum Handled {
        DONE,
        // Not received short, or already handled: nothing changed.
        NOTHING_OPEN,
        NOT_FOUND
    }

    /**
     * An office user marks the shortages of a received order handled, with a note (required, at most
     * ShipOrder.NOTE_MAX_LENGTH): the order leaves the Overview's Needs attention. Recorded as "Shortage handled".
     * IllegalArgumentException for a missing or too long note; AccessDeniedException when the user is not an enabled
     * office user.
     */
    @Transactional
    public Handled markShortageHandled(User officeUser, Long orderId, String note) {
        if (officeUser == null || officeUser.getRole() != Role.OFFICE_USER || !officeUser.isEnabled()) {
            throw new AccessDeniedException("Only the office handles a shortage");
        }
        String text = note == null ? "" : note.strip();
        if (text.isEmpty() || text.length() > ShipOrder.NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A shortage needs a note of at most " + ShipOrder.NOTE_MAX_LENGTH
                    + " characters");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !ShipOrderService.SENT_TO_OFFICE.contains(order.getStatus())) {
            return Handled.NOT_FOUND;
        }
        if (!order.isShortageOpen()) {
            return Handled.NOTHING_OPEN;
        }
        order.setShortageHandledAt(Instant.now());
        order.setShortageHandledBy(officeUser);
        order.setShortageNote(text);
        auditService.record("ORDER_SHORTAGE_HANDLED", order.getShip(), "Order #" + order.getId() + ", "
                + order.getShortLines() + (order.getShortLines() == 1 ? " line" : " lines") + " short: " + text,
                officeUser);
        return Handled.DONE;
    }

    /**
     * A receipt file, opened in the browser (a PDF or an image) or downloaded. 404 when the order has no such
     * file, or when it is not received yet and staged is false (the office sees a receipt once it is confirmed).
     */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> file(ShipOrder order, Long fileId, boolean staged, boolean download) {
        OrderReceiptFile receiptFile = fileRepository.findByIdAndOrderId(fileId, order.getId())
                .filter(f -> staged || order.getStatus() == ShipOrderStatus.CLOSED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ContentDisposition.Builder disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.filename(receiptFile.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(receiptFile.getFileContentType()))
                // A file never changes once uploaded: the browser may keep it for a day (slow links on board).
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(fileStorage.load(receiptFile.getStoragePath()));
    }

    /** True when the time is a possible receipt time of the order: not in the future, not before it was sent. */
    public static boolean isPossibleReceivedAt(ShipOrder order, Instant receivedAt) {
        return receivedAt != null && !receivedAt.isAfter(Instant.now().plus(CLOCK_SLACK))
                && (order.getSentAt() == null || !receivedAt.isBefore(order.getSentAt().truncatedTo(ChronoUnit.MINUTES)));
    }

    private static void checkReceivedAt(ShipOrder order, Instant receivedAt) {
        if (!isPossibleReceivedAt(order, receivedAt)) {
            throw new IllegalArgumentException("A receipt time is not in the future and not before the order was sent");
        }
    }

    // The order of the Master's ship (or, with cookToo, the Chief cook's: the files of his count), its row
    // locked; 404 for another ship's or an OPEN one.
    private ShipOrder lockOrder(User master, Long orderId, boolean cookToo) {
        if (master == null || !master.isEnabled() || master.getShip() == null || !(master.getRole() == Role.MASTER
                || (cookToo && master.getRole() == Role.CHIEF_COOK))) {
            throw new AccessDeniedException("Only the Master of the ship confirms receipt of its order");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !order.getShip().getId().equals(master.getShip().getId())
                || order.getStatus() == ShipOrderStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return order;
    }
}
