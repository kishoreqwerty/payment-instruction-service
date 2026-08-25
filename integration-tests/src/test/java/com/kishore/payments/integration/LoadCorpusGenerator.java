package com.kishore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 §1/§2: writes the two corpora to disk before any load test runs,
 * so submission-time work (IBAN checksum computation, XML templating) is
 * never on the k6 request path.
 *
 * <p><b>Replay corpus</b> (10,000 rows, seed 42, all valid): every row's
 * {@code endToEndId} is a concrete, final value written once. The replay
 * test submits this exact file three times, relying on the same
 * (debtorAccount, endToEndId) pair recurring across all three submissions
 * to exercise intake's dedup path -- see {@code ReplayIdempotencyLoadTest}.
 *
 * <p><b>Load corpus</b> (20,000 template rows, seed 1729, 8% defect mix):
 * each row's XML carries the literal placeholder token {@code __E2E__} in
 * place of a real EndToEndId/MsgId/PmtInfId. k6 substitutes a genuinely
 * unique value per HTTP request (see {@code load-test/k6/lib/corpus.js}),
 * so 20,000 template rows can be cycled to cover however many requests a
 * multi-hour load profile actually issues, without pre-generating and
 * storing a multi-gigabyte file for what would otherwise be a fixed
 * request ceiling. This is deliberately a smaller, cycled population, not
 * the "unique file per instruction" the replay corpus is -- the load
 * corpus's job is to vary currency/amount/rail/defect mix realistically,
 * not to guarantee no two requests in a 45-minute run ever share a body.
 */
class LoadCorpusGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CORPUS_DIR = Path.of("..", "load-test", "corpus");

    @Test
    void generateReplayCorpus() throws Exception {
        Files.createDirectories(CORPUS_DIR);
        Random random = new Random(42);
        List<String> lines = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            Instruction instr = randomInstruction(random, i, null, false);
            lines.add(JSON.writeValueAsString(toNode(instr)));
        }
        Files.write(
                CORPUS_DIR.resolve("replay-corpus.ndjson"), lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote 10000 rows to load-test/corpus/replay-corpus.ndjson (seed 42, all valid)");
    }

    @Test
    void generateLoadCorpus() throws Exception {
        Files.createDirectories(CORPUS_DIR);
        Random random = new Random(1729);
        List<String> lines = new ArrayList<>(20_000);
        int[] defectCounts = new int[Defect.values().length + 1];
        for (int i = 0; i < 20_000; i++) {
            Defect defect = pickDefect(random);
            Instruction instr = randomInstruction(random, i, defect, true);
            defectCounts[defect == null ? 0 : defect.ordinal() + 1]++;
            lines.add(JSON.writeValueAsString(toNode(instr)));
        }
        Files.write(CORPUS_DIR.resolve("load-corpus.ndjson"), lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote 20000 template rows to load-test/corpus/load-corpus.ndjson (seed 1729)");
        System.out.println("  valid: " + defectCounts[0] + " (" + pct(defectCounts[0]) + "%)");
        for (Defect d : Defect.values()) {
            System.out.println("  " + d + ": " + defectCounts[d.ordinal() + 1] + " (" + pct(defectCounts[d.ordinal() + 1]) + "%)");
        }
    }

    private static String pct(int count) {
        return BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(20_000), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 8% total, four categories at 2% each -- see PHASE-12-REPORT.md for why these four and not the full taxonomy. */
    private enum Defect {
        BAD_DEBTOR_IBAN,
        BAD_CREDITOR_IBAN,
        PAST_EXEC_DATE,
        CURRENCY_COUNTRY_MISMATCH
    }

    private static Defect pickDefect(Random random) {
        double r = random.nextDouble();
        if (r < 0.02) {
            return Defect.BAD_DEBTOR_IBAN;
        } else if (r < 0.04) {
            return Defect.BAD_CREDITOR_IBAN;
        } else if (r < 0.06) {
            return Defect.PAST_EXEC_DATE;
        } else if (r < 0.08) {
            return Defect.CURRENCY_COUNTRY_MISMATCH;
        }
        return null;
    }

    private static ObjectNode toNode(Instruction i) {
        ObjectNode node = JSON.createObjectNode();
        node.put("endToEndId", i.endToEndId);
        node.put("debtorAccount", i.debtorIban);
        node.put("currency", i.currency);
        node.put("amount", i.amount.toPlainString());
        node.put("defect", i.defect == null ? null : i.defect.name());
        node.put("xml", i.xml);
        return node;
    }

    /**
     * Amount distribution: log-normal, median $2,000. Calibrated against the Federal Reserve's own
     * published Fedwire Funds Service 2025 annual statistics (217,296,700 transfers, $1,148,267,267
     * million total value, $5.28 million average -- frbservices.org/resources/financial-services/
     * wires/volume-value-stats/annual-stats.html, fetched 2026-08-23) only for the shape of the
     * right tail, not the median: that $5.28M average describes Fedwire's whole population, which is
     * dominated by wholesale interbank settlement, not the customer-initiated pain.001 credit
     * transfers this generator produces. Forcing the median itself up to that figure would make
     * ACH_EQUIV ($0.01-$149,999.99) and SEPA's typical retail range essentially unreachable and
     * misrepresent what a customer-initiated stream looks like. sigma=2.0 instead sizes the tail so
     * roughly the top 2-3% of USD amounts clear FEDWIRE's $100,000 floor -- enough to exercise all
     * three configured rails (see V2__refdata_schema.sql) under load, with a thin tail reaching past
     * $1M, consistent in shape (not magnitude) with the published large-value concentration.
     */
    private static BigDecimal randomAmount(Random random) {
        double mu = Math.log(2000);
        double sigma = 2.0;
        double value = Math.exp(mu + sigma * random.nextGaussian());
        value = Math.max(value, 1.00);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Load-time-unique token: k6 replaces every occurrence of this in the XML with a real id per HTTP request. */
    private static final String PLACEHOLDER_E2E = "__E2E__";

    private static Instruction randomInstruction(Random random, int sequence, Defect defect, boolean placeholder) {
        boolean usd = random.nextDouble() < 0.5;
        String currency = usd ? "USD" : "EUR";
        String debtorCountry = usd ? "PL" : "DE";
        String creditorCountry = usd ? "GB" : "FR";
        String debtorBic = usd ? "CHASUS33XXX" : "DEUTDEFFXXX";
        String creditorBic = usd ? "CHASUS33XXX" : "DEUTDEFFXXX";

        if (defect == Defect.CURRENCY_COUNTRY_MISMATCH) {
            // EUR currency, debtor account from a country CurrencyConsistentWithDebtorCountryRule
            // maps to a different single currency (GB -> GBP) -- deliberately CURR, not AM02/AG01.
            currency = "EUR";
            debtorCountry = "GB";
            creditorCountry = "FR";
            debtorBic = "DEUTDEFFXXX";
            creditorBic = "DEUTDEFFXXX";
        }

        String debtorIban = randomIban(random, debtorCountry);
        String creditorIban = randomIban(random, creditorCountry);
        if (defect == Defect.BAD_DEBTOR_IBAN) {
            debtorIban = corruptCheckDigits(debtorIban);
        }
        if (defect == Defect.BAD_CREDITOR_IBAN) {
            creditorIban = corruptCheckDigits(creditorIban);
        }

        BigDecimal amount = randomAmount(random);
        LocalDate execDate = defect == Defect.PAST_EXEC_DATE
                ? LocalDate.now(ZoneOffset.UTC).minusDays(1)
                : nextBusinessDay(LocalDate.now(ZoneOffset.UTC).plusDays(1));

        String concreteEndToEndId = "LOAD-" + String.format("%06d", sequence) + "-" + Long.toHexString(random.nextLong() & 0xFFFFFFFFL);
        String xmlEndToEndId = placeholder ? PLACEHOLDER_E2E : concreteEndToEndId;
        String xml = toXml(xmlEndToEndId, debtorIban, debtorBic, creditorIban, creditorBic, currency, amount, execDate);

        return new Instruction(xmlEndToEndId, debtorIban, currency, amount, defect, xml);
    }

    private static LocalDate nextBusinessDay(LocalDate date) {
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private static String toXml(
            String endToEndId, String debtorIban, String debtorBic, String creditorIban, String creditorBic, String currency,
            BigDecimal amount, LocalDate execDate) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n"
                + "  <CstmrCdtTrfInitn>\n"
                + "    <GrpHdr>\n"
                + "      <MsgId>MSG-" + endToEndId + "</MsgId>\n"
                + "      <CreDtTm>" + LocalDate.now(ZoneOffset.UTC) + "T10:00:00</CreDtTm>\n"
                + "      <NbOfTxs>1</NbOfTxs>\n"
                + "      <InitgPty><Nm>Load Test Initiator</Nm></InitgPty>\n"
                + "    </GrpHdr>\n"
                + "    <PmtInf>\n"
                + "      <PmtInfId>PMTINF-" + endToEndId + "</PmtInfId>\n"
                + "      <PmtMtd>TRF</PmtMtd>\n"
                + "      <ReqdExctnDt><Dt>" + execDate + "</Dt></ReqdExctnDt>\n"
                + "      <Dbtr><Nm>Load Test Debtor</Nm></Dbtr>\n"
                + "      <DbtrAcct><Id><IBAN>" + debtorIban + "</IBAN></Id></DbtrAcct>\n"
                + "      <DbtrAgt><FinInstnId><BICFI>" + debtorBic + "</BICFI></FinInstnId></DbtrAgt>\n"
                + "      <ChrgBr>SLEV</ChrgBr>\n"
                + "      <CdtTrfTxInf>\n"
                + "        <PmtId><EndToEndId>" + endToEndId + "</EndToEndId></PmtId>\n"
                + "        <Amt><InstdAmt Ccy=\"" + currency + "\">" + amount + "</InstdAmt></Amt>\n"
                + "        <CdtrAgt><FinInstnId><BICFI>" + creditorBic + "</BICFI></FinInstnId></CdtrAgt>\n"
                + "        <Cdtr><Nm>Load Test Creditor</Nm></Cdtr>\n"
                + "        <CdtrAcct><Id><IBAN>" + creditorIban + "</IBAN></Id></CdtrAcct>\n"
                + "      </CdtTrfTxInf>\n"
                + "    </PmtInf>\n"
                + "  </CstmrCdtTrfInitn>\n"
                + "</Document>\n";
    }

    // -----------------------------------------------------------------------------------------
    // IBAN construction -- ISO 7064 MOD97-10, same algorithm as IbanValidator/
    // EvaluationCaseGeneratorSupport, reimplemented here rather than shared: this is test-scope
    // generation code in a different module from both, and the algorithm is stable, ~15 lines,
    // and already independently duplicated once in this codebase for the same reason.

    private static String randomIban(Random random, String countryCode) {
        int bbanLength = switch (countryCode) {
            case "DE" -> 18;
            case "FR" -> 23;
            case "PL" -> 24;
            case "GB" -> 18;
            default -> throw new IllegalArgumentException("No BBAN length configured for " + countryCode);
        };
        StringBuilder bban = new StringBuilder(bbanLength);
        for (int i = 0; i < bbanLength; i++) {
            bban.append((char) ('0' + random.nextInt(10)));
        }
        return countryCode + checkDigits(countryCode, bban.toString()) + bban;
    }

    private static String corruptCheckDigits(String iban) {
        String correct = iban.substring(2, 4);
        String wrong = correct.equals("00") ? "01" : "00";
        return iban.substring(0, 2) + wrong + iban.substring(4);
    }

    private static String checkDigits(String countryCode, String bban) {
        String rearranged = (bban + countryCode + "00").toUpperCase(Locale.ROOT);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else {
                numeric.append(Character.toUpperCase(c) - 'A' + 10);
            }
        }
        BigInteger value = new BigInteger(numeric.toString());
        int check = 98 - value.mod(BigInteger.valueOf(97)).intValue();
        return String.format("%02d", check);
    }

    private record Instruction(String endToEndId, String debtorIban, String currency, BigDecimal amount, Defect defect, String xml) {
    }
}
