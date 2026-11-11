package com.nereonix.nereonet_mvp.ship;

// Thrown by ShipService.create when a ship with that IMO number already exists.
public class ImoNumberAlreadyExistsException extends RuntimeException {

    public ImoNumberAlreadyExistsException() {
        super("A ship with this IMO number already exists");
    }
}
