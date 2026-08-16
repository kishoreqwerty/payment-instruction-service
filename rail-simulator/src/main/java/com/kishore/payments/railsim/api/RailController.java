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
        ScenarioConfig scenario = railState.scenario();
        BehaviorSpec behavior = scenario.resolve(payment, requestOrdinal);
        String callbackUrl = scenario.callbackUrl() != null ? scenario.callbackUrl() : defaultCallbackUrl;

        dispatch(railState, payment, behavior, callbackUrl, request, deferred);
        return deferred;
    }

    private void dispatch(
            RailState railState,
            InboundPayment payment,
            BehaviorSpec behavior,
            String callbackUrl,
            HttpServletRequest request,
            DeferredResult<ResponseEntity<?>> deferred) {
        AcceptResponse acceptResponse = behavior.acceptResponse();
        long acceptDelayMs = behavior.acceptDelayMs() != null ? behavior.acceptDelayMs() : 0;

        switch (acceptResponse) {
            case DROP -> {
                if (Boolean.TRUE.equals(behavior.recordBeforeTimeout())) {
                    railState.record(new RecordedPayment(payment, Instant.now()));
                }
                connectionDropper.drop(request);
                // If drop() returns normally, the connection is already
                // closed and no result is ever set here -- setting one
                // would just be Tomcat discarding a write to a dead socket.
                // If drop() cannot perform the drop, it throws, and that
                // propagates as a normal synchronous 500 (the request
                // hasn't returned yet) rather than silently succeeding into
                // a scenario that looks like TIMEOUT. Not scheduling a
                // confirmation on a successful drop, either -- a payment
                // the rail never accepted has nothing to confirm,
                // regardless of what `confirmation` says.
            }
            case ACCEPT, REJECT_SYNC -> scheduler.schedule(
                    () -> {
                        railState.record(new RecordedPayment(payment, Instant.now()));
                        scheduleConfirmationIfConfigured(railState, payment, behavior, acceptResponse, callbackUrl, 0);
                        deferred.setResult(ResponseEntity.status(HttpStatus.ACCEPTED).build());
                    },
                    acceptDelayMs,
                    TimeUnit.MILLISECONDS);
            case TIMEOUT -> {
                long timeoutHoldMs = behavior.timeoutHoldMs() != null ? behavior.timeoutHoldMs() : 0;
                boolean recordBeforeTimeout = Boolean.TRUE.equals(behavior.recordBeforeTimeout());
                if (recordBeforeTimeout) {
                    railState.record(new RecordedPayment(payment, Instant.now()));
                }
                scheduler.schedule(
                        () -> {
                            if (!recordBeforeTimeout) {
                                railState.record(new RecordedPayment(payment, Instant.now()));
                            }
                            // Confirmation timing is relative to when the
                            // rail actually finished deciding to accept --
                            // after the hold, not from the original request
                            // -- since that is when acceptance conceptually
                            // happens for a payment that timed out on the
                            // client.
                            scheduleConfirmationIfConfigured(railState, payment, behavior, acceptResponse, callbackUrl, 0);
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
            String callbackUrl,
            long extraDelayMs) {
        ConfirmationType confirmation = acceptResponse == AcceptResponse.REJECT_SYNC ? ConfirmationType.RJCT : behavior.confirmation();
        if (confirmation == null || confirmation == ConfirmationType.NONE) {
            return;
        }
        long confirmationDelayMs = (behavior.confirmationDelayMs() != null ? behavior.confirmationDelayMs() : 0) + extraDelayMs;
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
                callbackUrl,
                status -> railState.updateStatus(payment.uetr(), status));
    }

    @GetMapping("/payments/{uetr}")
    public ResponseEntity<PaymentStatusResponse> getPayment(@PathVariable String railId, @PathVariable String uetr) {
        RailState railState = railStateRegistry.get(parseRailId(railId));
        RecordedPayment recorded = railState.find(uetr);
        if (recorded == null) {
            return ResponseEntity.ok(new PaymentStatusResponse(uetr, "UNKNOWN", null, null));
        }
        return ResponseEntity.ok(new PaymentStatusResponse(
                uetr, "KNOWN", recorded.railStatus(), DateTimeFormatter.ISO_INSTANT.format(recorded.receivedAt())));
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

    private static RailId parseRailId(String railId) {
        try {
            return RailId.valueOf(railId.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownRailException(railId);
        }
    }

    public record PaymentStatusResponse(String uetr, String status, String railStatus, String receivedAt) {
    }

    public record RecordedPaymentSummary(
            String uetr, String receivedAt, String railStatus, java.math.BigDecimal amount, String currency, String creditorAccount) {
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
