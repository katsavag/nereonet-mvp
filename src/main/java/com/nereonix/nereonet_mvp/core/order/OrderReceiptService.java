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
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.ship.Ship;
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
 */
@Service
public class OrderReceiptService {

    /** A receipt has at most this many files. */
    public static final int MAX_FILES = 10;

    /** The receipt time may be this far ahead of the server's clock (a ship's clock a little fast). */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(5);

    private final OrderReceiptFileRepository fileRepository;
    private final UserRepository userRepository;
    private final LibraryFileStorage fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final EntityManager entityManager;

    public OrderReceiptService(OrderReceiptFileRepository fileRepository, UserRepository userRepository,
                               LibraryFileStorage fileStorage, AuditService auditService,
                               NotificationService notificationService, MessageSource messageSource,
                               EntityManager entityManager) {
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.entityManager = entityManager;
    }

    /** What a change of the receipt did. */
    public enum Result {
        DONE,
        // The order is not CONFIRMED (not scheduled yet, or already received): nothing changed.
        NOT_CONFIRMED,
        // stageFile: the receipt already has MAX_FILES files.
        TOO_MANY_FILES,
        // confirmReceipt: no file uploaded.
        NO_FILE
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
     * NOT_CONFIRMED, TOO_MANY_FILES: nothing saved. 404 for an order of another ship; AccessDeniedException when the
     * user is not an enabled Master with a ship.
     */
    @Transactional
    public Result stageFile(User master, Long orderId, MultipartFile file, String contentType) {
        ShipOrder order = lockOrder(master, orderId);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        if (fileRepository.countByOrderId(orderId) >= MAX_FILES) {
            return Result.TOO_MANY_FILES;
        }
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
        receiptFile.setUploadedBy(master);
        receiptFile.setUploadedAt(Instant.now());
        fileRepository.save(receiptFile);
        return Result.DONE;
    }

    /**
     * The Master removes files they uploaded to a CONFIRMED order's receipt (ids of other orders are ignored); the
     * files are deleted once this commits. NOT_CONFIRMED: nothing removed (a received order's receipt never changes).
     */
    @Transactional
    public Result removeFiles(User master, Long orderId, Collection<Long> fileIds) {
        ShipOrder order = lockOrder(master, orderId);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        for (Long id : fileIds) {
            fileRepository.findByIdAndOrderId(id, orderId).ifPresent(receiptFile -> {
                fileRepository.delete(receiptFile);
                fileStorage.deleteLater(receiptFile.getStoragePath());
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
     * notified: "MV Demo confirmed receipt of order 12 (2 lines short)". NOT_CONFIRMED or NO_FILE: nothing changed.
     * IllegalArgumentException for a line without a valid received quantity, a too long note, or a receipt time in
     * the future or before the order was sent; 404 and AccessDeniedException as stageFile.
     */
    @Transactional
    public Result confirmReceipt(User master, Long orderId, Receipt receipt) {
        ShipOrder order = lockOrder(master, orderId);
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

        Ship ship = order.getShip();
        auditService.record("ORDER_RECEIVED", ship, "Order #" + order.getId() + ", received " + order.getReceivedAt()
                + ", " + shortLines + (shortLines == 1 ? " line" : " lines") + " short, " + receiptFiles.size()
                + (receiptFiles.size() == 1 ? " receipt file" : " receipt files"), master);
        String text = messageSource.getMessage(shortLines == 0 ? "order.notification.received"
                        : "order.notification.receivedShort",
                new Object[] {ship.getName(), order.getId(), shortLines}, Locale.ENGLISH);
        for (User officeUser : userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER)) {
            if (officeUser.isEnabled()) {
                notificationService.notify(officeUser, NotificationType.ORDER, ship.getName(), text,
                        "/office/orders/" + order.getId());
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

    // The order of the Master's ship, its row locked; 404 for another ship's or an OPEN one.
    private ShipOrder lockOrder(User master, Long orderId) {
        if (master == null || master.getRole() != Role.MASTER || !master.isEnabled() || master.getShip() == null) {
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
