package com.kishore.payments.intake.state;

import com.kishore.payments.core.state.StateMachine;
import com.kishore.payments.core.state.StateTransitionTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core is a plain library with no Spring dependency, so its classes can't be
 * annotated as components. Wiring them into beans lives here instead.
 */
@Configuration
public class StateMachineConfig {

    @Bean
    public StateTransitionTable stateTransitionTable() {
        return new StateTransitionTable();
    }

    @Bean
    public StateMachine stateMachine(StateTransitionTable stateTransitionTable) {
        return new StateMachine(stateTransitionTable);
    }
}
