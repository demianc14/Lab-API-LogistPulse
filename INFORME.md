# INFORME — Laboratorio LogistPulse

> Borrador: las respuestas ★ deben reescribirse con mis propias palabras antes de entregar.

## Parte 0 · Recorrido

**Qué cambié:** nada de código (solo `git init`, commit inicial y este informe).

**Cómo lo probé** (Modo A, H2 en memoria, sin Docker):
- `mvn test` → `OrderStatusTest` 4 tests, `AStarPathfinderTest` 6 tests; 0 fallos, 0 errores.
- `bash scripts/smoke.sh` → los 10 pasos terminan bien (salud 200, ruta bloqueada 422, crear 201, validación 400,
  despachar 200 y repetir 409, saltar estados 409). El paso 9 devuelve 409 porque sin RabbitMQ (Modo A) el pedido
  se queda en `DISPATCHED` y no pasa a `IN_TRANSIT`; el propio script lo anticipa.

**Flujo de `POST /api/orders/{id}/dispatch`** (`OrderResource` → `OrderService.dispatch`, una sola `@Transactional`):
1. `find(id)` → 404 si no existe.
2. `OrderStatus.canGoTo(DISPATCHED)` → 409 si el estado actual no lo permite.
3. Busca el primer AMR `IDLE` con batería ≥ 20 → 409 si no hay.
4. `RoutePlanner.plan` (A*) desde el origen del almacén hasta el destino.
5. `CarrierClient.etaMinutes` (HTTP); si falla, ETA = `route.cost() * 2`.
6. Pedido → `DISPATCHED`, asigna robot y ETA; robot → `BUSY`.
7. `events.publishAfterCommit(id)`: el evento sale solo si la transacción hace commit.

**★ `JerseyConfig`:** es el `ResourceConfig` de Jersey; registra explícitamente los recursos
(`OrderResource`, `RobotResource`, `RouteResource`, `UiResource`) y los mappers de excepciones. Si olvido registrar un
recurso, Jersey no lo conoce: sus rutas devuelven 404 aunque la clase compile y tenga `@Path`.
Comprobado en la Parte 1: con `register(RobotResource.class)` comentado, `GET /api/robots/1` devolvió 404
(página HTML de Tomcat) mientras `/api/orders/1` seguía respondiendo 200.

**Qué no hice:** Modo B (Docker) no se probó en esta parte.

## Parte 1 · Jersey / JAX-RS

**Qué cambié:**
- `RobotResource` + `RobotService.get`: `GET /api/robots/{id}` con `@PathParam`; 404 con `ApiException` si no existe.
- `OrderResource` + `OrderService.delete`: `DELETE /api/orders/{id}`; 204 si el pedido está `CREATED`, 409 en otro estado, 404 si no existe.
- `ConstraintViolationMapper` (nuevo, registrado en `JerseyConfig`): `ConstraintViolationException` → 400 con `{"status":400,"error":"Datos inválidos","fields":[{"field","message"}]}`.

**Cómo lo probé** (Modo A, `curl` contra la app en marcha):
| Petición | Resultado |
|---|---|
| `GET /api/robots/1` | 200 `AMR-01` |
| `GET /api/robots/99` | 404 `Robot 99 no existe` |
| `DELETE /api/orders/4` (CREATED) | 204; luego `GET` → 404 |
| `DELETE /api/orders/1` (tras despacharlo, DISPATCHED) | 409 |
| `DELETE /api/orders/999` | 404 |
| `POST /api/orders` con `code:"mal"`, `customer:""`, `destX:-1` | 400 con 3 campos: `code`, `customer`, `destX` |

**★ Equivalencias Jersey → Spring MVC** (a verificar y reescribir con mis palabras):
- `@QueryParam` → `@RequestParam`
- `@Context UriInfo` → `UriComponentsBuilder` / `ServletUriComponentsBuilder` (o `HttpServletRequest`)
- `ExceptionMapper<E>` → `@ExceptionHandler` (dentro de un `@ControllerAdvice`)

**Qué no hice:** no escribí tests automáticos para estos endpoints; la evidencia es manual con `curl`.

## Parte 2 · JPA e Hibernate (N+1)

**Qué cambié:** `Order`: `@Table(indexes = @Index(name = "idx_orders_status", columnList = "status"))`.
`OrderRepository` quedó igual que al inicio (quité y restauré `@EntityGraph` solo para medir).
`spring.jpa.show-sql` lo activé por variable de entorno (`SPRING_JPA_SHOW_SQL=true`) para no dejarlo encendido en el repo.

**Cómo lo medí:** app en Modo A con show-sql, 12 pedidos (3 semilla + 9 creados) y 4 despachados (ids 9–12, cada uno
con un AMR distinto). Conté las líneas `select` que genera solo `GET /api/orders?size=10` (devuelve 10 pedidos):

| Variante | Consultas SQL | Detalle |
|---|---|---|
| Con `@EntityGraph(warehouse, robot)` | **2** | 1 `select` con joins + 1 `count` de la paginación |
| Sin `@EntityGraph` | **7** | 1 `select` de pedidos + 1 `count` + 1 `warehouse where id=?` + 4 `robot where id=?` |

**★ ¿Cuántas consultas aparecen y por qué?** Sin el grafo, `warehouse` y `robot` son `LAZY`: la consulta de pedidos no los trae y
`OrderDto.from` los toca al mapear, así que Hibernate lanza una consulta extra por cada asociación distinta aún no cargada
(el almacén es uno solo y se reutiliza del contexto de persistencia; hubo 4 robots distintos). Es el patrón N+1: crece con
el número de robots distintos de la página (máximo 1 + 1 + 1 + 10 con size=10, y 5 robots en este lab). Con `@EntityGraph` todo
va en un `join` y el número de consultas deja de depender de los datos. (Las cifras dependen de mis datos de prueba; con
otros datos el N cambia.)

**Índice sobre `status`:** verificado en el arranque (`create index idx_orders_status on orders (status)`).
Justificación: `findByStatus` y las consultas operativas filtran por estado. Con solo 5 valores la cardinalidad es baja,
así que el índice solo ayuda cuando el estado buscado es raro (pocos pedidos vivos frente al histórico `DELIVERED`);
con pocos datos como en el lab, no se nota. Costo: cada cambio de estado actualiza también el índice.

**Qué no hice:** no medí tiempos ni usé `EXPLAIN`, así que no puedo afirmar que el índice mejore algo en este lab (H2 con 12 filas).
