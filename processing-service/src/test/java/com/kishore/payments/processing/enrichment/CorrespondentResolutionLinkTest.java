package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.refdata.CorrespondentRelationship;
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
class CorrespondentResolutionLinkTest {

    @Mock
    private ReferenceDataService referenceData;

    @Test
    void setsCorrespondentBicWhenARelationshipExists() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        when(referenceData.findCorrespondent(instruction.getCreditorAgentBic()))
                .thenReturn(Optional.of(new CorrespondentRelationship(instruction.getCreditorAgentBic(), "DEUTDEFFXXX", "EUR")));

        new CorrespondentResolutionLink(referenceData).apply(instruction);

        assertThat(instruction.getCorrespondentBic()).isEqualTo("DEUTDEFFXXX");
    }

    @Test
    void throwsRc01StaticDataWhenNoCorrespondentIsOnFile() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        when(referenceData.findCorrespondent(instruction.getCreditorAgentBic())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new CorrespondentResolutionLink(referenceData).apply(instruction))
                .isInstanceOfSatisfying(BusinessFailureException.class, e -> {
                    assertThat(e.stage()).isEqualTo(FailureStage.ENRICHMENT);
                    assertThat(e.details()).hasSize(1);
                    assertThat(e.details().get(0).reasonCode()).isEqualTo("RC01");
                    assertThat(e.details().get(0).repairability()).isEqualTo(Repairability.STATIC_DATA);
                });
    }
}
