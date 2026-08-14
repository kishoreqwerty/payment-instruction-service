package com.kishore.payments.intake.pain001;

import com.kishore.payments.intake.iso20022.generated.Document;
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

@Configuration
public class Pain001Config {

    private static final String XSD_CLASSPATH_LOCATION = "/xsd/pain.001.001.09.xsd";

    @Bean
    public Schema pain001Schema() {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try (InputStream xsd = getClass().getResourceAsStream(XSD_CLASSPATH_LOCATION)) {
            if (xsd == null) {
                throw new IllegalStateException("pain.001.001.09.xsd not found on the classpath at " + XSD_CLASSPATH_LOCATION);
            }
            return factory.newSchema(new StreamSource(xsd));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Unable to compile pain.001.001.09.xsd", e);
        }
    }

    @Bean
    public JAXBContext pain001JaxbContext() {
        try {
            return JAXBContext.newInstance(Document.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Unable to initialise JAXB context for pain.001", e);
        }
    }
}
