package com.nereonix.nereonet_mvp.core.ship;

// IMO ship identification number: 7 digits, the last one a check digit.
public final class ImoNumber {

    private ImoNumber() {
    }

    // "IMO 907 4729" -> "9074729". Does not validate.
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String value = input.trim();
        if (value.regionMatches(true, 0, "IMO", 0, 3)) {
            value = value.substring(3);
        }
        return value.replaceAll("\\s", "");
    }

    // Exactly 7 digits (the form, before the check digit is looked at).
    public static boolean hasSevenDigits(String digits) {
        return digits != null && digits.matches("[0-9]{7}");
    }

    // The first six digits times 7, 6, 5, 4, 3, 2, summed: the last digit of the sum is the check digit.
    public static boolean isValid(String digits) {
        if (!hasSevenDigits(digits)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 6; i++) {
            sum += (digits.charAt(i) - '0') * (7 - i);
        }
        return sum % 10 == digits.charAt(6) - '0';
    }
}
