package com.logistpulse.lab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistpulse.lab.api.ApiException;
import com.logistpulse.lab.api.dto.OrderDto;
import com.logistpulse.lab.domain.Order;
import com.logistpulse.lab.domain.OrderStatus;
import com.logistpulse.lab.domain.OutboxEvent;
import com.logistpulse.lab.domain.Robot;
import com.logistpulse.lab.domain.RobotStatus;
import com.logistpulse.lab.domain.Warehouse;
import com.logistpulse.lab.integration.CarrierClient;
import com.logistpulse.lab.repo.OrderRepository;
import com.logistpulse.lab.repo.OutboxEventRepository;
import com.logistpulse.lab.repo.RobotRepository;
import com.logistpulse.lab.route.Point;
import com.logistpulse.lab.route.RouteDto;
import com.logistpulse.lab.route.RoutePlanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Parte 9.2: reglas de OrderService.dispatch sin base de datos ni red (todo simulado con Mockito). */
class OrderServiceDispatchTest {

    private static final int ROUTE_COST = 18;

    private final OrderRepository orders = mock(OrderRepository.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final RoutePlanner planner = mock(RoutePlanner.class);
    private final CarrierClient carrier = mock(CarrierClient.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private OrderService service;
    private Order order;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, robots, null, planner, carrier, outbox,
                new TransactionTemplate(txManager), new SimpleMeterRegistry());
        order = new Order("LP-T1", "cliente", 11, 7, new Warehouse("CD", 0, 0));
        ReflectionTestUtils.setField(order, "id", 1L);
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        when(planner.plan(any(Point.class), any(Point.class))).thenReturn(new RouteDto(List.of(), ROUTE_COST, 11));
    }

    private Robot idleRobot() {
        Robot r = new Robot("AMR-01", order.getWarehouse(), 100);
        when(robots.findByStatusAndBatteryGreaterThanEqualOrderByIdAsc(RobotStatus.IDLE, OrderService.MIN_BATTERY))
                .thenReturn(List.of(r));
        return r;
    }

    @Test
    void withoutAvailableRobotsRespondsConflictAndChangesNothing() {
        when(carrier.etaMinutes(anyString())).thenReturn(Optional.of(25));
        when(robots.findByStatusAndBatteryGreaterThanEqualOrderByIdAsc(RobotStatus.IDLE, OrderService.MIN_BATTERY))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.dispatch(1L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("No hay AMR disponible");
                });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getRobot()).isNull();
        verify(outbox, never()).save(any());
    }

    @Test
    void alreadyDispatchedOrderRespondsConflictWithoutCallingTheCarrier() {
        order.setStatus(OrderStatus.DISPATCHED);

        assertThatThrownBy(() -> service.dispatch(1L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("DISPATCHED → DISPATCHED");
                });
        verify(carrier, never()).etaMinutes(anyString());      // no se gasta una llamada externa en un despacho ilegal
        verify(robots, never()).findByStatusAndBatteryGreaterThanEqualOrderByIdAsc(any(), anyInt());
    }

    @Test
    void whenTheCarrierIsDownTheEtaIsRouteCostTimesTwo() {
        Robot robot = idleRobot();
        when(carrier.etaMinutes("LP-T1")).thenReturn(Optional.empty());   // transportista caído / lento

        OrderDto dto = service.dispatch(1L);

        assertThat(dto.etaMinutes()).isEqualTo(ROUTE_COST * 2);
        assertThat(order.getEtaMinutes()).isEqualTo(36);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(order.getRobot()).isSameAs(robot);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.BUSY);
    }

    @Test
    void whenTheCarrierAnswersItsEtaIsUsed() {
        idleRobot();
        when(carrier.etaMinutes("LP-T1")).thenReturn(Optional.of(25));

        assertThat(service.dispatch(1L).etaMinutes()).isEqualTo(25);
    }

    @Test
    void successfulDispatchStoresTheOutboxEventInTheSameTransaction() {
        idleRobot();
        when(carrier.etaMinutes(anyString())).thenReturn(Optional.of(25));

        service.dispatch(1L);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("order.dispatched");
        assertThat(saved.getValue().getPayload()).isEqualTo("1");
        verify(txManager).commit(any());
    }

    @Test
    void theCarrierIsCalledBeforeTheTransactionOpens() {
        idleRobot();
        when(carrier.etaMinutes(anyString())).thenReturn(Optional.of(25));

        service.dispatch(1L);

        var order = inOrder(carrier, txManager);
        order.verify(carrier).etaMinutes("LP-T1");
        order.verify(txManager).getTransaction(any());     // Parte 6.3: HTTP fuera de la transacción
    }

    @Test
    void unknownOrderRespondsNotFound() {
        when(orders.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dispatch(99L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        verify(carrier, never()).etaMinutes(anyString());
    }
}
