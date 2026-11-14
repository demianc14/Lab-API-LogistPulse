package com.logistpulse.lab.domain;

import static com.logistpulse.lab.domain.OrderStatus.CANCELLED;
import static com.logistpulse.lab.domain.OrderStatus.CREATED;
import static com.logistpulse.lab.domain.OrderStatus.DELIVERED;
import static com.logistpulse.lab.domain.OrderStatus.DISPATCHED;
import static com.logistpulse.lab.domain.OrderStatus.IN_TRANSIT;
import static com.logistpulse.lab.domain.OrderStatus.RETURNED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void happyPath() {
        assertThat(CREATED.canGoTo(DISPATCHED)).isTrue();
        assertThat(DISPATCHED.canGoTo(IN_TRANSIT)).isTrue();
        assertThat(IN_TRANSIT.canGoTo(DELIVERED)).isTrue();
    }

    @Test
    void cannotSkipStates() {
        assertThat(CREATED.canGoTo(IN_TRANSIT)).isFalse();
        assertThat(CREATED.canGoTo(DELIVERED)).isFalse();
        assertThat(DISPATCHED.canGoTo(DELIVERED)).isFalse();
    }

    @Test
    void cannotCancelOnceInTransit() {
        assertThat(IN_TRANSIT.canGoTo(CANCELLED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoExit() {
        // DELIVERED ya no es terminal (puede pasar a RETURNED); los terminales son CANCELLED y RETURNED.
        assertThat(CANCELLED.next()).isEmpty();
        assertThat(RETURNED.next()).isEmpty();
    }

    // --- Parte 4: estado RETURNED ---

    @Test
    void deliveredCanBeReturned() {
        assertThat(DELIVERED.canGoTo(RETURNED)).isTrue();
        assertThat(DELIVERED.next()).containsExactly(RETURNED);
    }

    @Test
    void returnedIsTerminal() {
        assertThat(RETURNED.next()).isEmpty();
    }

    @Test
    void onlyDeliveredOrdersCanBeReturned() {
        for (OrderStatus s : new OrderStatus[] {CREATED, DISPATCHED, IN_TRANSIT, CANCELLED, RETURNED}) {
            assertThat(s.canGoTo(RETURNED)).as(s + " -> RETURNED").isFalse();
        }
    }

    @Test
    void deliveryCannotBeUndoneExceptByReturn() {
        assertThat(DELIVERED.canGoTo(IN_TRANSIT)).isFalse();
        assertThat(DELIVERED.canGoTo(CANCELLED)).isFalse();
        assertThat(DELIVERED.canGoTo(CREATED)).isFalse();
    }

    @Test
    void noStateGoesBackToCreatedOrLoopsToItself() {
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(s.canGoTo(CREATED)).as(s + " -> CREATED").isFalse();
            assertThat(s.canGoTo(s)).as(s + " -> " + s).isFalse();
        }
    }

    @Test
    void fullTransitionMatrix() {
        Map<OrderStatus, Set<OrderStatus>> expected = Map.of(
                CREATED, EnumSet.of(DISPATCHED, CANCELLED),
                DISPATCHED, EnumSet.of(IN_TRANSIT, CANCELLED),
                IN_TRANSIT, EnumSet.of(DELIVERED),
                DELIVERED, EnumSet.of(RETURNED),
                CANCELLED, EnumSet.noneOf(OrderStatus.class),
                RETURNED, EnumSet.noneOf(OrderStatus.class));
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(s.next()).as("next(" + s + ")").isEqualTo(expected.get(s));
        }
    }
}
