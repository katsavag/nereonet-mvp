package com.nereonix.nereonet_mvp.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.profile.CrewProfile;
import com.nereonix.nereonet_mvp.core.profile.Languages;
import com.nereonix.nereonet_mvp.core.profile.PhotoImages;
import com.nereonix.nereonet_mvp.core.profile.ProfileService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.ship.Countries;

import jakarta.validation.Valid;

/**
 * My profile (P31): what a seafarer says about themselves, four optional fields, each on its own page with Save and
 * Cancel; Save comes back to My profile with "Saved.". Always the signed-in person's own profile: no URL names a
 * user. Name, rank, department and ship are the office's and only shown. Also "More" (/ship/more), the fifth tab of
 * the phone tab bar: My profile, My crew and Sign out. English only, every ship role (SecurityConfig: /ship/**).
 */
@Controller
public class ProfileController {

    static final long MAX_PHOTO_SIZE = 5 * 1024 * 1024;
    private static final String SAVED = "profile.saved";
    private static final String REDIRECT = "redirect:/ship/profile";

    private final ProfileService profileService;
    private final ConfirmPages confirmPages;

    public ProfileController(ProfileService profileService, ConfirmPages confirmPages) {
        this.profileService = profileService;
        this.confirmPages = confirmPages;
    }

    @GetMapping("/ship/profile")
    public String profile(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        own(currentUser, model);
        return "profile/profile";
    }

    @GetMapping("/ship/more")
    public String more(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        own(currentUser, model);
        return "profile/more";
    }

    // ---- Languages ------------------------------------------------------------------------------------------------

    // languages: the working list (null on opening the page: the saved one); q: the search.
    @GetMapping("/ship/profile/languages")
    public String languages(@AuthenticationPrincipal CurrentUser currentUser,
                            @RequestParam(required = false) String languages, @RequestParam(required = false) String q,
                            Model model) {
        ProfileService.Own own = own(currentUser, model);
        String working = languages != null ? languages : Languages.join(own.profile().getLanguages());
        model.addAttribute("choice", LanguageChoice.of(working, q));
        return "profile/languages";
    }

    @PostMapping("/ship/profile/languages")
    public String saveLanguages(@AuthenticationPrincipal CurrentUser currentUser,
                                @RequestParam(defaultValue = "") String languages, Model model,
                                RedirectAttributes redirectAttributes) {
        List<String> sent = Arrays.stream(languages.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> codes = Languages.parse(languages);
        if (codes.size() != sent.size() || codes.size() > CrewProfile.MAX_LANGUAGES) {
            own(currentUser, model);
            model.addAttribute("choice", LanguageChoice.of(languages, null));
            model.addAttribute("languagesError", codes.size() > CrewProfile.MAX_LANGUAGES
                    ? "profile.languages.error.tooMany" : "profile.languages.error.unknown");
            return "profile/languages";
        }
        profileService.saveLanguages(currentUser.getUsername(), codes);
        return saved(redirectAttributes);
    }

    // ---- Nationality ----------------------------------------------------------------------------------------------

    @GetMapping("/ship/profile/nationality")
    public String nationality(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        ProfileService.Own own = own(currentUser, model);
        model.addAttribute("form", NationalityForm.of(own.profile()));
        return nationalityPage(model);
    }

    @PostMapping("/ship/profile/nationality")
    public String saveNationality(@AuthenticationPrincipal CurrentUser currentUser,
                                  @Valid @ModelAttribute("form") NationalityForm form, BindingResult result,
                                  Model model, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            own(currentUser, model);
            return nationalityPage(model);
        }
        profileService.saveNationality(currentUser.getUsername(), form.value());
        return saved(redirectAttributes);
    }

    private static String nationalityPage(Model model) {
        model.addAttribute("countries", Countries.labels(Locale.ENGLISH));
        return "profile/nationality";
    }

    // ---- Bio ------------------------------------------------------------------------------------------------------

    @GetMapping("/ship/profile/bio")
    public String bio(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        ProfileService.Own own = own(currentUser, model);
        model.addAttribute("form", BioForm.of(own.profile()));
        return "profile/bio";
    }

    @PostMapping("/ship/profile/bio")
    public String saveBio(@AuthenticationPrincipal CurrentUser currentUser, @Valid @ModelAttribute("form") BioForm form,
                          BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            own(currentUser, model);
            return "profile/bio";
        }
        profileService.saveBio(currentUser.getUsername(), form.value());
        return saved(redirectAttributes);
    }

    // ---- Photo ----------------------------------------------------------------------------------------------------

    // ?fileTooLarge: an upload over the multipart limit, sent back here by UploadTooLargeFilter.
    @GetMapping("/ship/profile/photo")
    public String photo(@AuthenticationPrincipal CurrentUser currentUser,
                        @RequestParam(required = false) String fileTooLarge, Model model) {
        own(currentUser, model);
        if (fileTooLarge != null) {
            model.addAttribute("photoError", "profile.photo.error.tooLarge");
        }
        return photoPage(model);
    }

    @PostMapping("/ship/profile/photo")
    public String savePhoto(@AuthenticationPrincipal CurrentUser currentUser,
                            @RequestParam(required = false) MultipartFile photo, Model model,
                            RedirectAttributes redirectAttributes) {
        String error = null;
        if (photo == null || photo.isEmpty()) {
            error = "profile.photo.error.required";
        } else if (photo.getSize() > MAX_PHOTO_SIZE) {
            error = "profile.photo.error.tooLarge";
        } else {
            try {
                profileService.savePhoto(currentUser.getUsername(), photo.getBytes());
            } catch (PhotoImages.UnreadablePhotoException e) {
                error = "profile.photo.error.type";
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (error != null) {
            own(currentUser, model);
            model.addAttribute("photoError", error);
            return photoPage(model);
        }
        return saved(redirectAttributes);
    }

    private static String photoPage(Model model) {
        model.addAttribute("maxPhotoSize", MAX_PHOTO_SIZE);
        return "profile/photo";
    }

    // Where "Delete my profile photo" leads when js/confirm.js did not load (P10).
    @GetMapping("/ship/profile/photo/delete")
    public String confirmDeletePhoto(Model model) {
        return confirmPages.neutral()
                .title("profile.photo.delete.confirm.title")
                .body("profile.photo.delete.confirm.body")
                .confirmLabel("profile.photo.delete.confirm.submit")
                .action("/ship/profile/photo/delete", null)
                .cancelUrl("/ship/profile/photo")
                .crumb("profile.title", "/ship/profile")
                .crumb("profile.field.photo", "/ship/profile/photo")
                .show(model);
    }

    @PostMapping("/ship/profile/photo/delete")
    public String deletePhoto(@AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        profileService.deletePhoto(currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "profile.photo.deleted");
        return REDIRECT;
    }

    // ---- Shared ---------------------------------------------------------------------------------------------------

    // Model: me (User), ship, profile (CrewProfile, empty before the first Save).
    private ProfileService.Own own(CurrentUser currentUser, Model model) {
        ProfileService.Own own = profileService.own(currentUser.getUsername());
        model.addAttribute("me", own.user());
        model.addAttribute("ship", own.user().getShip());
        model.addAttribute("profile", own.profile());
        return own;
    }

    private static String saved(RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("successMessage", SAVED);
        return REDIRECT;
    }
}
