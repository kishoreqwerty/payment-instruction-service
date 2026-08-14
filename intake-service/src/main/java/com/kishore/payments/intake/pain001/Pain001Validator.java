package com.kishore.payments.intake.pain001;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.springframework.stereotype.Component;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * Validates a pain.001 payload in two distinct steps, because "not well-formed
 * XML" and "well-formed XML that fails the XSD" are different failures with
 * different HTTP responses (400 vs 422). A well-formedness failure is fatal
 * and aborts parsing immediately; schema violations are recoverable at the
 * SAX level, so every one of them is collected rather than only the first.
 */
@Component
public class Pain001Validator {

    private final Schema schema;

    public Pain001Validator(Schema schema) {
        this.schema = schema;
    }

    public ValidationResult validate(byte[] payload) {
        List<String> violations = new ArrayList<>();
        Validator validator = schema.newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                violations.add(describe(e));
            }

            @Override
            public void error(SAXParseException e) {
                violations.add(describe(e));
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                // Not well-formed XML: cannot be recovered from, must abort
                // rather than continue collecting violations.
                throw e;
            }
        });

        XMLReader reader = SecureXml.newSecureXmlReader();
        try {
            validator.validate(new SAXSource(reader, new InputSource(new ByteArrayInputStream(payload))));
        } catch (SAXException | IOException e) {
            return ValidationResult.malformed();
        }

        return new ValidationResult(true, List.copyOf(violations));
    }

    private static String describe(SAXParseException e) {
        return "Line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage();
    }

    public record ValidationResult(boolean wellFormed, List<String> violations) {

        static ValidationResult malformed() {
            return new ValidationResult(false, List.of());
        }

        public boolean isValid() {
            return wellFormed && violations.isEmpty();
        }
    }
}
