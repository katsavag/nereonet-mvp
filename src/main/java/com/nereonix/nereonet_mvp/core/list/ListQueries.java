package com.nereonix.nereonet_mvp.core.list;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * One page of an entity list, queried in the database (P19): a count, then the rows of the page with LIMIT and
 * OFFSET, sorted in SQL. The caller gives the conditions and the sort as Criteria expressions (left joins for
 * optional associations, so sorting by a column that may be empty never drops a row). A page past the last one
 * shows the last page, so a bookmarked page 9 still shows rows after some were removed.
 * Not a Spring Data repository on purpose: audit entries must never get the delete methods of
 * JpaSpecificationExecutor.
 */
@Component
public class ListQueries {

    /** The WHERE conditions on the root; an empty list means every row. */
    @FunctionalInterface
    public interface Where<T> {
        List<Predicate> build(Root<T> root, CriteriaBuilder cb);
    }

    /** The sort expressions of the list's chosen sort key, most significant first; the id is added after them. */
    @FunctionalInterface
    public interface SortBy<T> {
        List<Expression<?>> build(Root<T> root, CriteriaBuilder cb);
    }

    /** Fetch joins for the page query only (not the count): associations every row shows. */
    @FunctionalInterface
    public interface Fetch<T> {
        void apply(Root<T> root);
    }

    @PersistenceContext
    private EntityManager entityManager;

    public <T> Page<T> page(Class<T> type, ListQuery list, Where<T> where, SortBy<T> sortBy, Fetch<T> fetch) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<T> countRoot = count.from(type);
        count.select(cb.count(countRoot)).where(where.build(countRoot, cb).toArray(Predicate[]::new));
        long total = entityManager.createQuery(count).getSingleResult();

        int page = clamp(list, total);
        if (total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(0, list.getSize()), 0);
        }
        CriteriaQuery<T> query = cb.createQuery(type);
        Root<T> root = query.from(type);
        if (fetch != null) {
            fetch.apply(root);
        }
        List<Order> orders = new ArrayList<>();
        for (Expression<?> expression : sortBy.build(root, cb)) {
            orders.add(list.isDescending() ? cb.desc(expression) : cb.asc(expression));
        }
        // A tie-breaker, so the same URL always shows the same rows in the same order.
        orders.add(list.isDescending() ? cb.desc(root.get("id")) : cb.asc(root.get("id")));
        query.select(root).where(where.build(root, cb).toArray(Predicate[]::new)).orderBy(orders);
        List<T> rows = entityManager.createQuery(query)
                .setFirstResult(page * list.getSize())
                .setMaxResults(list.getSize())
                .getResultList();
        return new PageImpl<>(rows, PageRequest.of(page, list.getSize()), total);
    }

    /** The page to show: the one asked for, or the last one when it is past the end. Zero-based. */
    public static int clamp(ListQuery list, long total) {
        int last = total == 0 ? 0 : (int) ((total - 1) / list.getSize());
        return Math.min(list.getPage(), last);
    }

    /** lower(expression) LIKE the list's search pattern (ListQuery.getContains), with \ as the escape character. */
    public static Predicate contains(CriteriaBuilder cb, Expression<String> expression, String pattern) {
        return cb.like(cb.lower(expression), pattern, '\\');
    }
}
