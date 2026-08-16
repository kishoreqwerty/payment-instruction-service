package com.kishore.payments.processing.validation;

import java.util.Locale;
import java.util.Map;

/**
 * ISO 7064 MOD97-10 IBAN validation, written by hand rather than pulled in
 * as a dependency: it is forty-odd lines, it is a checksum you should be
 * able to explain on a whiteboard, and the country length table below is
 * data, not logic.
 *
 * <p>The algorithm: move the four leading characters (country code + two
 * check digits) to the end, expand every letter to its two-digit value
 * (A=10 ... Z=35), and the resulting decimal number mod 97 must equal 1.
 * The running remainder is computed digit-by-digit rather than via
 * BigInteger -- an IBAN's expanded numeric form can run past 30 digits, far
 * beyond a long, but {@code (remainder * 10 + digit) % 97} never needs more
 * than two digits of headroom.
 */
public final class IbanValidator {

    // IBAN registry-standard total length per country, keyed by the ISO
    // 3166-1 alpha-2 country code that opens every IBAN. Data, not logic:
    // adding a country the business starts supporting is a one-line change
    // here, never a change to the validation algorithm itself.
    private static final Map<String, Integer> COUNTRY_LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28), Map.entry("AT", 20),
            Map.entry("AZ", 28), Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22),
            Map.entry("BH", 22), Map.entry("BR", 29), Map.entry("BY", 28), Map.entry("CH", 21),
            Map.entry("CR", 22), Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22),
            Map.entry("DK", 18), Map.entry("DO", 28), Map.entry("EE", 20), Map.entry("EG", 29),
            Map.entry("ES", 24), Map.entry("FI", 18), Map.entry("FO", 18), Map.entry("FR", 27),
            Map.entry("GB", 22), Map.entry("GE", 22), Map.entry("GI", 23), Map.entry("GL", 18),
            Map.entry("GR", 27), Map.entry("GT", 28), Map.entry("HR", 21), Map.entry("HU", 28),
            Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IQ", 23), Map.entry("IS", 26),
            Map.entry("IT", 27), Map.entry("JO", 30), Map.entry("KW", 30), Map.entry("KZ", 20),
            Map.entry("LB", 28), Map.entry("LC", 32), Map.entry("LI", 21), Map.entry("LT", 20),
            Map.entry("LU", 20), Map.entry("LV", 21), Map.entry("LY", 25), Map.entry("MC", 27),
            Map.entry("MD", 24), Map.entry("ME", 22), Map.entry("MK", 19), Map.entry("MR", 27),
            Map.entry("MT", 31), Map.entry("MU", 30), Map.entry("NL", 18), Map.entry("NO", 15),
            Map.entry("PK", 24), Map.entry("PL", 28), Map.entry("PS", 29), Map.entry("PT", 25),
            Map.entry("QA", 29), Map.entry("RO", 24), Map.entry("RS", 22), Map.entry("SA", 24),
            Map.entry("SC", 31), Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24),
            Map.entry("SM", 27), Map.entry("ST", 25), Map.entry("SV", 28), Map.entry("TL", 23),
            Map.entry("TN", 24), Map.entry("TR", 26), Map.entry("UA", 29), Map.entry("VA", 22),
            Map.entry("VG", 24), Map.entry("XK", 20));

    private IbanValidator() {
    }

    public static boolean isValid(String rawIban) {
        if (rawIban == null) {
            return false;
        }
        String iban = rawIban.replace(" ", "").toUpperCase(Locale.ROOT);
        if (iban.length() < 4 || !isAsciiLetter(iban.charAt(0)) || !isAsciiLetter(iban.charAt(1))) {
            return false;
        }
        Integer expectedLength = COUNTRY_LENGTHS.get(iban.substring(0, 2));
        if (expectedLength == null || iban.length() != expectedLength) {
            return false;
        }
        if (!Character.isDigit(iban.charAt(2)) || !Character.isDigit(iban.charAt(3))) {
            return false;
        }

        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (isAsciiLetter(c)) {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }

        int remainder = 0;
        for (int i = 0; i < numeric.length(); i++) {
            remainder = (remainder * 10 + (numeric.charAt(i) - '0')) % 97;
        }
        return remainder == 1;
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'A' && c <= 'Z';
    }
}
