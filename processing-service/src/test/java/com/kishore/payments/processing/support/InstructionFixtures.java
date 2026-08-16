package com.kishore.payments.processing.support;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A minimal, valid-by-default instruction for tests to mutate the one or two fields they actually care about. */
public final class InstructionFixtures {

    private InstructionFixtures() {
    }

    /** Debtor account is a valid German IBAN (EUR, single-currency country) so EUR-flow tests need no further changes. */
    public static PaymentInstructionEntity eurInstruction(BigDecimal amount, LocalDate requestedExecDate) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor GmbH",
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "Creditor SARL",
                "FR1420041010050500013M02606",
                "DEUTDEFFXXX",
                amount,
                "EUR",
                null,
                requestedExecDate);
    }

    /**
     * Debtor account is a valid Polish IBAN -- Poland is deliberately absent
     * from CurrencyConsistentWithDebtorCountryRule's single-currency table,
     * so a USD instruction built from this fixture doesn't trip that rule
     * the way a EUR-zone debtor account would.
     */
    public static PaymentInstructionEntity usdInstruction(BigDecimal amount, LocalDate requestedExecDate) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor Sp",
                "PL61109010140000071219812874",
                "CHASUS33XXX",
                "Creditor Inc",
                "GB29NWBK60161331926819",
                "CHASUS33XXX",
                amount,
                "USD",
                null,
                requestedExecDate);
    }
}
