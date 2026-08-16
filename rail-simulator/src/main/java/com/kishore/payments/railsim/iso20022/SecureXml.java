package com.kishore.payments.railsim.iso20022;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * pacs.008 arrives from outside this process (a settlement-gateway this
 * simulator does not trust any more than intake-service trusts an inbound
 * pain.001), so both the XSD validator and the JAXB unmarshaller are
 * hardened against XXE: DOCTYPE declarations and external entity resolution
 * are disabled entirely, rather than left at the JDK's insecure defaults.
 * Deliberately duplicated from intake-service's copy of the same class
 * rather than shared -- this module has no dependency on anything else in
 * the repository, core included.
 */
public final class SecureXml {

    private SecureXml() {
    }

    public static XMLReader newSecureXmlReader() {
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

    public static XMLInputFactory newSecureXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
