package com.nereonix.nereonet_mvp.mycrew;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.profile.ProfilePhotoStorage;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * My crew (P31): who is on board the signed-in person's ship now, read only. The same number of queries whatever the
 * size of the crew: the signed-in user (with their ship), then one CrewmateQueries query for everyone and their
 * profiles (MyCrewFlowTest checks it). Anyone not on board that ship now (another ship, invited, deactivated, an
 * office user, or no such user) is a 404, never a 403, so nobody can probe who exists.
 */
@Service
public class MyCrewService {

    /** department: null for the people without a rank ("Rank not set", last). */
    public record Group(Rank.Department department, List<CrewmateView> people) {
    }

    public record MyCrew(Ship ship, int count, List<Group> groups) {
    }

    private static final Comparator<CrewmateView> ORDER = Comparator
            .comparing(CrewmateView::department, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CrewmateView::rank, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CrewmateView::fullName, Collator.getInstance(Locale.ENGLISH))
            .thenComparing(CrewmateView::id);

    private final UserRepository userRepository;
    private final CrewmateQueries crewmateQueries;
    private final ProfilePhotoStorage photoStorage;

    public MyCrewService(UserRepository userRepository, CrewmateQueries crewmateQueries,
                         ProfilePhotoStorage photoStorage) {
        this.userRepository = userRepository;
        this.crewmateQueries = crewmateQueries;
        this.photoStorage = photoStorage;
    }

    /** By department (Deck, Engine, Galley, then "Rank not set"), then rank order, then name. */
    @Transactional(readOnly = true)
    public MyCrew list(String email) {
        User me = userRepository.findByEmail(email).orElseThrow();
        List<CrewmateView> people = crewmateQueries.onBoard(me.getShip(), me.getId()).stream().sorted(ORDER).toList();
        List<Group> groups = new ArrayList<>();
        for (CrewmateView person : people) {
            if (groups.isEmpty() || groups.getLast().department() != person.department()) {
                groups.add(new Group(person.department(), new ArrayList<>()));
            }
            groups.getLast().people().add(person);
        }
        return new MyCrew(me.getShip(), people.size(), groups.stream().map(g -> new Group(g.department(), List.copyOf(g.people()))).toList());
    }

    @Transactional(readOnly = true)
    public CrewmateView crewmate(String email, Long id) {
        User me = userRepository.findByEmail(email).orElseThrow();
        return crewmateQueries.onBoard(me.getShip(), id, me.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** A crewmate's current photo in one size; 404 unless they are on board now and the key is their photo's. */
    @Transactional(readOnly = true)
    public Resource photo(String email, Long id, String key, int size) {
        CrewmateView person = crewmate(email, id);
        Resource file = key.equals(person.photoKey()) ? photoStorage.load(key, size) : null;
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return file;
    }
}
