package com.oxygenraj.initial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GreetingControllerTest {

    @Mock
    private OracleSchemaService oracleSchemaService;

    @InjectMocks
    private GreetingController controller;

    @Test
    void returnsHiWithTheVerifiedSchema() {
        when(oracleSchemaService.requireInitialSchema()).thenReturn("INITIAL_DB");

        assertThat(controller.hi())
                .isEqualTo(new GreetingResponse("Hi", "initial-service", "INITIAL_DB"));
    }

    @Test
    void returnsHelloWithTheVerifiedSchema() {
        when(oracleSchemaService.requireInitialSchema()).thenReturn("INITIAL_DB");

        assertThat(controller.hello())
                .isEqualTo(new GreetingResponse("Hello", "initial-service", "INITIAL_DB"));
    }

    @Test
    void doesNotReturnAGreetingWhenTheSchemaCannotBeVerified() {
        when(oracleSchemaService.requireInitialSchema())
                .thenThrow(new DatabaseUnavailableException());

        assertThatThrownBy(controller::hi).isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(controller::hello).isInstanceOf(DatabaseUnavailableException.class);
    }
}
