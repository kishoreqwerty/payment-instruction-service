package com.kishore.payments.gateway.iso20022;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.GregorianCalendar;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/** ISO date/time conversions every generated-message builder in this module needs. */
public final class XmlDateTimes {

    private static final DatatypeFactory FACTORY = newFactory();

    private XmlDateTimes() {
    }

    public static XMLGregorianCalendar now() {
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        return FACTORY.newXMLGregorianCalendar(GregorianCalendar.from(nowUtc.toZonedDateTime()));
    }

    public static XMLGregorianCalendar date(LocalDate date) {
        return FACTORY.newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
    }

    private static DatatypeFactory newFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("Unable to initialise a DatatypeFactory for XML date/time conversion", e);
        }
    }
}
