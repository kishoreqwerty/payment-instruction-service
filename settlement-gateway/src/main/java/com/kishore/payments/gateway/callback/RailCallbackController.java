package com.kishore.payments.gateway.callback;

import com.kishore.payments.gateway.iso20022.XmlSchemaValidator;
import javax.xml.validation.Schema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a rail's asynchronous pacs.002/pacs.004 lands. Both endpoints
 * validate against the XSD before trusting the content, then hand off to
 * {@link CallbackCorrelationService} for the actual correlation and state
 * change -- processed synchronously (see that class's javadoc for why), so
 * the response code here reflects genuinely completed processing, not just
 * "received."
 */
@RestController
@RequestMapping("/callbacks/rail/{railId}")
public class RailCallbackController {

    private final XmlSchemaValidator xmlSchemaValidator;
    private final Schema pacs002Schema;
    private final Schema pacs004Schema;
    private final Pacs002Parser pacs002Parser;
    private final Pacs004Parser pacs004Parser;
    private final CallbackCorrelationService correlationService;

    public RailCallbackController(
            XmlSchemaValidator xmlSchemaValidator,
            @Qualifier("pacs002Schema") Schema pacs002Schema,
            @Qualifier("pacs004Schema") Schema pacs004Schema,
            Pacs002Parser pacs002Parser,
            Pacs004Parser pacs004Parser,
            CallbackCorrelationService correlationService) {
        this.xmlSchemaValidator = xmlSchemaValidator;
        this.pacs002Schema = pacs002Schema;
        this.pacs004Schema = pacs004Schema;
        this.pacs002Parser = pacs002Parser;
        this.pacs004Parser = pacs004Parser;
        this.correlationService = correlationService;
    }

    @PostMapping(path = "/status", consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<?> status(@PathVariable String railId, @RequestBody byte[] body) {
        XmlSchemaValidator.ValidationResult validation = xmlSchemaValidator.validate(body, pacs002Schema);
        if (!validation.isValid()) {
            return ResponseEntity.badRequest().body(new SchemaInvalidResponse(validation.wellFormed(), validation.violations()));
        }
        // Phase 7: a status racing AmbiguityResolver's own resolution of the
        // same instruction and losing -- the ambiguity it thought it needed
        // to resolve is already resolved, one way or another -- is handled
        // inside CallbackCorrelationService#handleStatus itself, alongside
        // its own ACK-pending retry; nothing to catch here.
        correlationService.handleStatus(railId, pacs002Parser.parse(body));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping(path = "/return", consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<?> paymentReturn(@PathVariable String railId, @RequestBody byte[] body) {
        XmlSchemaValidator.ValidationResult validation = xmlSchemaValidator.validate(body, pacs004Schema);
        if (!validation.isValid()) {
            return ResponseEntity.badRequest().body(new SchemaInvalidResponse(validation.wellFormed(), validation.violations()));
        }
        correlationService.handleReturn(railId, pacs004Parser.parse(body));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    public record SchemaInvalidResponse(boolean wellFormed, java.util.List<String> violations) {
    }
}
