package com.kishore.payments.exception.classifier;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The non-blocking entry point {@code ExceptionCaseService} calls after a case-opening
 * transaction has already committed. {@link #invokeAsync} always returns immediately: redaction,
 * the model call, and persisting the result all happen on {@link ClassifierConfig}'s dedicated
 * executor, never on the caller's own thread (the Kafka listener processing
 * {@code payments.exceptions}). Nothing thrown here, by {@link PromptRedactor} or {@link
 * ClassifierClient} or {@link ClassifierProposalWriter}, is allowed to propagate back to that
 * thread -- the outermost catch is deliberately broad, because "the classifier is not on the
 * critical path" has to hold even for a bug in this class itself, not only for a slow API.
 */
@Component
public class ClassifierInvoker {

    private static final Logger log = LoggerFactory.getLogger(ClassifierInvoker.class);

    private final PromptRedactor redactor;
    private final ClassifierClient client;
    private final ClassifierProposalWriter writer;
    private final ExecutorService executor;

    public ClassifierInvoker(PromptRedactor redactor, ClassifierClient client, ClassifierProposalWriter writer, ExecutorService classifierExecutor) {
        this.redactor = redactor;
        this.client = client;
        this.writer = writer;
        this.executor = classifierExecutor;
    }

    public void invokeAsync(
            UUID caseId, FailureStage failureStage, PaymentInstructionEntity instruction, InstructionExceptionEvent.Detail detail,
            int repairAttempts) {
        if (!client.isAvailable()) {
            return;
        }
        executor.submit(() -> {
            try {
                ClassifierRequest request = redactor.redact(failureStage, instruction, detail, repairAttempts);
                client.classify(request).ifPresent(proposal -> writer.apply(caseId, proposal));
            } catch (Exception e) {
                log.warn("Classifier invocation failed for case {}, proceeding without a proposal: {}", caseId, e.toString());
            }
        });
    }
}
