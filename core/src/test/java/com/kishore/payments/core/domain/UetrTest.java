package com.kishore.payments.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UetrTest {

    @Test
    void generateProducesAParsableValue() {
        Uetr uetr = Uetr.generate();
        assertThat(Uetr.parse(uetr.toString())).isEqualTo(uetr);
    }

    @Test
    void parseAcceptsAValidUuid() {
        UUID id = UUID.randomUUID();
        Uetr uetr = Uetr.parse(id.toString());
        assertThat(uetr.value()).isEqualTo(id);
    }

    @Test
    void parseRejectsAMalformedUuid() {
        assertThatThrownBy(() -> Uetr.parse("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> Uetr.parse(null));
    }

    @Test
    void constructorRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new Uetr(null));
    }
}
