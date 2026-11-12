package com.nereonix.nereonet_mvp.core.profile;

import java.util.List;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * A seafarer's own profile (P31): languages, nationality, bio and photo. Every change is made by the signed-in person
 * to their own profile (the email of the session, never an id from the request), and is recorded in the audit log
 * when it changes something: the field and its new value, the size of a photo, never image data or the bio's text.
 * Saving the same value again changes nothing and records nothing.
 */
@Service
public class ProfileService {

    private final CrewProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final ProfilePhotoStorage photoStorage;
    private final AuditService auditService;

    public ProfileService(CrewProfileRepository profileRepository, UserRepository userRepository,
                          ProfilePhotoStorage photoStorage, AuditService auditService) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.photoStorage = photoStorage;
        this.auditService = auditService;
    }

    /** The signed-in ship user and their profile (an empty, unsaved one when they have never saved anything). */
    public record Own(User user, CrewProfile profile) {
    }

    @Transactional(readOnly = true)
    public Own own(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return new Own(user, profileOf(user));
    }

    /** The profile of any user, or an empty one (the office crew page, the crew home page). */
    @Transactional(readOnly = true)
    public CrewProfile profileOf(User user) {
        return profileRepository.findByUserId(user.getId()).orElseGet(() -> new CrewProfile(user));
    }

    /** codes: valid ISO 639-1 codes, at most five, no repeats (checked by the form). */
    @Transactional
    public void saveLanguages(String email, List<String> codes) {
        CrewProfile profile = editable(email);
        if (profile.getLanguages().equals(codes)) {
            return;
        }
        profile.setLanguages(codes);
        changed(profile, "PROFILE_LANGUAGES_UPDATED", codes.isEmpty() ? "none" : String.join(", ", codes));
    }

    /** code: an ISO 3166 code, or null for "Not given". */
    @Transactional
    public void saveNationality(String email, String code) {
        CrewProfile profile = editable(email);
        if (Objects.equals(profile.getNationality(), code)) {
            return;
        }
        profile.setNationality(code);
        changed(profile, "PROFILE_NATIONALITY_UPDATED", code == null ? "none" : code);
    }

    /** bio: plain text of at most 280 characters, or null for none. The audit entry has its length, not its text. */
    @Transactional
    public void saveBio(String email, String bio) {
        CrewProfile profile = editable(email);
        if (Objects.equals(profile.getBio(), bio)) {
            return;
        }
        profile.setBio(bio);
        changed(profile, "PROFILE_BIO_UPDATED", bio == null ? "none" : bio.length() + " characters");
    }

    /** Re-encodes the upload (PhotoImages) and replaces the photo; the old files go once this has committed. */
    @Transactional
    public void savePhoto(String email, byte[] upload) throws PhotoImages.UnreadablePhotoException {
        PhotoImages.Photo photo = PhotoImages.process(upload);
        CrewProfile profile = editable(email);
        String old = profile.getPhotoKey();
        profile.setPhotoKey(photoStorage.save(photo));
        photoStorage.deleteAfterCommit(old);
        changed(profile, "PROFILE_PHOTO_UPLOADED", photo.width() + "x" + photo.height() + " JPEG");
    }

    @Transactional
    public void deletePhoto(String email) {
        CrewProfile profile = editable(email);
        if (profile.getPhotoKey() == null) {
            return;
        }
        photoStorage.deleteAfterCommit(profile.getPhotoKey());
        profile.setPhotoKey(null);
        changed(profile, "PROFILE_PHOTO_DELETED", null);
    }

    /** A user is being deleted: their photo files go with them (the profile row goes by database cascade). */
    @Transactional
    public void deletePhotoFilesOf(User user) {
        profileRepository.findByUserId(user.getId()).ifPresent(profile -> photoStorage.deleteAfterCommit(profile.getPhotoKey()));
    }

    /** For the office (/office/crew/{id}/photo/...): any crew member's current photo; 404 for anything else. */
    @Transactional(readOnly = true)
    public Resource photoForOffice(Long userId, String key, int size) {
        return profileRepository.findByUserId(userId)
                .filter(profile -> profile.getUser().getRole() != Role.OFFICE_USER)
                .filter(profile -> key.equals(profile.getPhotoKey()))
                .map(profile -> photoStorage.load(key, size))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // The signed-in user's profile, created on the first change. Ship roles only (/ship/** in SecurityConfig).
    private CrewProfile editable(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        if (user.getRole() == Role.OFFICE_USER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return profileRepository.findByUserId(user.getId()).orElseGet(() -> profileRepository.save(new CrewProfile(user)));
    }

    private void changed(CrewProfile profile, String action, String details) {
        profile.touch();
        profileRepository.save(profile);
        auditService.record(action, profile.getUser().getShip(), details);
    }
}
