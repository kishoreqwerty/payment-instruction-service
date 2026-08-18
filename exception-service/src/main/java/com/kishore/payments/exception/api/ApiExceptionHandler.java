package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.CaseNotFoundException;
import com.kishore.payments.exception.cases.IllegalCaseActionException;
import com.kishore.payments.exception.cases.InvestigationConfirmationNotFoundException;
import com.kishore.payments.exception.repair.FieldNotRepairableException;
import com.kishore.payments.exception.repair.MakerCheckerViolationException;
import com.kishore.payments.exception.repair.RepairActionNotFoundException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps this module's own exception vocabulary to the HTTP status each one actually means -- see each exception's own javadoc for why that status. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({CaseNotFoundException.class, RepairActionNotFoundException.class, InvestigationConfirmationNotFoundException.class,
            NoSuchElementException.class})
    public ResponseEntity<ErrorResponse> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(FieldNotRepairableException.class)
    public ResponseEntity<FieldNotRepairableResponse> fieldNotRepairable(FieldNotRepairableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new FieldNotRepairableResponse("FIELD_NOT_REPAIRABLE", e.getMessage(), e.disallowedFields()));
    }

    @ExceptionHandler(MakerCheckerViolationException.class)
    public ResponseEntity<ErrorResponse> makerCheckerViolation(MakerCheckerViolationException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("MAKER_CHECKER_VIOLATION", e.getMessage()));
    }

    @ExceptionHandler(IllegalCaseActionException.class)
    public ResponseEntity<ErrorResponse> illegalCaseAction(IllegalCaseActionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("ILLEGAL_CASE_ACTION", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    public record ErrorResponse(String error, String detail) {
    }

    public record FieldNotRepairableResponse(String error, String detail, java.util.List<String> disallowedFields) {
    }
}
