package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.refdata.NostroAccount;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NostroAccountLinkTest {

    @Mock
    private ReferenceDataService referenceData;

    @Test
    void setsNostroAccountWhenOneExistsForTheCorrespondentAndCurrency() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        instruction.setCorrespondentBic("DEUTDEFFXXX");
        when(referenceData.findNostroAccount("DEUTDEFFXXX", "EUR"))
                .thenReturn(Optional.of(new NostroAccount("DEUTDEFFXXX", "EUR", "NOSTRO-EUR-001")));

        new NostroAccountLink(referenceData).apply(instruction);

        assertThat(instruction.getNostroAccount()).isEqualTo("NOSTRO-EUR-001");
    }

    @Test
    void throwsRc01StaticDataWhenNoNostroAccountIsOnFile() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        instruction.setCorrespondentBic("DEUTDEFFXXX");
        when(referenceData.findNostroAccount("DEUTDEFFXXX", "EUR")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new NostroAccountLink(referenceData).apply(instruction))
                .isInstanceOfSatisfying(BusinessFailureException.class, e -> {
                    assertThat(e.stage()).isEqualTo(FailureStage.ENRICHMENT);
                    assertThat(e.details().get(0).reasonCode()).isEqualTo("RC01");
                    assertThat(e.details().get(0).repairability()).isEqualTo(Repairability.STATIC_DATA);
                });
    }
}
