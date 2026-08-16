package com.kishore.payments.railsim.dispatch;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * Forcibly closes the underlying TCP connection with no response at all --
 * what a DROP scenario needs, and observably different from TIMEOUT (which
 * always eventually sends something, even if the client has already given
 * up). There is no servlet-API-level way to do this; it requires reaching
 * through Tomcat's request facade to the raw {@code org.apache.coyote.Request}
 * and issuing {@code ActionCode.CLOSE_NOW}, which is not a supported public
 * API on the wrapper Spring hands controllers, only on the concrete
 * Catalina class underneath it. Done via reflection, confined to this one
 * class.
 *
 * <p>Two failure-handling decisions here are deliberate, not oversights.
 * First, {@link #verifyDropIsSupported()} runs at startup and fails the
 * application context if the internal shape this class depends on isn't
 * there -- a rail simulator that cannot actually drop a connection cannot
 * support the ambiguity resolution Phase 7 is built around, so refusing to
 * start is the correct response, not a warning nobody reads. Second,
 * {@link #drop} throws rather than swallowing a reflection failure at
 * request time: an earlier version of this class logged and fell through,
 * which meant a broken drop silently degraded into a TIMEOUT-shaped hang
 * (Spring's async request timeout eventually answering instead) -- a test
 * asserting only on the eventual UNKNOWN status passed without ever
 * exercising a real connection drop. Verified against Apache Tomcat/10.1.20
 * (see .notes/reports/PHASE-5-REPORT.md); re-verify by hand after any
 * Spring Boot / embedded-Tomcat version bump.
 */
@Component
public class ConnectionDropper {

    private static final String ACTION_CODE_CLASS_NAME = "org.apache.coyote.ActionCode";
    private static final String COYOTE_REQUEST_CLASS_NAME = "org.apache.coyote.Request";
    private static final String CATALINA_REQUEST_CLASS_NAME = "org.apache.catalina.connector.Request";
    private static final String REQUEST_FACADE_CLASS_NAME = "org.apache.catalina.connector.RequestFacade";
    private static final String VERIFIED_AGAINST = "Apache Tomcat/10.1.20";

    /**
     * Fails application startup if the reflective path DROP depends on
     * isn't present on the classpath's actual Tomcat, rather than
     * discovering that the first time a DROP scenario silently does
     * nothing.
     */
    @PostConstruct
    void verifyDropIsSupported() {
        try {
            Class.forName(REQUEST_FACADE_CLASS_NAME).getDeclaredField("request");
            Class.forName(CATALINA_REQUEST_CLASS_NAME).getMethod("getCoyoteRequest");
            Class<?> actionCodeClass = Class.forName(ACTION_CODE_CLASS_NAME);
            closeNowConstant(actionCodeClass);
            Class.forName(COYOTE_REQUEST_CLASS_NAME).getMethod("action", actionCodeClass, Object.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "rail-simulator's DROP scenario requires an undocumented Tomcat internal path "
                            + "(RequestFacade.request -> Request.getCoyoteRequest() -> coyote Request.action(CLOSE_NOW)) "
                            + "that is not available against " + runningTomcatVersion() + ". This class was last "
                            + "verified working against " + VERIFIED_AGAINST + ". Refusing to start rather than "
                            + "accepting DROP scenarios it cannot actually perform -- see "
                            + ".notes/reports/PHASE-5-REPORT.md sections 4 and 6.",
                    e);
        }
    }

    /** Throws rather than degrading into a silent no-op: a failed drop must be a visible failure, never a disguised TIMEOUT. */
    public void drop(HttpServletRequest request) {
        try {
            Object coyoteRequest = coyoteRequestOf(request);
            Class<?> actionCodeClass = Class.forName(ACTION_CODE_CLASS_NAME);
            Object closeNow = closeNowConstant(actionCodeClass);
            Method action = coyoteRequest.getClass().getMethod("action", actionCodeClass, Object.class);
            action.invoke(coyoteRequest, closeNow, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to force-close the connection for a DROP scenario against " + runningTomcatVersion()
                            + "; refusing to let this degrade into a TIMEOUT-shaped hang", e);
        }
    }

    private static Object closeNowConstant(Class<?> actionCodeClass) throws NoSuchFieldException {
        return Arrays.stream(actionCodeClass.getEnumConstants())
                .filter(constant -> "CLOSE_NOW".equals(((Enum<?>) constant).name()))
                .findFirst()
                .orElseThrow(() -> new NoSuchFieldException("ActionCode.CLOSE_NOW"));
    }

    private static Object coyoteRequestOf(HttpServletRequest request) throws ReflectiveOperationException {
        // request is an org.apache.catalina.connector.RequestFacade, which
        // (as of Tomcat 10.1) exposes no getter at all for its wrapped
        // org.apache.catalina.connector.Request -- only a protected field
        // -- so that field has to be read directly. That class's own
        // getCoyoteRequest() is public.
        Field requestField = request.getClass().getDeclaredField("request");
        requestField.setAccessible(true);
        Object catalinaRequest = requestField.get(request);
        Method getCoyoteRequest = catalinaRequest.getClass().getMethod("getCoyoteRequest");
        return getCoyoteRequest.invoke(catalinaRequest);
    }

    private static String runningTomcatVersion() {
        try {
            Class<?> serverInfo = Class.forName("org.apache.catalina.util.ServerInfo");
            return (String) serverInfo.getMethod("getServerInfo").invoke(null);
        } catch (ReflectiveOperationException e) {
            return "an unknown Tomcat version (ServerInfo itself could not be read: " + e + ")";
        }
    }
}
