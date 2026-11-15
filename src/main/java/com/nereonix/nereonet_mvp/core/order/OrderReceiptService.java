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
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The receipt of an order on board (P51). Only the vessel can say the items arrived: while the order is CONFIRMED
 * (the office scheduled the delivery) the Master uploads the delivery receipt, one file at a time or several at once
 * (stageFile; they wait on the order, only the Master sees them, and can be removed again), then confirms receipt with
 * what arrived of each line and when (confirmReceipt): the order is CLOSED, the files are its receipt, and the office
 * is notified. Lines received short keep the order in the office's Overview until an office user marks the shortage
 * handled (markShortageHandled). Every change locks the order's row, so two tabs never confirm twice.
 * P56: what arrived of the provisions goes into the Chief cook's store (StockService.receive) in the same transaction.
 * A confirmed receipt never changes afterwards (CLOSED: no page or service changes its lines, files or time), so the
 * store gets each line once and needs no correction.
 * P61: the vessel's Chief cook may count the delivery first (DeliveryCountService) and add photos of the delivery note
 * (stageFile, removeFiles: his own files, while his count is not with the Master); they are the Master's staged files.
 * Confirming receipt of a count sent to the Master approves it: the cook is the receipt's "counted by".
 * P62: the pages of the supplier's invoice, photographed on "Photograph the invoice" by the Chief cook or the Master,
 * are staged receipt files too (stageInvoicePage, removeInvoicePage): numbered 1, 2, ... without gaps, at most
 * MAX_INVOICE_PAGES, counting against MAX_FILES. Removing or replacing a page drops the invoice lines read from it.
 */
@Service
public class OrderReceiptService {

    /** A receipt has at most this many files. */
    public static final int MAX_FILES = 10;

    /** P62: an invoice has at most this many pages. */
    public static final int MAX_INVOICE_PAGES = 6;

    // P62: the kinds of file an invoice page may be, by their first bytes.
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

    public OrderReceiptService(OrderReceiptFileRepository fileRepository, UserRepository userRepository,
                               LibraryFileStorage fileStorage, AuditService auditService,
                               NotificationService notificationService, MessageSource messageSource,
                               StockService stockService, EntityManager entityManager,
                               DeliveryInvoiceRepository invoiceRepository,
                               DeliveryInvoiceLineRepository invoiceLineRepository) {
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
        // P61, stageFile and removeFiles by the Chief cook: his count is with the Master (or approved). Nothing changed.
        COUNT_SENT,
        // P62, stageInvoicePage: the invoice already has MAX_INVOICE_PAGES pages.
        TOO_MANY_PAGES,
        // P62, stageInvoicePage and removeInvoicePage: its pages are being read; nothing changed.
        READING
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
     * P61: the vessel's enabled Chief cook may too, while his delivery count is not with the Master (COUNT_SENT); his
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
     * P62: one photographed page of the supplier's invoice (already checked: invoicePageContentType, at most 10 MB),
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
     * P62: removes one page of the invoice (Remove on "Photograph the invoice"); the pages after it move up and so do
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

    /** P62: the invoice's pages, by page number. */
    @Transactional(readOnly = true)
    public List<OrderReceiptFile> invoicePages(Long orderId) {
        return fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId);
    }

    /**
     * P62: the content type of a file an invoice page may be, by its first bytes: image/jpeg, image/png, image/webp,
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
     * P61: the Chief cook removes only the files he uploaded, and only while his count is not with the Master
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
                // P62: an invoice page removed with the receipt's files.
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
     * The Master confirms receipt of a CONFIRMED order of their ship (P51): each line not removed by the office gets
     * its received quantity and note, the order gets receivedAt, receivedBy and the number of lines received short,
     * and is CLOSED; the files uploaded are its receipt. Recorded as "Receipt confirmed"; every enabled office user is
     * notified: "MV Demo confirmed receipt of order 12 (2 lines short)". P56: each PROVISIONS line received above zero
     * adds to the store (StockService.receive; personal items never do), and when any did the ship's enabled Chief
     * cook is told: "Order 12 received: stock updated for 5 items", linking to Stores. NOT_CONFIRMED or NO_FILE:
     * nothing changed. P61: when the Chief cook's count was sent to the Master, confirming approves it (APPROVED; the
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
        // P61: a count sent to the Master is approved by confirming; a draft the cook never sent is left as it was.
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
        }
        return Result.DONE;
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
     * A receipt file, opened in the browser (a PDF or an image, P22) or downloaded. 404 when the order has no such
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

    // The order of the Master's ship (or, with cookToo, the Chief cook's: P61, the files of his count), its row
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
