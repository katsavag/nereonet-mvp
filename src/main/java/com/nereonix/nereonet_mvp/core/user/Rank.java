package com.nereonix.nereonet_mvp.core.user;

import java.util.Arrays;
import java.util.List;

// Ranks for ship roles, in the order shown in the forms. Labels: rank.<NAME> in messages.properties.
// Each rank belongs to one department (labels: department.<NAME>), for the crew on board on the ship page; the rank
// pickers group the ranks by it (groups(), labels rank.group.<NAME>).
// The rank sets the user's role (role(), P18): the office picks a rank, never a role.
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

    /** The role that goes with this rank: Master and Chief Cook their own, every other rank (Chief Engineer included)
     *  CREW_MEMBER. */
    public Role role() {
        return switch (this) {
            case MASTER -> Role.MASTER;
            case CHIEF_COOK -> Role.CHIEF_COOK;
            default -> Role.CREW_MEMBER;
        };
    }

    /** One department and its ranks, for an optgroup of a rank picker. */
    public record Group(Department department, List<Rank> ranks) {
    }

    /** The ranks by department, both in their declared order. */
    public static List<Group> groups() {
        return Arrays.stream(Department.values())
                .map(department -> new Group(department,
                        Arrays.stream(values()).filter(rank -> rank.department == department).toList()))
                .toList();
    }
}
