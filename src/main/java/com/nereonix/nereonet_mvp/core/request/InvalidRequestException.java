package com.nereonix.nereonet_mvp.core.request;

/**
 * What RequestService.submit and update refuse, with the reason, so a page can show it as a form error without
 * repeating the checks. Still an IllegalArgumentException, as before.
 */
public class InvalidRequestException extends IllegalArgumentException {

    public enum Problem {
        // No ship or nobody who asks.
        NO_SHIP_OR_REQUESTER,
        // Not one line.
        NO_LINES,
        // A line with both, or neither, of an item and a new item name.
        ITEM_OR_NEW_ITEM,
        // A line's item belongs to another ship.
        OTHER_SHIP,
        // A line's quantity is not above zero.
        QUANTITY
    }

    private final Problem problem;

    public InvalidRequestException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    public Problem getProblem() {
        return problem;
    }
}
