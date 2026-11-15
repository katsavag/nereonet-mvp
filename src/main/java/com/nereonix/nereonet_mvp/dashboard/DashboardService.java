package com.nereonix.nereonet_mvp.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.dashboard.AttentionType.Group;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;

/**
 * The office Overview: four stat tiles (StatTiles), a work list of open items (NeedsAttention: paged, filtered by
 * category or vessel), and the cards Next 30 days (NextThirtyDays), Fleet (FleetCard) and Recent activity
 * (RecentActivity); and the first-run checklist (SetupChecklist). This class composes them into the page's model.
 *
 * load() always runs the same 13 queries, whatever the size of the fleet: the active ships with their crew, the
 * eleven of NeedsAttention.find and the newest audit entries; two more when any required document rule is set.
 * DashboardFlowTest counts them. Not read-only: the snooze sync ends the snoozes that no longer apply.
 */
@Service
public class DashboardService {

    /** The sort keys of the "Needs attention" list: most urgent first (the default), or by date. */
    public static final String SORT_URGENCY = NeedsAttention.SORT_URGENCY;
    public static final String SORT_DATE = NeedsAttention.SORT_DATE;

    private final DashboardQueries queries;
    private final NeedsAttention needsAttention;
    private final RecentActivity recentActivity;
    private final SetupChecklist setupChecklist;
    private final Clock clock = Clock.systemUTC();

    public DashboardService(DashboardQueries queries, NeedsAttention needsAttention, RecentActivity recentActivity,
                            SetupChecklist setupChecklist) {
        this.queries = queries;
        this.needsAttention = needsAttention;
        this.recentActivity = recentActivity;
        this.setupChecklist = setupChecklist;
    }

    /**
     * One active ship in the fleet card: its RightShip standing (the Safety Score badge) and its open items, not
     * snoozed. certificateIssues and ordersNotExported are the ship page's rule (ShipPageFlowTest).
     */
    public record FleetRow(FleetShip ship, long crew, RightShipView view, long certificateIssues, long notAcknowledged,
                           long ordersNotExported, long openItems) {
    }

    /** A kind of open item with its number (not snoozed), for the filter chips. */
    public record GroupCount(Group group, long count) {
    }

    /** A coming date of the Next 30 days card: what, which vessel, when, and the page it opens. kind: a message
     *  key's suffix (dashboard.upcoming.<kind>). */
    public record Upcoming(LocalDate date, String kind, String title, FleetShip ship, String url) {
    }

    /**
     * The Overview.
     * fleet          active ships, those with open items first, then by name (the Fleet card shows FleetCard.SHOWN)
     * crewOnBoard    enabled crew on the active ships
     * openItems      every open item of the fleet, not snoozed, whatever the filter (the Items tile)
     * snoozedItems   the items a snooze holds (the tile's detail line)
     * ordersWaiting  orders sent and not yet scheduled (the Orders tile)
     * ordersNotExported  the open Not exported items (the tile's detail line; shortages are not counted)
     * items          one page of the open items the filter keeps, in the chosen order (ListQuery: page, sort)
     * groups         the number of open items per kind (unfiltered), every kind, zero included
     * group, ship    the filter (/office?type=...&shipId=...), each null when not set
     * upcoming       the Next 30 days card's dates, soonest first
     * recent         the newest audit entries
     * checkedAt      when this was loaded
     */
    public record Dashboard(List<FleetRow> fleet, long crewOnBoard, long openItems, long snoozedItems,
                            long ordersWaiting, long ordersNotExported, Page<AttentionItem> items, List<GroupCount> groups, Group group,
                            FleetShip ship, List<Upcoming> upcoming, List<AuditEntry> recent, Instant checkedAt) {

        /** A filter chip's URL: this page with the kind (null for All), keeping the vessel filter and the sort. */
        public String chipUrl(Group kind, boolean byDate) {
            List<String> params = new ArrayList<>();
            if (kind != null) {
                params.add("type=" + kind.param());
            }
            if (ship != null) {
                params.add("shipId=" + ship.id());
            }
            if (byDate) {
                params.add("sort=" + SORT_DATE);
            }
            return params.isEmpty() ? "/office" : "/office?" + String.join("&", params);
        }

        public boolean filtered() {
            return group != null || ship != null;
        }

        public long countOf(Group group) {
            return groups.stream().filter(g -> g.group() == group).mapToLong(GroupCount::count).sum();
        }

        /** As countOf, by the group's name: for the templates, where a static reference is not allowed. */
        public long countOf(String group) {
            return countOf(Group.valueOf(group));
        }

        /** The vessels with open items. */
        public long vesselsWithOpenItems() {
            return fleet.stream().filter(row -> row.openItems() > 0).count();
        }

        /** The Fleet card's rows: at most FleetCard.SHOWN. */
        public List<FleetRow> fleetShown() {
            return fleet.size() <= FleetCard.SHOWN ? fleet : fleet.subList(0, FleetCard.SHOWN);
        }
    }

    /** The unfiltered Overview, page 1, most urgent first. */
    @Transactional
    public Dashboard load() {
        return load(null, null, ListQuery.of("/office", null, ListSort.asc(SORT_URGENCY), ListSort.asc(SORT_DATE)));
    }

    /**
     * The Overview, its open items filtered to one kind (group) and / or one active ship (shipId); null keeps all.
     * An unknown or inactive shipId filters nothing. list: the page and sort of the "Needs attention" list.
     */
    @Transactional
    public Dashboard load(Group group, Long shipId, ListQuery list) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        List<FleetShip> ships = queries.activeShips();
        Map<Long, FleetShip> shipsById = ships.stream()
                .collect(Collectors.toMap(FleetShip::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        NeedsAttention.OpenItems items = needsAttention.find(shipsById, now, today);
        List<AttentionItem> open = items.open();
        StatTiles tiles = StatTiles.of(ships, items);
        FleetShip ship = shipId == null ? null : shipsById.get(shipId);
        List<AuditEntry> recent = recentActivity.newest();

        return new Dashboard(FleetCard.rows(ships, items.rightShip(), open, today), tiles.crewOnBoard(),
                tiles.openItems(), tiles.snoozedItems(), tiles.ordersWaiting(), tiles.ordersNotExported(),
                NeedsAttention.page(open, group, ship, list), NeedsAttention.groups(open), group, ship,
                NextThirtyDays.dates(open, items.orders(), shipsById, today), recent, now);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // First-run setup
    // ---------------------------------------------------------------------------------------------------------------

    /** One step of the setup checklist: key names its texts (dashboard.setup.<key>.*), url the page it is done on. */
    public record SetupStep(String key, boolean done, String url) {
    }

    /** The setup checklist: add a ship, add or import its crew, add a library document, send an announcement. */
    public record Setup(List<SetupStep> steps) {

        /** Every step done: the checklist is not shown. */
        public boolean complete() {
            return steps.stream().allMatch(SetupStep::done);
        }

        /** There is a ship and crew, so there is work to list: the work list is shown (under the checklist). */
        public boolean workListShown() {
            return steps.get(0).done() && steps.get(1).done();
        }

        public long doneCount() {
            return steps.stream().filter(SetupStep::done).count();
        }

        /** The first step not done, or null. */
        public SetupStep next() {
            return steps.stream().filter(step -> !step.done()).findFirst().orElse(null);
        }
    }

    /** The Overview's setup checklist (SetupChecklist), in one query. */
    @Transactional(readOnly = true)
    public Setup setup() {
        return setupChecklist.load();
    }
}
