package com.nereonix.nereonet_mvp.core.announcement;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.Setter;

// A message from the office to the crew of the whole fleet or of a chosen set of ships (P28).
// Never edited or deleted after it is sent: it is the proof of what the crew confirmed.
// Before P28 it had one nullable ship_id; AnnouncementTargetMigration moves those rows to fleetWide / targetShips.
@Entity
@Getter
@Setter
public class Announcement {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int BODY_MAX_LENGTH = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = TITLE_MAX_LENGTH)
    private String title;

    // Plain text.
    @Column(nullable = false, updatable = false, length = BODY_MAX_LENGTH)
    private String body;

    // Sent to every active ship; targetShips is then empty. The default lets Hibernate add the column to a table that
    // already has rows (the migration then sets it).
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean fleetWide;

    // The ships it was sent to, by name; empty when fleetWide. Read in batches, so a page of the list loads the
    // targets of all its announcements with one query.
    @ManyToMany
    @JoinTable(name = "announcement_ship", joinColumns = @JoinColumn(name = "announcement_id"),
            inverseJoinColumns = @JoinColumn(name = "ship_id"))
    @OrderBy("name")
    @BatchSize(size = 50)
    private Set<Ship> targetShips = new LinkedHashSet<>();

    // Null when the office user who sent it was deleted (the database sets it to null).
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isFleet() {
        return fleetWide;
    }

    /** The one ship it was sent to; null when it went to the whole fleet or to more than one ship. */
    public Ship getOnlyShip() {
        return !fleetWide && targetShips.size() == 1 ? targetShips.iterator().next() : null;
    }

    /** The target ships' names in name order, comma separated ("MV Alpha, MV Beta"); empty for the whole fleet. */
    public String getTargetNames() {
        return targetShips.stream().map(Ship::getName).collect(Collectors.joining(", "));
    }
}
