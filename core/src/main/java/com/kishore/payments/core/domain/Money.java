package com.kishore.payments.core.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** A positive monetary amount in a single currency. Never a double or a float. */
public record Money(BigDecimal amount, Currency currency) {

    private static final int MAX_SCALE = 5;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Money amount must be positive, was: " + amount);
        }
        if (amount.scale() > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "Money amount scale must not exceed " + MAX_SCALE + ", was: " + amount.scale());
        }
    }
}
