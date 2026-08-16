package com.kishore.payments.railsim.iso20022;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.xml.sax.SAXException;

/**
 * One {@link Schema} and one {@link JAXBContext} per message type. Kept
 * separate rather than one combined schema/context for all three, matching
 * the same reason the JAXB bindings themselves live in separate packages
 * (see the xjc configuration in pom.xml): pacs.008, pacs.002 and pacs.004
 * are independent schemas that happen to share a lot of component shape,
 * not one schema with three root elements.
 */
@Configuration
public class Iso20022Config {

    private static final String PACS_008_XSD = "/xsd/pacs.008.001.08.xsd";
    private static final String PACS_002_XSD = "/xsd/pacs.002.001.10.xsd";
    private static final String PACS_004_XSD = "/xsd/pacs.004.001.09.xsd";

    @Bean
    public Schema pacs008Schema() {
        return compile(PACS_008_XSD);
    }

    @Bean
    public Schema pacs002Schema() {
        return compile(PACS_002_XSD);
    }

    @Bean
    public Schema pacs004Schema() {
        return compile(PACS_004_XSD);
    }

    @Bean
    public JAXBContext pacs008JaxbContext() {
        return context(com.kishore.payments.railsim.iso20022.generated.pacs008.Document.class);
    }

    @Bean
    public JAXBContext pacs002JaxbContext() {
        return context(com.kishore.payments.railsim.iso20022.generated.pacs002.Document.class);
    }

    @Bean
    public JAXBContext pacs004JaxbContext() {
        return context(com.kishore.payments.railsim.iso20022.generated.pacs004.Document.class);
    }

    private static Schema compile(String classpathLocation) {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try (InputStream xsd = Iso20022Config.class.getResourceAsStream(classpathLocation)) {
            if (xsd == null) {
                throw new IllegalStateException("Schema not found on the classpath at " + classpathLocation);
            }
            return factory.newSchema(new StreamSource(xsd));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Unable to compile schema at " + classpathLocation, e);
        }
    }

    private static JAXBContext context(Class<?> documentClass) {
        try {
            return JAXBContext.newInstance(documentClass);
        } catch (JAXBException e) {
            throw new IllegalStateException("Unable to initialise JAXB context for " + documentClass, e);
        }
    }
}
