package com.kishore.payments.railsim.iso20022;

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
 * Validates an XML payload against a given {@link Schema} in two distinct
 * steps -- "not well-formed" and "well-formed but schema-invalid" are
 * different failures, mirroring intake-service's Pain001Validator. Used both
 * ways here: inbound (reject a malformed pacs.008 with 400 before it's
 * recorded at all) and outbound (the simulator validates its own generated
 * pacs.002/pacs.004 before sending them, so a builder bug surfaces as a
 * simulator startup/test failure rather than as malformed input a future
 * settlement-gateway has to debug against).
 */
@Component
public class XmlSchemaValidator {

    public ValidationResult validate(byte[] payload, Schema schema) {
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
