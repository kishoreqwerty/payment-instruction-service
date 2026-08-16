package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Vectors are the official SWIFT IBAN registry "IBAN example" for each
 * country. The negative cases are each a positive vector with exactly one
 * digit of the check digits or BBAN changed, so a failure here is
 * specifically a checksum/format defect, not a typo in the test data.
 */
class IbanValidatorTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DE89370400440532013000", // Germany
                "FR1420041010050500013M02606", // France
                "GB29NWBK60161331926819", // United Kingdom
                "NL91ABNA0417164300", // Netherlands
                "BE68539007547034", // Belgium
                "CH9300762011623852957", // Switzerland
                "ES9121000418450200051332", // Spain
                "IT60X0542811101000000123456", // Italy
                "AT611904300234573201", // Austria
                "PL61109010140000071219812874", // Poland
                "SE4550000000058398257466", // Sweden
                "PT50000201231234567890154", // Portugal
                "DK5000400440116243", // Denmark
                "NO9386011117947", // Norway
                "FI2112345600000785", // Finland
                "IE29AIBK93115212345678", // Ireland
                "LU280019400644750000" // Luxembourg
            })
    void acceptsOfficialSwiftRegistryExamples(String iban) {
        assertThat(IbanValidator.isValid(iban)).as("expected %s to be a valid IBAN", iban).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DE88370400440532013000", // Germany, check digits corrupted (89 -> 88)
                "FR1520041010050500013M02606", // France, check digits corrupted (14 -> 15)
                "GB30NWBK60161331926819", // UK, check digits corrupted (29 -> 30)
                "NL92ABNA0417164300", // Netherlands, check digits corrupted
                "DE89370400440532013001" // Germany, BBAN digit corrupted (last 0 -> 1)
            })
    void rejectsIbansWithACorruptedCheckDigit(String iban) {
        assertThat(IbanValidator.isValid(iban)).as("expected %s to fail the mod-97 checksum", iban).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DE8937040044053201300", // Germany, one character short
                "DE893704004405320130000", // Germany, two characters long
            })
    void rejectsWrongLengthForCountry(String iban) {
        assertThat(IbanValidator.isValid(iban)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZ89370400440532013000", "XX91ABNA0417164300"})
    void rejectsUnknownCountryCode(String iban) {
        assertThat(IbanValidator.isValid(iban)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "D", "DE", "DE8"})
    void rejectsTooShortToEvenHaveACountryOrCheckDigits(String iban) {
        assertThat(IbanValidator.isValid(iban)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void rejectsNull() {
        assertThat(IbanValidator.isValid(null)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void isCaseInsensitiveAndIgnoresSpaces() {
        assertThat(IbanValidator.isValid("de89 3704 0044 0532 0130 00")).isTrue();
    }
}
