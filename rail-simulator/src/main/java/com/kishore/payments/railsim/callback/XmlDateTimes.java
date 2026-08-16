package com.kishore.payments.railsim.callback;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.GregorianCalendar;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/** The one conversion every generated-message builder in this package needs: "now" as an XMLGregorianCalendar. */
final class XmlDateTimes {

    private static final DatatypeFactory FACTORY = newFactory();

    private XmlDateTimes() {
    }

    static XMLGregorianCalendar now() {
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        return FACTORY.newXMLGregorianCalendar(GregorianCalendar.from(nowUtc.toZonedDateTime()));
    }

    private static DatatypeFactory newFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("Unable to initialise a DatatypeFactory for XML date/time conversion", e);
        }
    }
}
