package com.kishore.payments.railsim.dispatch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * The reflection path itself is exercised for real by DispatchAmbiguityTest
 * (a genuine embedded-Tomcat request). This test proves the other half of
 * the contract: when the reflection path can't be walked -- here, because a
 * Mockito mock has none of RequestFacade's internal shape -- drop() throws
 * visibly instead of logging and returning as though nothing happened.
 */
class ConnectionDropperTest {

    @Test
    void dropThrowsRatherThanSilentlyDegradingWhenTheRequestIsNotARequestFacade() {
        ConnectionDropper connectionDropper = new ConnectionDropper();
        HttpServletRequest notATomcatRequestFacade = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> connectionDropper.drop(notATomcatRequestFacade))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to force-close the connection");
    }
}
