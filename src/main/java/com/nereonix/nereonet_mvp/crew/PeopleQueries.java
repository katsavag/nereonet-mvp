package com.nereonix.nereonet_mvp.crew;

import java.time.Instant;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * The Crew and Office users lists (P19): users and pending invitations in one list, searched, filtered, sorted and
 * paginated in the database. One SQL query (a UNION ALL of the users and the PENDING invitations) gives the kind and
 * id of the rows of the page, a second one counts them; the page's users and invitations are then loaded by id.
 * Native SQL because JPQL has no portable UNION with LIMIT; it runs on PostgreSQL and H2.
 * Sort keys: name, email, rank (in the Rank order), ship (by name), status (Active, Invited, Invitation expired,
 * Inactive: the order of CrewStatus).
 */
@Repository
public class PeopleQueries {

    /** One row of the list: a user, or a pending invitation (the other is null). */
    public record Row(User user, Invitation invitation) {
    }

    private static final String RANK_ORDER = rankCase(rank -> Integer.toString(rank.ordinal()), "999");
    private static final String RANK_ROLE = rankCase(rank -> "'" + rank.role().name() + "'", "null");

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
     */
    public Page<Row> page(boolean office, Long shipId, CrewStatus status, ListQuery list) {
        Map<String, Object> params = new HashMap<>();
        String union = union(office, shipId, status, list, params);

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
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
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
     * P18's "Rank needs review" notice, for the Crew list as filtered (every page): the users with a ship role who
     * have no rank or a rank that sets another role (User.rankNeedsReview), by name.
     */
    public List<User> rankNeedsReview(Long shipId, CrewStatus status, ListQuery list) {
        if (status != null && !status.users()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        String sql = "select u.id from users u " + userWhere(false, shipId, status, list, params)
                + " and (u.rank is null or " + RANK_ROLE.replace("{col}", "u.rank") + " <> u.role)"
                + " order by lower(u.full_name), u.id";
        @SuppressWarnings("unchecked")
        List<Number> ids = bind(entityManager.createNativeQuery(sql), params).getResultList();
        Map<Long, User> users = userRepository.findAllById(ids.stream().map(Number::longValue).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        return ids.stream().map(id -> users.get(id.longValue())).toList();
    }

    private static String union(boolean office, Long shipId, CrewStatus status, ListQuery list,
                                Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        if (status == null || status.users()) {
            parts.add("select 'U' as kind, u.id as id, lower(u.full_name) as sort_name, lower(u.email) as sort_email, "
                    + RANK_ORDER.replace("{col}", "u.rank") + " as sort_rank, lower(s.name) as sort_ship, "
                    + "case when u.enabled = true then 0 else 3 end as sort_status "
                    + "from users u left join ship s on s.id = u.ship_id "
                    + userWhere(office, shipId, status, list, params));
        }
        if (status == null || !status.users()) {
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
                    + "case when i.expires_at < :now then 2 else 1 end as sort_status "
                    + "from invitation i left join ship s on s.id = i.ship_id where " + String.join(" and ", where));
        }
        return String.join(" union all ", parts);
    }

    // "where ..." for the users table, alias u.
    private static String userWhere(boolean office, Long shipId, CrewStatus status, ListQuery list,
                                    Map<String, Object> params) {
        List<String> where = new ArrayList<>();
        where.add(office ? "u.role = 'OFFICE_USER'" : "u.role <> 'OFFICE_USER'");
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
