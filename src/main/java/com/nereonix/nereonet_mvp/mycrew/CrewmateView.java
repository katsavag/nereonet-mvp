package com.nereonix.nereonet_mvp.mycrew;

import java.util.List;
import java.util.Locale;

import com.nereonix.nereonet_mvp.core.profile.Languages;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.ship.Countries;

/**
 * One person on board as their crewmates see them (P31): the read model of My crew and the crewmate page, and the
 * only thing those templates get. It holds what the office set that everyone on board may see (name, rank) and what
 * the person chose to share (languages, nationality, bio, photo), and nothing else: no email, no dates, no
 * certificates, so none of it can end up in the HTML.
 *   photoKey   the current photo's key (ProfilePhotoStorage), or null
 *   languages  ISO 639-1 codes in the person's order
 *   you        this is the signed-in person
 */
public record CrewmateView(Long id, String fullName, Rank rank, String photoKey, List<String> languages,
                           String nationality, String bio, boolean you) {

    public String initials() {
        return CurrentUser.initialsOf(fullName);
    }

    /** "Maria" of "Maria Santos": "Maria hasn't added anything to their profile yet." */
    public String firstName() {
        return fullName.trim().split("\\s+")[0];
    }

    /** Null without a rank ("Rank not set"). */
    public Rank.Department department() {
        return rank == null ? null : rank.department();
    }

    /** The photo in one of its sizes (512 or 96) for crew pages; null without one. */
    public String photoUrl(int size) {
        return photoKey == null ? null : "/ship/crew/" + id + "/photo/" + photoKey + "/" + size;
    }

    /** English names, in the person's order (crew screens are English only). */
    public List<String> languageNames() {
        return Languages.names(languages, Locale.ENGLISH);
    }

    public String nationalityName() {
        return Countries.nameOf(nationality, Locale.ENGLISH);
    }

    /** At least one of the four is filled in. */
    public boolean hasProfile() {
        return !languages.isEmpty() || nationality != null || bio != null || photoKey != null;
    }
}
