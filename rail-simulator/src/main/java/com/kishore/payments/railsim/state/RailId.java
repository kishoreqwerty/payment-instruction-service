package com.kishore.payments.railsim.state;

/** The three rails this simulator stands in for. Same code, independent state, independent scenario per rail -- see RailStateRegistry. */
public enum RailId {
    FEDWIRE,
    SEPA,
    ACH_EQUIV
}
