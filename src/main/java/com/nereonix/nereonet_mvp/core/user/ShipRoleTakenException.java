package com.nereonix.nereonet_mvp.core.user;

// Thrown by UserService.checkShipRoleFree: the ship already has an enabled MASTER or CHIEF_COOK.
public class ShipRoleTakenException extends RuntimeException {

    public ShipRoleTakenException(Role role) {
        super("The ship already has an enabled " + role);
    }
}
