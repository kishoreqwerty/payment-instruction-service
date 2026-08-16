package com.kishore.payments.processing.refdata;

/** One row of refdata.nostro_account: the account this bank holds at a correspondent, for settling in one currency. */
public record NostroAccount(String correspondentBic, String currency, String accountNumber) {
}
