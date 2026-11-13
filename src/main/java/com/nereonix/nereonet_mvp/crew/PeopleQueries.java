package com.nereonix.nereonet_mvp.crew;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.requirement.RequirementRules;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * The Crew and Office users lists (P19): users and pending invitations in one list, searched, filtered, sorted and
 * paginated in the database. One SQL query (a UNION ALL of the users and the PENDING invitations) gives the kind and
 * id of the rows of the page, a second one counts them; the page's users and invitations are then loaded by id.
 * Native SQL because JPQL has no portable UNION with LIMIT; it runs on PostgreSQL and H2.
 * Sort keys: name, email, rank (in the Rank order), ship (by name), status (Active, Invited, Invitation expired,
 * Inactive: the order of CrewStatus), documents (P42: the number of required documents missing, expired included;
 * people without required documents and invitations after everyone else).
 */
@Repository
public class PeopleQueries {

    /** One row of the list: a user, or a pending invitation (the other is null). */
    public record Row(User user, Invitation invitation) {
    }

    private static final String RANK_ORDER = rankCase(rank -> Integer.toString(rank.ordinal()), "999");
    private static final String RANK_ROLE = rankCase(rank -> "'" + rank.role().name() + "'", "null");

    // Required documents (P42), the rules of requirement/RequirementRules in SQL, for user u on ship s: the required
    // (not optional) types whose ranks include u's rank, on all vessels or s's type; none without a rank or a ship.
    // A type is held by a certificate of the same type (lower case, trimmed) expiring from today to the P39 limit.
    private static final String REQUIRED_TYPES = " from document_requirement r"
            + " join document_requirement_rank rr on rr.requirement_id = r.id"
            + " where rr.rank = u.rank and u.ship_id is not null and r.optional = false"
            + " and (r.ship_type is null or r.ship_type = s.type)";
    private static final String REQUIRED_COUNT =
            "(select count(distinct lower(trim(r.certificate_type)))" + REQUIRED_TYPES + ")";
    private static final String MISSING_COUNT = "(select count(distinct lower(trim(r.certificate_type)))"
            + REQUIRED_TYPES + " and not exists (select 1 from certificate c where c.user_id = u.id"
            + " and lower(trim(c.type)) = lower(trim(r.certificate_type))"
            + " and c.expires_on >= :today and c.expires_on <= :latest))";
    // The Documents column's sort: the number missing (expired included); -1 without required documents, so those
    // come after everyone whose documents are complete.
    private static final String DOCUMENTS_ORDER =
            "case when " + REQUIRED_COUNT + " = 0 then -1 else " + MISSING_COUNT + " end";

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public PeopleQueries(UserRepository userRepository, InvitationRepository invitationRepository) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
    }

    /**
     * office: the office users (true) or everyone with a ship role (false). shipId, status: the filters, or null.
     * missingDocuments (P42, the Crew list): only users missing a required document (expired counts as missing); no
     * invitations then.
     */
    public Page<Row> page(boolean office, Long shipId, CrewStatus status, boolean missingDocuments, ListQuery list) {
        Map<String, Object> params = new HashMap<>();
        String union = union(office, shipId, status, missingDocuments, list, params);

        long total = ((Number) bind(entityManager.createNativeQuery("select count(*) from (" + union + ") p"), params)
                .getSingleResult()).longValue();
        int page = ListQueries.clamp(list, total);
        if (total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(0, list.getSize()), 0);
        }

        String dir = list.isDescending() ? "desc" : "asc";
        String sql = "select kind, id from (" + union + ") p order by " + sortColumn(list.getSort()) + " " + dir
                + ", sort_name " + dir + ", kind " + dir + ", id " + dir;
        @SuppressWarnings("unchecked")
        List<Object[]> keys = bind(entityManager.createNativeQuery(sql), params)
                .setFirstResult(page * list.getSize())
                .setMaxResults(list.getSize())
                .getResultList();

        List<Long> userIds = new ArrayList<>();
        List<Long> invitationIds = new ArrayList<>();
        for (Object[] key : keys) {
            ("U".equals(String.valueOf(key[0]).strip()) ? userIds : invitationIds).add(((Number) key[1]).longValue());
        }
        // With their ship in the same query, so a page costs the same whatever ships its people are on.
        Map<Long, User> users = (userIds.isEmpty() ? List.<User>of() : userRepository.findAllWithShipByIdIn(userIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Invitation> invitations = invitationRepository.findAllById(invitationIds).stream()
                .collect(Collectors.toMap(Invitation::getId, Function.identity()));
        List<Row> rows = new ArrayList<>();
        for (Object[] key : keys) {
            long id = ((Number) key[1]).longValue();
            rows.add("U".equals(String.valueOf(key[0]).strip())
                    ? new Row(users.get(id), null) : new Row(null, invitations.get(id)));
        }
        return new PageImpl<>(rows, PageRequest.of(page, list.getSize()), total);
    }

    /**
     * Every user of the Crew list as filtered and sorted, without pages and without invitations: the export of their
     * required documents (P42). Two queries, whatever the number.
     */
    public List<User> allUsers(Long shipId, CrewStatus status, boolean missingDocuments, ListQuery list) {
        if (status != null && !status.users()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        String sql = usersPart(false, shipId, status, missingDocuments, list, params);
        String dir = list.isDescending() ? "desc" : "asc";
        @SuppressWarnings("unchecked")
        List<Object[]> keys = bind(entityManager.createNativeQuery("select kind, id from (" + sql + ") p order by "
                + sortColumn(list.getSort()) + " " + dir + ", sort_name " + dir + ", id " + dir), params).getResultList();
        List<Long> ids = keys.stream().map(key -> ((Number) key[1]).longValue()).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userRepository.findAllWithShipByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream().map(users::get).toList();
    }

    /**
     * P18's "Rank needs review" notice, for the Crew list as filtered (every page): the users with a ship role who
     * have no rank or a rank that sets another role (User.rankNeedsReview), by name; since P42 also those without a
     * vessel, as neither has required documents.
     */
    public List<User> rankNeedsReview(Long shipId, CrewStatus status, boolean missingDocuments, ListQuery list) {
        if (status != null && !status.users()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        String sql = "select u.id from users u left join ship s on s.id = u.ship_id "
                + userWhere(false, shipId, status, missingDocuments, list, params)
                + " and (u.rank is null or u.ship_id is null or " + RANK_ROLE.replace("{col}", "u.rank") + " <> u.role)"
                + " order by lower(u.full_name), u.id";
        @SuppressWarnings("unchecked")
        List<Number> ids = bind(entityManager.createNativeQuery(sql), params).getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userRepository.findAllWithShipByIdIn(ids.stream().map(Number::longValue).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        return ids.stream().map(id -> users.get(id.longValue())).toList();
    }

    private static String union(boolean office, Long shipId, CrewStatus status, boolean missingDocuments,
                                ListQuery list, Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        if (status == null || status.users()) {
            parts.add(usersPart(office, shipId, status, missingDocuments, list, params));
        }
        if ((status == null || !status.users()) && !missingDocuments) {
            params.put("now", Instant.now());
            List<String> where = new ArrayList<>();
            where.add("i.status = 'PENDING'");
            where.add(office ? "i.role = 'OFFICE_USER'" : "i.role <> 'OFFICE_USER'");
            if (shipId != null) {
                params.put("shipId", shipId);
                where.add("i.ship_id = :shipId");
            }
            if (status == CrewStatus.INVITED) {
                where.add("i.expires_at >= :now");
            } else if (status == CrewStatus.EXPIRED) {
                where.add("i.expires_at < :now");
            }
            search(list, "i", params, where);
            parts.add("select 'I' as kind, i.id as id, lower(i.full_name) as sort_name, lower(i.email) as sort_email, "
                    + RANK_ORDER.replace("{col}", "i.rank") + " as sort_rank, lower(s.name) as sort_ship, "
                    + "case when i.expires_at < :now then 2 else 1 end as sort_status, "
                    + "-1 as sort_documents "
                    + "from invitation i left join ship s on s.id = i.ship_id where " + String.join(" and ", where));
        }
        if (parts.isEmpty()) {
            // Invitations filtered by Missing documents: nothing, with the same columns.
            parts.add("select 'U' as kind, u.id as id, '' as sort_name, '' as sort_email, 0 as sort_rank, "
                    + "'' as sort_ship, 0 as sort_status, 0 as sort_documents from users u where 1 = 0");
        }
        return String.join(" union all ", parts);
    }

    // The users of the list with their sort columns. The documents sort only matters on the Crew list; office users
    // have no rank, so it is -1 there anyway.
    private static String usersPart(boolean office, Long shipId, CrewStatus status, boolean missingDocuments,
                                    ListQuery list, Map<String, Object> params) {
        return "select 'U' as kind, u.id as id, lower(u.full_name) as sort_name, lower(u.email) as sort_email, "
                + RANK_ORDER.replace("{col}", "u.rank") + " as sort_rank, lower(s.name) as sort_ship, "
                + "case when u.enabled = true then 0 else 3 end as sort_status, "
                + (office ? "-1" : documents(params)) + " as sort_documents "
                + "from users u left join ship s on s.id = u.ship_id "
                + userWhere(office, shipId, status, missingDocuments, list, params);
    }

    // The documents sort expression, with its dates (UTC) bound.
    private static String documents(Map<String, Object> params) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        params.put("today", today);
        params.put("latest", RequirementRules.latestExpiry(today));
        return DOCUMENTS_ORDER;
    }

    // "where ..." for the users table, alias u, joined to its ship as s.
    private static String userWhere(boolean office, Long shipId, CrewStatus status, boolean missingDocuments,
                                    ListQuery list, Map<String, Object> params) {
        List<String> where = new ArrayList<>();
        where.add(office ? "u.role = 'OFFICE_USER'" : "u.role <> 'OFFICE_USER'");
        if (missingDocuments) {
            documents(params);
            where.add(MISSING_COUNT + " > 0");
        }
        if (shipId != null) {
            params.put("shipId", shipId);
            where.add("u.ship_id = :shipId");
        }
        if (status != null) {
            params.put("enabled", status == CrewStatus.ACTIVE);
            where.add("u.enabled = :enabled");
        }
        search(list, "u", params, where);
        return "where " + String.join(" and ", where);
    }

    // Full name or email contains the search, ignoring case.
    private static void search(ListQuery list, String alias, Map<String, Object> params, List<String> where) {
        if (list.getContains() != null) {
            params.put("q", list.getContains());
            where.add("(lower(" + alias + ".full_name) like :q escape '\\' or lower(" + alias
                    + ".email) like :q escape '\\')");
        }
    }

    private static String sortColumn(String key) {
        return switch (key) {
            case "email" -> "sort_email";
            case "rank" -> "sort_rank";
            case "ship" -> "sort_ship";
            case "status" -> "sort_status";
            case "documents" -> "sort_documents";
            default -> "sort_name";
        };
    }

    // "case {col} when 'MASTER' then ... else ... end" over every Rank; {col} is replaced by the column.
    private static String rankCase(Function<Rank, String> value, String otherwise) {
        StringBuilder sql = new StringBuilder("case {col}");
        for (Rank rank : Rank.values()) {
            sql.append(" when '").append(rank.name()).append("' then ").append(value.apply(rank));
        }
        return sql.append(" else ").append(otherwise).append(" end").toString();
    }

    private static Query bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
        return query;
    }
}
