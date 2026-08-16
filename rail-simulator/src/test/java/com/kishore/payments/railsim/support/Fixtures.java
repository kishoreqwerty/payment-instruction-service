package com.kishore.payments.railsim.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Loads the raw pacs.008 XML fixtures under src/test/resources/samples and
 * substitutes the fields a given test needs to vary, by plain
 * String.replace against the fixed placeholder values baked into
 * valid-pacs008.xml. No templating engine and no programmatic JAXB
 * building -- keeping fixtures as literal, readable XML files makes it easy
 * to eyeball that they are what a real settlement-gateway would actually
 * send.
 */
public final class Fixtures {

    private static final String TEMPLATE_UETR = "8a562c67-ca16-48ba-b074-65581be6f001";
    private static final String TEMPLATE_AMOUNT = "1000.00";
    private static final String TEMPLATE_CURRENCY = "USD";
    private static final String TEMPLATE_CREDITOR_ACCOUNT = "CDTRACCT0001";
    private static final String TEMPLATE_DEBTOR_AGENT_BIC = "CHASUS33XXX";
    private static final String TEMPLATE_END_TO_END_ID = "E2E-0001";

    private Fixtures() {
    }

    public static byte[] sample(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Sample not found on classpath: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read sample: " + name, e);
        }
    }

    /** A valid pacs.008 with a fresh random UETR, so concurrent tests never collide on the same recorded payment. */
    public static Pacs008Fixture validPayment() {
        return new Pacs008Fixture(new String(sample("valid-pacs008.xml"), StandardCharsets.UTF_8))
                .withUetr(UUID.randomUUID().toString());
    }

    public static final class Pacs008Fixture {
        private String xml;

        private Pacs008Fixture(String xml) {
            this.xml = xml;
        }

        private String uetr;

        public Pacs008Fixture withUetr(String uetr) {
            xml = xml.replace(TEMPLATE_UETR, uetr);
            this.uetr = uetr;
            return this;
        }

        public String uetr() {
            return uetr;
        }

        public Pacs008Fixture withAmount(String amount) {
            xml = xml.replace(">" + TEMPLATE_AMOUNT + "<", ">" + amount + "<");
            return this;
        }

        public Pacs008Fixture withCurrency(String currency) {
            xml = xml.replace("Ccy=\"" + TEMPLATE_CURRENCY + "\"", "Ccy=\"" + currency + "\"");
            return this;
        }

        public Pacs008Fixture withCreditorAccountEndsWith(String suffix) {
            xml = xml.replace(TEMPLATE_CREDITOR_ACCOUNT, "CDTRACCT" + suffix);
            return this;
        }

        public Pacs008Fixture withDebtorAgentBic(String bic) {
            xml = xml.replace(TEMPLATE_DEBTOR_AGENT_BIC, bic);
            return this;
        }

        public Pacs008Fixture withEndToEndId(String endToEndId) {
            xml = xml.replace(TEMPLATE_END_TO_END_ID, endToEndId);
            return this;
        }

        public byte[] bytes() {
            return xml.getBytes(StandardCharsets.UTF_8);
        }
    }
}
