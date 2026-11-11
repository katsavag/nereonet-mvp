package com.nereonix.nereonet_mvp.core.user;

// What a user can do (SecurityConfig). Labels: role.<NAME>; for ship roles also role.access.<NAME> (what it allows).
// The office never picks a ship role: it follows from the rank (Rank.role(), P18).
public enum Role {
    OFFICE_USER,
    MASTER,
    CHIEF_COOK,
    CREW_MEMBER;

    /** A ship has at most one enabled user with this role (see UserService.checkShipRoleFree). */
    public boolean isOnePerShip() {
        return this == MASTER || this == CHIEF_COOK;
    }
}
