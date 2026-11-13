package com.nereonix.nereonet_mvp.core.profile;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * What a seafarer chose to say about themselves (P31): up to five languages, a nationality, a short bio and a photo,
 * each optional. One row per user, created on their first Save and deleted with the user (database cascade). Name,
 * rank, department and ship are the office's and stay on User. Visible to everyone on the same ship and to the office.
 */
@Entity
@Table(name = "crew_profile")
public class CrewProfile {

    public static final int MAX_LANGUAGES = 5;
    public static final int BIO_MAX_LENGTH = 280;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    // ISO 639-1 codes in the person's order, comma separated ("en,el"); empty for none. One column, so the crew list
    // needs no extra query for them (see Languages.parse / join).
    @Column(nullable = false, length = 14)
    private String languageCodes = "";

    // ISO 3166 alpha-2 code (as a ship's flag, ship/Countries), or null.
    @Column(length = 2)
    private String nationality;

    // Plain text with line breaks (\n), or null.
    @Column(length = BIO_MAX_LENGTH)
    private String bio;

    // The stored photo's name in ProfilePhotoStorage (a UUID), or null. A new photo gets a new key, so its URLs can be
    // cached for good.
    @Column(length = 36)
    private String photoKey;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CrewProfile() {
    }

    public CrewProfile(User user) {
        this.user = user;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public List<String> getLanguages() {
        return Languages.parse(languageCodes);
    }

    public void setLanguages(List<String> codes) {
        this.languageCodes = Languages.join(codes);
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    public void setPhotoKey(String photoKey) {
        this.photoKey = photoKey;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * The URL of the person's own photo as the crew pages serve it (P31), with the profile's version so a new photo
     * shows at once despite the cache header (P43): "/ship/crew/12/photo/<key>/96?v=1763000000"; null without a
     * photo. size: 96 or 512. The one place that builds it (layout/parts :: personAvatar shows it).
     */
    public String photoUrl(int size) {
        if (photoKey == null) {
            return null;
        }
        return "/ship/crew/" + user.getId() + "/photo/" + photoKey + "/" + size + "?v=" + updatedAt.getEpochSecond();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
