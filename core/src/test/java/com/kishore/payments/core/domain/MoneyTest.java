package com.kishore.payments.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void zeroIsRejected() {
        assertThatThrownBy(() -> new Money(BigDecimal.ZERO, USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeIsRejected() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-1.00"), USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scale6IsRejected() {
        assertThatThrownBy(() -> new Money(new BigDecimal("1.123456"), USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scale5IsAccepted() {
        Money money = new Money(new BigDecimal("1.12345"), USD);
        assertThat(money.amount()).isEqualByComparingTo("1.12345");
    }

    @Test
    void nullAmountIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null, USD));
    }

    @Test
    void nullCurrencyIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Money(BigDecimal.TEN, null));
    }
}
