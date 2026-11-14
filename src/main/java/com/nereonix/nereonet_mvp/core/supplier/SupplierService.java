package com.nereonix.nereonet_mvp.core.supplier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

// The office's suppliers (P44): the list, adding and editing. A supplier is never deleted (its RFQs keep it);
// "active" takes it out of new RFQs.
@Service
public class SupplierService {

    /** What the office enters for a supplier. Values already checked by the form (SupplierForm). */
    public record Details(String name, String email, String portsServed, String currency, String website,
                          boolean active) {
    }

    /** The name is taken by another supplier (ignoring case). */
    public static class DuplicateNameException extends RuntimeException {
        public DuplicateNameException() {
            super("A supplier with this name exists");
        }
    }

    private final SupplierRepository supplierRepository;
    private final ListQueries listQueries;
    private final AuditService auditService;

    public SupplierService(SupplierRepository supplierRepository, ListQueries listQueries, AuditService auditService) {
        this.supplierRepository = supplierRepository;
        this.listQueries = listQueries;
        this.auditService = auditService;
    }

    /**
     * One page of suppliers. active: true keeps the active ones, false the inactive ones, null all. The search looks
     * in the name, email and ports served. Sorts: name (default), currency.
     */
    @Transactional(readOnly = true)
    public Page<Supplier> find(Boolean active, ListQuery list) {
        return listQueries.page(Supplier.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (active != null) {
                where.add(cb.equal(root.get("active"), active));
            }
            String q = list.getContains();
            if (q != null) {
                where.add(cb.or(ListQueries.contains(cb, root.get("name"), q),
                        ListQueries.contains(cb, root.get("email"), q),
                        ListQueries.contains(cb, root.get("portsServed"), q)));
            }
            return where;
        }, (root, cb) -> {
            List<Expression<?>> sort = new ArrayList<>();
            if ("currency".equals(list.getSort())) {
                sort.add(root.get("currency"));
            }
            sort.add(cb.lower(root.get("name")));
            return sort;
        }, null);
    }

    @Transactional(readOnly = true)
    public Optional<Supplier> find(Long id) {
        return supplierRepository.findById(id);
    }

    /** The suppliers an RFQ can ask: the active ones, by name. */
    @Transactional(readOnly = true)
    public List<Supplier> active() {
        return supplierRepository.findAllByActiveTrueOrderByNameAsc();
    }

    /** Adds a supplier (channel EMAIL). Recorded as "Supplier added". DuplicateNameException for a name in use. */
    @Transactional
    public Supplier create(User officeUser, Details details) {
        if (supplierRepository.existsByNameIgnoreCase(details.name().strip())) {
            throw new DuplicateNameException();
        }
        Supplier supplier = new Supplier();
        apply(supplier, details);
        supplier.setChannel(ChannelType.EMAIL);
        supplier.setCreatedAt(Instant.now());
        supplierRepository.save(supplier);
        auditService.record("SUPPLIER_ADDED", null, supplier.getName() + ", " + supplier.getEmail(), officeUser);
        return supplier;
    }

    /** Changes a supplier. Recorded as "Supplier changed". DuplicateNameException for a name another one uses. */
    @Transactional
    public Supplier update(User officeUser, Supplier supplier, Details details) {
        if (supplierRepository.existsByNameIgnoreCaseAndIdNot(details.name().strip(), supplier.getId())) {
            throw new DuplicateNameException();
        }
        apply(supplier, details);
        auditService.record("SUPPLIER_UPDATED", null, supplier.getName() + ", " + supplier.getEmail()
                + (supplier.isActive() ? "" : ", inactive"), officeUser);
        return supplier;
    }

    private static void apply(Supplier supplier, Details details) {
        supplier.setName(details.name().strip());
        supplier.setEmail(details.email().strip());
        supplier.setPortsServed(blankToNull(details.portsServed()));
        supplier.setCurrency(details.currency().strip().toUpperCase(Locale.ROOT));
        supplier.setWebsite(blankToNull(details.website()));
        supplier.setActive(details.active());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
