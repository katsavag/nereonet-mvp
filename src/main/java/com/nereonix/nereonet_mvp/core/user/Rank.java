package com.nereonix.nereonet_mvp.core.user;

// Ranks for ship roles, in the order shown in the forms. Labels: rank.<NAME> in messages.properties.
// Each rank belongs to one department (labels: department.<NAME>), for the crew on board on the ship page.
public enum Rank {
    MASTER(Department.DECK),
    CHIEF_OFFICER(Department.DECK),
    SECOND_OFFICER(Department.DECK),
    THIRD_OFFICER(Department.DECK),
    DECK_CADET(Department.DECK),
    BOSUN(Department.DECK),
    ABLE_SEAMAN(Department.DECK),
    ORDINARY_SEAMAN(Department.DECK),
    CHIEF_ENGINEER(Department.ENGINE),
    SECOND_ENGINEER(Department.ENGINE),
    THIRD_ENGINEER(Department.ENGINE),
    FOURTH_ENGINEER(Department.ENGINE),
    ELECTRO_TECHNICAL_OFFICER(Department.ENGINE),
    ENGINE_CADET(Department.ENGINE),
    FITTER(Department.ENGINE),
    OILER(Department.ENGINE),
    WIPER(Department.ENGINE),
    CHIEF_COOK(Department.GALLEY),
    COOK(Department.GALLEY),
    STEWARD(Department.GALLEY);

    /** In the order shown. A user without a rank has no department (shown as "Rank not set"). */
    public enum Department {
        DECK, ENGINE, GALLEY
    }

    private final Department department;

    Rank(Department department) {
        this.department = department;
    }

    public Department department() {
        return department;
    }
}
