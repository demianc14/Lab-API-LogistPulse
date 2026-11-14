package com.logistpulse.lab.service;

import com.logistpulse.lab.api.ApiException;
import com.logistpulse.lab.api.dto.OrderDto;
import com.logistpulse.lab.api.dto.OrderInput;
import com.logistpulse.lab.api.dto.PageDto;
import com.logistpulse.lab.domain.Order;
import com.logistpulse.lab.domain.OrderStatus;
import com.logistpulse.lab.domain.Robot;
import com.logistpulse.lab.domain.RobotStatus;
import com.logistpulse.lab.domain.Warehouse;
import com.logistpulse.lab.integration.CarrierClient;
import com.logistpulse.lab.messaging.DispatchEventPublisher;
import com.logistpulse.lab.repo.OrderRepository;
import com.logistpulse.lab.repo.RobotRepository;
import com.logistpulse.lab.repo.WarehouseRepository;
import com.logistpulse.lab.route.Point;
import com.logistpulse.lab.route.RouteDto;
import com.logistpulse.lab.route.RoutePlanner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    /** Un AMR con menos batería que esto no recibe pedidos. */
    static final int MIN_BATTERY = 20;

    private final OrderRepository orders;
    private final RobotRepository robots;
    private final WarehouseRepository warehouses;
    private final RoutePlanner planner;
    private final CarrierClient carrier;
    private final DispatchEventPublisher events;
    private final TransactionTemplate tx;
    private final Counter created;
    private final Counter dispatched;

    public OrderService(OrderRepository orders, RobotRepository robots, WarehouseRepository warehouses,
                        RoutePlanner planner, CarrierClient carrier, DispatchEventPublisher events,
                        TransactionTemplate tx, MeterRegistry registry) {
        this.orders = orders;
        this.robots = robots;
        this.warehouses = warehouses;
        this.planner = planner;
        this.carrier = carrier;
        this.events = events;
        this.tx = tx;
        this.created = Counter.builder("logistpulse.orders.created").description("Pedidos creados").register(registry);
        this.dispatched = Counter.builder("logistpulse.orders.dispatched").description("Pedidos despachados").register(registry);
    }

    @Transactional
    public OrderDto create(OrderInput in) {
        Warehouse warehouse = warehouses.findAll().stream().findFirst()
                .orElseThrow(() -> new ApiException(500, "No hay almacén configurado"));
        if (!planner.isWalkable(in.destX(), in.destY())) {
            throw new ApiException(422, "Destino (" + in.destX() + "," + in.destY() + ") bloqueado o fuera de la grilla");
        }
        String code = in.code() != null ? in.code() : newCode();
        if (orders.existsByCode(code)) {
            throw new ApiException(409, "Ya existe un pedido con código " + code);
        }
        Order saved = orders.save(new Order(code, in.customer(), in.destX(), in.destY(), warehouse));
        created.increment();
        return OrderDto.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id) {
        return OrderDto.from(find(id));
    }

    /** Campos por los que se puede ordenar (nombre público → propiedad de la entidad). */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id", "code", "code", "customer", "customerName",
            "status", "status", "createdAt", "createdAt", "etaMinutes", "etaMinutes");

    @Transactional(readOnly = true)
    public PageDto<OrderDto> list(OrderStatus status, String customer, String sort, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, parseSort(sort));
        boolean byCustomer = customer != null && !customer.isBlank();
        Page<Order> result;
        if (byCustomer) {
            result = status == null
                    ? orders.findByCustomerNameContainingIgnoreCase(customer.trim(), pageable)
                    : orders.findByStatusAndCustomerNameContainingIgnoreCase(status, customer.trim(), pageable);
        } else {
            result = status == null ? orders.findAll(pageable) : orders.findByStatus(status, pageable);
        }
        List<OrderDto> content = result.getContent().stream().map(OrderDto::from).toList();
        return new PageDto<>(content, safePage, safeSize, result.getTotalElements(), result.getTotalPages(), Map.of());
    }

    /** "campo[,asc|desc]" validado contra la lista blanca; el id desempata para que la paginación sea estable. */
    static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by("id").descending();
        }
        String[] parts = sort.split(",", -1);
        String property = SORTABLE.get(parts[0].trim());
        if (property == null || parts.length > 2) {
            throw new ApiException(400, "sort inválido '" + sort + "'. Campos permitidos: "
                    + new TreeSet<>(SORTABLE.keySet()) + ", formato campo[,asc|desc]");
        }
        String dir = parts.length == 2 ? parts[1].trim().toLowerCase() : "asc";
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new ApiException(400, "Dirección de sort inválida '" + parts[1] + "' (usa asc o desc)");
        }
        Sort primary = Sort.by(dir.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, property);
        return property.equals("id") ? primary : primary.and(Sort.by("id").descending());
    }

    /**
     * CREATED → DISPATCHED: asigna el AMR libre con batería suficiente, calcula ruta y ETA y publica el evento.
     *
     * La llamada HTTP al transportista se hace ANTES de abrir la transacción (este método no es @Transactional):
     * así no se retiene una conexión de la pool mientras se espera a un servicio externo lento.
     * La parte de base de datos corre en una transacción corta (TransactionTemplate) que revalida todo.
     */
    public OrderDto dispatch(Long id) {
        Order order = find(id);   // lectura corta, su propia transacción
        if (!order.getStatus().canGoTo(OrderStatus.DISPATCHED)) {   // evita llamar al transportista si ya es ilegal
            throw new ApiException(409, "Transición ilegal " + order.getStatus() + " → DISPATCHED");
        }
        Optional<Integer> carrierEta = carrier.etaMinutes(order.getCode());   // HTTP, sin transacción abierta
        return tx.execute(status -> dispatchInTx(id, carrierEta));
    }

    /** Parte transaccional del despacho: solo base de datos y cálculo local (A*), nada de red. */
    private OrderDto dispatchInTx(Long id, Optional<Integer> carrierEta) {
        Order order = find(id);   // se vuelve a leer y validar: el estado pudo cambiar durante la llamada HTTP
        if (!order.getStatus().canGoTo(OrderStatus.DISPATCHED)) {
            throw new ApiException(409, "Transición ilegal " + order.getStatus() + " → DISPATCHED");
        }
        Robot robot = robots.findByStatusAndBatteryGreaterThanEqualOrderByIdAsc(RobotStatus.IDLE, MIN_BATTERY)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(409, "No hay AMR disponible (libre y con batería ≥ " + MIN_BATTERY + "%)"));

        Warehouse w = order.getWarehouse();
        RouteDto route = planner.plan(new Point(w.getOriginX(), w.getOriginY()),
                new Point(order.getDestX(), order.getDestY()));
        int eta = carrierEta.orElse(route.cost() * 2);

        order.setStatus(OrderStatus.DISPATCHED);
        order.setRobot(robot);
        order.setEtaMinutes(eta);
        robot.setStatus(RobotStatus.BUSY);

        dispatched.increment();
        events.publishAfterCommit(order.getId());
        return OrderDto.from(order);
    }

    /** Solo se pueden borrar pedidos CREATED (aún sin robot asignado). */
    @Transactional
    public void delete(Long id) {
        Order order = find(id);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new ApiException(409, "Solo se pueden borrar pedidos CREATED; este está " + order.getStatus());
        }
        orders.delete(order);
    }

    /** Transiciones restantes: IN_TRANSIT, DELIVERED, CANCELLED. Valida contra la máquina de estados. */
    @Transactional
    public OrderDto transition(Long id, OrderStatus to) {
        if (to == OrderStatus.DISPATCHED) {
            throw new ApiException(400, "Para despachar usa POST /api/orders/{id}/dispatch");
        }
        Order order = find(id);
        if (!order.getStatus().canGoTo(to)) {
            throw new ApiException(409, "Transición ilegal " + order.getStatus() + " → " + to);
        }
        Robot robot = order.getRobot();
        if (to == OrderStatus.DELIVERED && robot != null) {
            Warehouse w = order.getWarehouse();
            RouteDto route = planner.plan(new Point(w.getOriginX(), w.getOriginY()),
                    new Point(order.getDestX(), order.getDestY()));
            robot.setBattery(robot.getBattery() - route.cost());   // 1% de batería por unidad de costo (ida y vuelta)
            robot.setStatus(RobotStatus.IDLE);
        }
        if (to == OrderStatus.CANCELLED && robot != null) {
            robot.setStatus(RobotStatus.IDLE);
            order.setRobot(null);
        }
        order.setStatus(to);
        return OrderDto.from(order);
    }

    private Order find(Long id) {
        return orders.findById(id).orElseThrow(() -> new ApiException(404, "Pedido " + id + " no existe"));
    }

    private static String newCode() {
        return "LP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
