package com.kishore.payments.railsim.api;

import com.kishore.payments.railsim.callback.CallbackSender;
import com.kishore.payments.railsim.dispatch.ConnectionDropper;
import com.kishore.payments.railsim.dispatch.InboundPayment;
import com.kishore.payments.railsim.dispatch.Pacs008Parser;
import com.kishore.payments.railsim.iso20022.XmlSchemaValidator;
import com.kishore.payments.railsim.scenario.AcceptResponse;
import com.kishore.payments.railsim.scenario.BehaviorSpec;
import com.kishore.payments.railsim.scenario.ConfirmationType;
import com.kishore.payments.railsim.scenario.ScenarioConfig;
import com.kishore.payments.railsim.scenario.ScenarioLoader;
import com.kishore.payments.railsim.state.RailId;
import com.kishore.payments.railsim.state.RailState;
import com.kishore.payments.railsim.state.RailStateRegistry;
import com.kishore.payments.railsim.state.RecordedPayment;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.xml.validation.Schema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/rail/{railId}")
public class RailController {

    private final RailStateRegistry railStateRegistry;
    private final XmlSchemaValidator xmlSchemaValidator;
    private final Schema pacs008Schema;
    private final Pacs008Parser pacs008Parser;
    private final ConnectionDropper connectionDropper;
    private final CallbackSender callbackSender;
    private final ScenarioLoader scenarioLoader;
    private final ScheduledExecutorService scheduler;
    private final String defaultCallbackUrl;

    public RailController(
            RailStateRegistry railStateRegistry,
            XmlSchemaValidator xmlSchemaValidator,
            @Qualifier("pacs008Schema") Schema pacs008Schema,
            Pacs008Parser pacs008Parser,
            ConnectionDropper connectionDropper,
            CallbackSender callbackSender,
            ScenarioLoader scenarioLoader,
            ScheduledExecutorService railSimulatorScheduler,
            @Value("${railsim.default-callback-url}") String defaultCallbackUrl) {
        this.railStateRegistry = railStateRegistry;
        this.xmlSchemaValidator = xmlSchemaValidator;
        this.pacs008Schema = pacs008Schema;
        this.pacs008Parser = pacs008Parser;
        this.connectionDropper = connectionDropper;
        this.callbackSender = callbackSender;
        this.scenarioLoader = scenarioLoader;
        this.scheduler = railSimulatorScheduler;
        this.defaultCallbackUrl = defaultCallbackUrl;
    }

    @PostMapping(path = "/payments", consumes = MediaType.APPLICATION_XML_VALUE)
    public DeferredResult<ResponseEntity<?>> receivePayment(
            @PathVariable String railId, @RequestBody byte[] body, HttpServletRequest request) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        DeferredResult<ResponseEntity<?>> deferred = new DeferredResult<>();

        XmlSchemaValidator.ValidationResult validation = xmlSchemaValidator.validate(body, pacs008Schema);
        if (!validation.wellFormed()) {
            deferred.setResult(ResponseEntity.badRequest().body(new ErrorResponse("MALFORMED_XML", "The request body is not well-formed XML.")));
            return deferred;
        }
        if (!validation.violations().isEmpty()) {
            deferred.setResult(ResponseEntity.badRequest().body(new SchemaInvalidResponse("SCHEMA_INVALID", validation.violations())));
            return deferred;
        }

        InboundPayment payment = pacs008Parser.parse(body);
        long requestOrdinal = railState.nextRequestOrdinal();
        int deliveryAttempt = railState.nextDeliveryAttempt(payment.uetr());
        ScenarioConfig scenario = railState.scenario();
        BehaviorSpec behavior = scenario.resolve(payment, requestOrdinal, deliveryAttempt);
        String statusCallbackUrl = scenario.statusCallbackUrl() != null ? scenario.statusCallbackUrl() : defaultCallbackUrl;
        String returnCallbackUrl = scenario.returnCallbackUrl() != null ? scenario.returnCallbackUrl() : defaultCallbackUrl;

