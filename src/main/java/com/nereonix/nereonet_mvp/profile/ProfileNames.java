package com.nereonix.nereonet_mvp.profile;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.profile.Languages;
import com.nereonix.nereonet_mvp.ship.Countries;

/**
 * Names of a profile's languages and nationality for the templates (P31), as @profileNames: in English on the crew's
 * pages (English only), in the page language on the office's Profile card ("Ελληνικά", "Ελλάδα").
 */
@Component("profileNames")
public class ProfileNames {

    /** "English, Greek"; empty when there are none. */
    public String languages(List<String> codes, Locale locale) {
        return String.join(", ", Languages.names(codes, locale == null ? Locale.ENGLISH : locale));
    }

    /** "Greece"; null when not given. */
    public String country(String code, Locale locale) {
        return Countries.nameOf(code, locale == null ? Locale.ENGLISH : locale);
    }
}
