package com.nereonix.nereonet_mvp.ship;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.ship.ImoNumber;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

@Service
public class ShipService {

    private final ShipRepository shipRepository;
    private final AuditService auditService;

    public ShipService(ShipRepository shipRepository, AuditService auditService) {
        this.shipRepository = shipRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Ship> findAll() {
        return shipRepository.findAllByOrderByNameAsc();
    }

    // 404 if there is no such ship.
    @Transactional(readOnly = true)
    public Ship get(Long id) {
        return shipRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Saves a new, active ship. imoNumber may be "IMO 9074729" or "9074729"; only the digits are stored.
     * Throws ImoNumberAlreadyExistsException if a ship with that IMO number exists.
     */
    @Transactional
    public Ship create(String imoNumber, String name, ShipType type, String flag) {
        String digits = ImoNumber.normalize(imoNumber);
        if (!ImoNumber.isValid(digits)) {
            throw new IllegalArgumentException("Invalid IMO number");
        }
        if (shipRepository.existsByImoNumber(digits)) {
            throw new ImoNumberAlreadyExistsException();
        }
        Ship ship = new Ship();
        ship.setImoNumber(digits);
        apply(ship, name, type, flag);
        ship.setActive(true);
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        auditService.record("SHIP_CREATED", ship, describe(ship));
        return ship;
    }

    // Everything except the IMO number, which never changes.
    @Transactional
    public void update(Long id, String name, ShipType type, String flag) {
        Ship ship = get(id);
        apply(ship, name, type, flag);
        auditService.record("SHIP_UPDATED", ship, describe(ship));
    }

    @Transactional
    public void setActive(Long id, boolean active) {
        Ship ship = get(id);
        ship.setActive(active);
        auditService.record(active ? "SHIP_ACTIVATED" : "SHIP_DEACTIVATED", ship, null);
    }

    // For the audit log, e.g. "IMO 9074729, MV Aegean Spirit, BULK_CARRIER, GR"
    private static String describe(Ship ship) {
        return "IMO " + ship.getImoNumber() + ", " + ship.getName() + ", "
                + (ship.getType() == null ? "-" : ship.getType()) + ", "
                + (ship.getFlag() == null ? "-" : ship.getFlag());
    }

    // flag may be "Greece (GR)" or "GR"; only the country code is stored.
    private static void apply(Ship ship, String name, ShipType type, String flag) {
        if (!Countries.isValidOrBlank(flag)) {
            throw new IllegalArgumentException("Unknown country");
        }
        ship.setName(name.trim());
        ship.setType(type);
        ship.setFlag(Countries.toCode(flag));
    }
}
