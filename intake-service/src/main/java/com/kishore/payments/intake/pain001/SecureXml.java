package com.kishore.payments.intake.pain001;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * pain.001 arrives from outside the system, so both the XSD validator and the
 * JAXB unmarshaller need to be hardened against XXE: DOCTYPE declarations and
 * external entity resolution are disabled entirely (pain.001 has no
 * legitimate use for either), rather than left at the JDK's insecure
 * defaults.
 */
final class SecureXml {

    private SecureXml() {
    }

    static XMLReader newSecureXmlReader() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("Unable to configure a secure SAX parser", e);
        }
    }

    static XMLInputFactory newSecureXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