        dispatch(railState, payment, body, behavior, statusCallbackUrl, returnCallbackUrl, request, deferred);
        return deferred;
    }

    private void dispatch(
            RailState railState,
            InboundPayment payment,
            byte[] rawPayload,
            BehaviorSpec behavior,
            String statusCallbackUrl,
            String returnCallbackUrl,
            HttpServletRequest request,
            DeferredResult<ResponseEntity<?>> deferred) {
        AcceptResponse acceptResponse = behavior.acceptResponse();
        long acceptDelayMs = behavior.acceptDelayMs() != null ? behavior.acceptDelayMs() : 0;

        switch (acceptResponse) {
            case DROP -> {
                boolean recordBeforeTimeout = Boolean.TRUE.equals(behavior.recordBeforeTimeout());
                if (recordBeforeTimeout) {
                    railState.record(new RecordedPayment(payment, Instant.now(), rawPayload));
                    // Unlike a payment the rail never accepted at all
                    // (recordBeforeTimeout: false, nothing to confirm,
                    // regardless of what `confirmation` says), one recorded
                    // before the connection reset genuinely was received --
                    // only the response back to the client was lost, not the
                    // request. That is exactly the "response lost, not the
                    // request" world .notes/ARCHITECTURE.md §6.4 describes,
                    // and a real rail in that world can still confirm later,
                    // out of band. Scheduled the same way ACCEPT/TIMEOUT do.
                    scheduleConfirmationIfConfigured(railState, payment, behavior, acceptResponse, statusCallbackUrl, returnCallbackUrl, 0);
                }
                connectionDropper.drop(request);
                // If drop() returns normally, the connection is already
                // closed and no result is ever set here -- setting one
                // would just be Tomcat discarding a write to a dead socket.
                // If drop() cannot perform the drop, it throws, and that
                // propagates as a normal synchronous 500 (the request
                // hasn't returned yet) rather than silently succeeding into
                // a scenario that looks like TIMEOUT.
            }
            case ACCEPT, REJECT_SYNC -> scheduler.schedule(
                    () -> {
                        railState.record(new RecordedPayment(payment, Instant.now(), rawPayload));
                        scheduleConfirmationIfConfigured(railState, payment, behavior, acceptResponse, statusCallbackUrl, returnCallbackUrl, 0);
                        deferred.setResult(ResponseEntity.status(HttpStatus.ACCEPTED).build());
                    },
                    acceptDelayMs,
                    TimeUnit.MILLISECONDS);
            case REJECT_4XX -> scheduler.schedule(
                    () -> deferred.setResult(ResponseEntity.badRequest().body(new ErrorResponse(
                            "REJECTED", behavior.rejectReasonCode() != null ? behavior.rejectReasonCode() : "Rejected by rail"))),
                    acceptDelayMs,
                    TimeUnit.MILLISECONDS);
            case ERROR_5XX -> scheduler.schedule(
                    () -> {
                        int attempt = railState.nextErrorAttempt(payment.uetr());
                        Integer errorCount = behavior.errorCount();
                        boolean stillErroring = errorCount == null || attempt <= errorCount;
                        if (stillErroring) {
                            deferred.setResult(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body(new ErrorResponse("SERVER_ERROR", "Rail server error on attempt " + attempt)));
                        } else {
                            railState.record(new RecordedPayment(payment, Instant.now(), rawPayload));
                            scheduleConfirmationIfConfigured(railState, payment, behavior, acceptResponse, statusCallbackUrl, returnCallbackUrl, 0);
                            deferred.setResult(ResponseEntity.status(HttpStatus.ACCEPTED).build());
                        }
                    },
                    acceptDelayMs,
                    TimeUnit.MILLISECONDS);
            case TIMEOUT -> {
                long timeoutHoldMs = behavior.timeoutHoldMs() != null ? behavior.timeoutHoldMs() : 0;
                boolean recordBeforeTimeout = Boolean.TRUE.equals(behavior.recordBeforeTimeout());
                if (recordBeforeTimeout) {
                    railState.record(new RecordedPayment(payment, Instant.now(), rawPayload));
                }
                scheduler.schedule(
                        () -> {
                            if (!recordBeforeTimeout) {
                                railState.record(new RecordedPayment(payment, Instant.now(), rawPayload));
                            }
                            // Confirmation timing is relative to when the
                            // rail actually finished deciding to accept --
                            // after the hold, not from the original request
                            // -- since that is when acceptance conceptually
                            // happens for a payment that timed out on the
                            // client.
                            scheduleConfirmationIfConfigured(railState, payment, behavior, acceptResponse, statusCallbackUrl, returnCallbackUrl, 0);
                            deferred.setResult(ResponseEntity.status(HttpStatus.ACCEPTED).build());
                        },
                        timeoutHoldMs,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private void scheduleConfirmationIfConfigured(
            RailState railState,
            InboundPayment payment,
            BehaviorSpec behavior,
            AcceptResponse acceptResponse,
            String statusCallbackUrl,
            String returnCallbackUrl,
            long extraDelayMs) {
        ConfirmationType confirmation = acceptResponse == AcceptResponse.REJECT_SYNC ? ConfirmationType.RJCT : behavior.confirmation();
        if (confirmation == null || confirmation == ConfirmationType.NONE) {
            return;
        }
        long confirmationDelayMs = (behavior.confirmationDelayMs() != null ? behavior.confirmationDelayMs() : 0) + extraDelayMs;

        if (confirmation == ConfirmationType.RETURN_AFTER_SETTLEMENT) {
            // Settles first (ACSC) to statusCallbackUrl, then unwinds (pacs.004)
            // to returnCallbackUrl -- different message types, different
            // endpoints, exactly like a real rail. The pacs.004's delay is
            // additional to, not instead of, the ACSC's own delay, so the
            // return always arrives after settlement, never before or
            // instead of it.
            callbackSender.scheduleConfirmation(
                    payment, ConfirmationType.ACSC.name(), null, confirmationDelayMs, statusCallbackUrl,
                    (status, reasonCode) -> railState.updateStatus(payment.uetr(), status, reasonCode));
            long returnDelayMs = confirmationDelayMs + (behavior.returnDelayMs() != null ? behavior.returnDelayMs() : 0);
            callbackSender.scheduleReturn(payment, behavior.returnReasonCode(), returnDelayMs, returnCallbackUrl);
            return;
        }

        String rejectReasonCode = confirmation == ConfirmationType.RJCT ? behavior.rejectReasonCode() : null;
        // railState.updateStatus fires the moment the rail decides the
        // outcome, not gated on the callback POST actually landing -- GET
        // /payments/{uetr} answers "what does the rail think", which is
        // true whether or not settlement-gateway received the notification.
        callbackSender.scheduleConfirmation(
                payment,
                confirmation.name(),
                rejectReasonCode,
                confirmationDelayMs,
                statusCallbackUrl,
                (status, reasonCode) -> railState.updateStatus(payment.uetr(), status, reasonCode));
    }

    /**
     * Reconciliation queries are a second, independent interaction with the
     * rail (see {@link StatusQueryBehaviour}), so this branches on the
     * scenario's {@code statusQueryBehaviour} before falling through to the
     * same lookup {@link #NORMAL} always used. {@code ALWAYS_ERROR} answers
     * as if the rail's status endpoint were down; {@code SLOW} answers
     * truthfully but only after a configured delay, modeling a rail whose
     * query path is merely slow rather than broken; {@code
     * UNKNOWN_THEN_KNOWN} answers UNKNOWN for a configured number of leading
     * queries regardless of what is actually on file, then truthfully --
     * this is what lets a test prove a resolver requires more than one
     * UNKNOWN observation before acting.
     */
    @GetMapping("/payments/{uetr}")
    public ResponseEntity<PaymentStatusResponse> getPayment(@PathVariable String railId, @PathVariable String uetr) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        ScenarioConfig scenario = railState.scenario();

        switch (scenario.statusQueryBehaviour()) {
            case ALWAYS_ERROR -> {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new PaymentStatusResponse(uetr, "ERROR", null, null, null));
            }
            case SLOW -> sleep(scenario.resolvedStatusQuerySlowDelayMs());
            case UNKNOWN_THEN_KNOWN -> {
                int attempt = railState.nextStatusQueryAttempt(uetr);
                if (attempt <= scenario.resolvedStatusQueryUnknownCount()) {
                    return ResponseEntity.ok(new PaymentStatusResponse(uetr, "UNKNOWN", null, null, null));
                }
            }
            case NORMAL -> {
                // fall through to the lookup below
            }
        }

        RecordedPayment recorded = railState.find(uetr);
        if (recorded == null) {
            return ResponseEntity.ok(new PaymentStatusResponse(uetr, "UNKNOWN", null, null, null));
        }
        return ResponseEntity.ok(new PaymentStatusResponse(
                uetr, "KNOWN", recorded.railStatus(), recorded.railReasonCode(), DateTimeFormatter.ISO_INSTANT.format(recorded.receivedAt())));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Test-only: loads a new scenario for this rail and resets its counters, recorded payments and pending callbacks. */
    @PostMapping("/scenario")
    public ResponseEntity<Void> loadScenario(@PathVariable String railId, @RequestBody byte[] yaml) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        ScenarioConfig scenario = scenarioLoader.load(yaml);
        railState.loadScenario(scenario);
        return ResponseEntity.ok().build();
    }

    /** Test-only: everything this rail has recorded, for test assertions. */
    @GetMapping("/received")
    public ResponseEntity<List<RecordedPaymentSummary>> received(@PathVariable String railId) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        List<RecordedPaymentSummary> summaries = railState.allRecorded().stream()
                .map(r -> new RecordedPaymentSummary(
                        r.payment().uetr(),
                        DateTimeFormatter.ISO_INSTANT.format(r.receivedAt()),
                        r.railStatus(),
                        r.payment().amount(),
                        r.payment().currency(),
                        r.payment().creditorAccount()))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * Test-only: one UETR's receipt summary, including how many times it
     * was received beyond the first -- see {@link RailState#record} for why
     * a later receipt of an already-known UETR is counted as a duplicate
     * rather than overwriting the first. This is what a test uses to prove
     * a redispatch's original delivery, arriving late, was recognised as a
     * duplicate rather than processed as a second, distinct payment.
     */
    @GetMapping("/received/{uetr}")
    public ResponseEntity<ReceivedSummary> receivedOne(@PathVariable String railId, @PathVariable String uetr) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        RecordedPayment recorded = railState.find(uetr);
        int duplicateCount = railState.duplicateReceiptCount(uetr);
        if (recorded == null) {
            return ResponseEntity.ok(new ReceivedSummary(uetr, false, 0, null));
        }
        return ResponseEntity.ok(new ReceivedSummary(
                uetr, true, duplicateCount, DateTimeFormatter.ISO_INSTANT.format(recorded.receivedAt())));
    }

    /**
     * Test-only: the exact bytes this rail received for one UETR, kept
     * separate from {@link #received} rather than inlined there so the
     * common-case summary stays small -- a full pacs.008 body for every
     * recorded payment on every {@code GET /received} call would be
     * needless weight for tests that only care about the summary fields.
     * Exists specifically so a byte-for-byte fidelity check (a client's
     * "what I stored" against this rail's "what I actually received") is
     * possible without the client having to also be the one capturing the
     * wire bytes -- see .notes/reports/PHASE-6-REPORT.md §6.
     */
    @GetMapping("/received/{uetr}/raw")
    public ResponseEntity<byte[]> receivedRaw(@PathVariable String railId, @PathVariable String uetr) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        RecordedPayment recorded = railState.find(uetr);
        if (recorded == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(recorded.rawPayload());
    }

    private static RailId parseRailId(String railId) {
        try {
            return RailId.valueOf(railId.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownRailException(railId);
        }
    }

    public record PaymentStatusResponse(String uetr, String status, String railStatus, String railReasonCode, String receivedAt) {
    }

    public record RecordedPaymentSummary(
            String uetr, String receivedAt, String railStatus, java.math.BigDecimal amount, String currency, String creditorAccount) {
    }

    public record ReceivedSummary(String uetr, boolean received, int duplicateCount, String receivedAt) {
    }

    public record ErrorResponse(String error, String detail) {
    }

    public record SchemaInvalidResponse(String error, List<String> violations) {
    }

    static class UnknownRailException extends RuntimeException {
        UnknownRailException(String railId) {
            super("Unknown rail: " + railId);
        }
    }
}
