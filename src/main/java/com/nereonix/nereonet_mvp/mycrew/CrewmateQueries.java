package com.nereonix.nereonet_mvp.mycrew;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.nereonix.nereonet_mvp.core.profile.Languages;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

/**
 * Who is on board a ship now (P31): its users with a crew role (Master, Chief cook, crew members) who are active.
 * Only registered people are users; invited people are invitations and never show. One query, whatever the size of
 * the crew: the users with their profile (left join), selecting only the columns of a CrewmateView.
 */
@Repository
public class CrewmateQueries {

    private static final String SELECT = """
            select u.id, u.fullName, u.rank, p.photoKey, p.languageCodes, p.nationality, p.bio
            from User u left join CrewProfile p on p.user = u
            where u.ship = :ship and u.role <> :office and u.enabled = true""";

    @PersistenceContext
    private EntityManager entityManager;

    /** Everyone on board, unsorted. youId: the signed-in person, marked "you". */
    public List<CrewmateView> onBoard(Ship ship, Long youId) {
        return query(SELECT, ship).getResultList().stream().map(row -> view(row, youId)).toList();
    }

    /** One person, if they are on board this ship now. */
    public Optional<CrewmateView> onBoard(Ship ship, Long id, Long youId) {
        return query(SELECT + " and u.id = :id", ship).setParameter("id", id).getResultList().stream()
                .findFirst().map(row -> view(row, youId));
    }

    private TypedQuery<Object[]> query(String jpql, Ship ship) {
        return entityManager.createQuery(jpql, Object[].class)
                .setParameter("ship", ship)
                .setParameter("office", Role.OFFICE_USER);
    }

    private static CrewmateView view(Object[] row, Long youId) {
        Long id = (Long) row[0];
        String bio = (String) row[6];
        return new CrewmateView(id, (String) row[1], (Rank) row[2], (String) row[3], Languages.parse((String) row[4]),
                (String) row[5], bio == null || bio.isBlank() ? null : bio, id.equals(youId));
    }
}
