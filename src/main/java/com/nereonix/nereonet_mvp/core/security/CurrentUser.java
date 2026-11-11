package com.nereonix.nereonet_mvp.core.security;

import java.util.List;
import java.util.Locale;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import com.nereonix.nereonet_mvp.core.user.Role;

// The logged-in user. Templates read it as ${#authentication.principal}.
public class CurrentUser extends User {

    private final Long id;
    private final String fullName;
    private final Role role;

    public CurrentUser(com.nereonix.nereonet_mvp.core.user.User user) {
        super(user.getEmail(), user.getPasswordHash(), user.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.id = user.getId();
        this.fullName = user.getFullName();
        this.role = user.getRole();
    }

    // The user's id. Pages carry it (<body data-user>), so the drafts js/save.js keeps in the browser are per user and
    // one person's text is never offered to the next person on a shared PC (P15).
    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    // Dashboard for this user's role: OFFICE_USER -> /office, every ship role -> /ship
    public String getHomeUrl() {
        return role == Role.OFFICE_USER ? "/office" : "/ship";
    }

    // "Sofia Marinou" -> "SM"
    public String getInitials() {
        String[] parts = fullName.trim().split("\s+");
        if (parts[0].isEmpty()) {
            return "";
        }
        String initials = parts[0].substring(0, 1);
        if (parts.length > 1) {
            initials += parts[parts.length - 1].substring(0, 1);
        }
        return initials.toUpperCase(Locale.ROOT);
    }
}
