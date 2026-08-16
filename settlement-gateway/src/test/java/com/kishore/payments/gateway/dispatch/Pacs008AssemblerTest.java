package com.kishore.payments.gateway.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.gateway.iso20022.Iso20022Config;
import com.kishore.payments.gateway.iso20022.XmlSchemaValidator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Pacs008AssemblerTest {

    private final Iso20022Config config = new Iso20022Config();
    private final Pacs008Assembler assembler = new Pacs008Assembler(config.pacs008JaxbContext(), config.pacs008Schema(), new XmlSchemaValidator());

    @Test
    void assembledMessageValidatesAgainstTheXsd() {
        PaymentInstructionEntity instruction = routedInstruction(new BigDecimal("1250.75"), "USD");

        byte[] xml = assembler.assemble(instruction);

        var result = new XmlSchemaValidator().validate(xml, config.pacs008Schema());
        assertThat(result.isValid()).as("violations: %s", result.violations()).isTrue();
    }

    @Test
    void uetrLandsInThePaymentIdentificationElement() {
        PaymentInstructionEntity instruction = routedInstruction(new BigDecimal("500.00"), "EUR");

        byte[] xml = assembler.assemble(instruction);
        String xmlString = new String(xml, StandardCharsets.UTF_8);

        assertThat(xmlString).contains("<UETR>" + instruction.getUetr() + "</UETR>");
    }

    @Test
    void amountAndCurrencySerialiseWithCorrectScale() {
        PaymentInstructionEntity instruction = routedInstruction(new BigDecimal("1234.50"), "GBP");

        byte[] xml = assembler.assemble(instruction);
        String xmlString = new String(xml, StandardCharsets.UTF_8);

        assertThat(xmlString).contains("Ccy=\"GBP\"");
        assertThat(xmlString).contains(">1234.50<");
    }

    @Test
    void nonIbanCreditorAccountIsEncodedAsOthr() {
        PaymentInstructionEntity instruction = routedInstruction(new BigDecimal("100.00"), "USD");

        byte[] xml = assembler.assemble(instruction);
        String xmlString = new String(xml, StandardCharsets.UTF_8);

        // The fixture's creditor account (ACCT-9988776) isn't IBAN-shaped.
        assertThat(xmlString).contains("<Othr><Id>ACCT-9988776</Id></Othr>");
    }

    private static PaymentInstructionEntity routedInstruction(BigDecimal amount, String currency) {
        PaymentInstructionEntity instruction = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Alice Debtor",
                "DE89370400440532013000",
                "CHASUS33XXX",
                "Bob Creditor",
                "ACCT-9988776",
                "DEUTDEFFXXX",
                amount,
                currency,
                "SHAR",
                LocalDate.now());
        instruction.setSettlementDate(LocalDate.now().plusDays(1));
        instruction.setSelectedRail("FEDWIRE");
        return instruction;
    }
}
