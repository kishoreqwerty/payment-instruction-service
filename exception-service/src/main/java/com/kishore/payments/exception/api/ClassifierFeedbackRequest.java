package com.kishore.payments.exception.api;

/** Body of POST /v1/cases/{caseId}/classifier-feedback: a direct signal from the operator's own "Accept"/"Edit" action on the proposal panel. */
public record ClassifierFeedbackRequest(boolean accepted) {
}
