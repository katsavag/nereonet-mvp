package com.nereonix.nereonet_mvp.core.vetting;

/**
 * RightShip's GHG Rating (P40): A (the best) to E, or U when the vessel is not rated (shown "N/A"). Only A can carry a
 * plus ("A+", ShipRightShipStatus.ghgPlus). Each letter has RightShip's colour, used on the icon only (app.css,
 * --nn-ghg-a ... --nn-ghg-e); the text keeps the page's text colour.
 */
public enum GhgRating {
    A, B, C, D, E, U;

    /** Rated: any letter but U. */
    public boolean rated() {
        return this != U;
    }
}
