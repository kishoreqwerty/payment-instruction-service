package com.kishore.payments.gateway.dispatch;

/** The lifecycle of one dispatch attempt -- not the instruction's own state, see {@link DispatchRecordEntity}. */
public enum DispatchState {
    PENDING,
    ACKNOWLEDGED,
    TIMED_OUT,
    FAILED
}
