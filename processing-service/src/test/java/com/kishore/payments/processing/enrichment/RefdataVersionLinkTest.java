package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kishore.payments.processing.refdata.ReferenceDataService;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefdataVersionLinkTest {

    @Mock
    private ReferenceDataService referenceData;

    @Test
    void recordsTheCurrentRefdataVersionOnTheInstruction() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        when(referenceData.currentVersion()).thenReturn(7L);

        new RefdataVersionLink(referenceData).apply(instruction);

        assertThat(instruction.getRefdataVersion()).isEqualTo(7L);
    }
}
