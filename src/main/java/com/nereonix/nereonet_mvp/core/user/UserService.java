package com.nereonix.nereonet_mvp.core.user;

import java.time.Instant;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ship.Ship;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Sets a new password (P14, password reset). Only its hash is stored. */
    @Transactional
    public void changePassword(User user, String rawPassword) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    /** rank is for ship roles only and may be null. */
    @Transactional
    public User createUser(String email, String fullName, String rawPassword, Role role, Ship ship,
                           Rank rank) {
        if (role == Role.OFFICE_USER && ship != null) {
            throw new IllegalArgumentException("An office user cannot belong to a ship");
        }
        if (role != Role.OFFICE_USER && ship == null) {
            throw new IllegalArgumentException("Role " + role + " requires a ship");
        }

        User user = new User();
        user.setEmail(email.trim().toLowerCase(Locale.ROOT));
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setShip(ship);
        user.setRank(role == Role.OFFICE_USER ? null : rank);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    /**
     * A ship has at most one enabled MASTER and one enabled CHIEF_COOK.
     * Throws ShipRoleTakenException if giving role on ship to user would break that.
     * user is the crew member being edited, or null for a new crew member (an invitation).
     * A disabled user does not count, so editing one never breaks the rule.
     * The exception does not mark the caller's transaction rollback-only: the crew import catches it and reports it
     * on the row (P18).
     */
    @Transactional(readOnly = true, noRollbackFor = ShipRoleTakenException.class)
    public void checkShipRoleFree(Ship ship, Role role, User user) {
        if (role != Role.MASTER && role != Role.CHIEF_COOK) {
            return;
        }
        if (user != null && !user.isEnabled()) {
            return;
        }
        boolean taken = userRepository.findAllByShipAndRoleAndEnabledTrue(ship, role).stream()
                .anyMatch(other -> user == null || !other.getId().equals(user.getId()));
        if (taken) {
            throw new ShipRoleTakenException(role);
        }
    }

    @Transactional(readOnly = true)
    public boolean officeUserExists() {
        return userRepository.existsByRole(Role.OFFICE_USER);
    }
}
