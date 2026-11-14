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

## Parte 3 · Spring Data, paginación y HATEOAS

**Qué cambié:**
- `OrderRepository`: `findByCustomerNameContainingIgnoreCase` y `findByStatusAndCustomerNameContainingIgnoreCase` (ambos con `@EntityGraph`).
- `OrderService.list(status, customer, sort, page, size)`: elige el método según haya `customer`/`status`; `parseSort` valida `?sort=campo[,asc|desc]`
  contra la lista blanca `SORTABLE` (`id, code, customer, status, createdAt, etaMinutes`; `customer` se mapea a `customerName`).
  Un campo o dirección inválidos → `ApiException(400)`. Siempre se añade `id desc` como desempate.
- `OrderResource.list`: nuevos `@QueryParam("customer")` y `@QueryParam("sort")`; los enlaces `self/first/prev/next/last` conservan ambos.
- Test nuevo `OrderSortTest` (5 tests sobre `parseSort`).

**Cómo lo probé** (`curl` con la app en marcha + `mvn test`: 15 tests, 0 fallos; antes eran 10):
| Petición | Resultado |
|---|---|
| `?customer=ALFA` | 200, 2 pedidos ("Alfa Market", "alfa ferretería"): no distingue mayúsculas |
| `?customer=zETa&status=DISPATCHED` | 200, 1 pedido (Zeta Foods) |
| `?customer=nadie` | 200, lista vacía |
| `?customer=%` | 200, lista vacía: el `%` se escapa, no actúa como comodín |
| `?sort=createdAt,desc&size=2&customer=a` | 200; el enlace `next` conserva `customer` y `sort` |
| `?sort=password,desc` | 400 con los campos permitidos |
| `?sort=id,sideways` / `?sort=createdAt,asc,x` | 400 |

**Limitación vista:** `sort=customer` ordena por valor binario en H2 (mayúsculas antes que minúsculas: "alfa ferretería" quedó después de "Zeta Foods").

**★ Grafo de estados y enlaces de `OrderDto`** (enlaces observados en respuestas reales; todos llevan además `self` y `collection`):
```
CREATED  [dispatch, to-cancelled] ──dispatch──▶ DISPATCHED [to-in_transit, to-cancelled] ──▶ IN_TRANSIT [to-delivered] ──▶ DELIVERED []
   │                                                  │
   └──────────────── to-cancelled ───────────────────┴──▶ CANCELLED []
```
`dispatch` tiene su propio endpoint (`POST /{id}/dispatch`); el resto de `to-*` apuntan a `POST /{id}/transitions` con `{"to": ...}`.
Los enlaces salen de `OrderStatus.next()` en `OrderResource.linked`, así que cliente y servidor comparten la misma máquina de estados.
Observación: `DELETE` solo funciona en `CREATED` pero no se anuncia como enlace.

**Reto: ¿por qué `size` se limita a 100 en el servicio y no solo en el recurso?** (respuesta mía, a reescribir) El recurso no es la única
puerta de entrada al servicio: otro recurso, un job o un test pueden llamar a `OrderService.list` directamente y saltarse el límite.
La regla de protección (no traer miles de filas con sus joins) pertenece a la capa que ejecuta la consulta; el recurso solo traduce HTTP.

**Qué no hice:** no hay tests de integración HTTP del filtro (la evidencia es `curl`); `sort` no es insensible a mayúsculas.

## Parte 4 · Máquina de estados y concurrencia

**Qué cambié:**
- `OrderStatus`: nuevo estado `RETURNED`; `DELIVERED → RETURNED` y `RETURNED` es terminal (`DELIVERED` deja de serlo).
- `index.html` (consola): color para `RETURNED` y se excluye de los pedidos "activos" del plano.
- `OrderStatusTest`: de 4 a 10 tests (6 nuevos: entregado→devuelto, `RETURNED` terminal, solo `DELIVERED` puede ir a `RETURNED`,
  la entrega no se deshace, nadie vuelve a `CREATED` ni a sí mismo, y la matriz completa de transiciones).
  Actualicé `terminalStatesHaveNoExit`, que afirmaba que `DELIVERED` era terminal (contrato que cambió a propósito).
- `mvn test`: 21 tests, 0 fallos.

**Probado a mano (`curl`):** `CREATED→RETURNED` 409, `DISPATCHED→RETURNED` 409, `DELIVERED→RETURNED` 200,
`RETURNED→DELIVERED` 409; el pedido devuelto no ofrece enlaces y sale con `?status=RETURNED`.

**★ Concurrencia — dos despachos simultáneos** (script con dos `curl … &` + `wait`, 10 rondas):
| Caso | Resultado en las 10 rondas |
|---|---|
| Mismo pedido, 2 despachos | siempre un `200` y un `409` (`Conflicto de concurrencia, reintenta la operación`); el ganador varía (a veces la 1ª petición, a veces la 2ª) |
| Dos pedidos distintos | siempre un `200` (AMR-01) y un `409`; **nunca** dos pedidos con el mismo AMR |

- **¿Cuál gana y qué recibe el perdedor?** Gana la transacción que hace commit primero; es impredecible. La otra falla al hacer `UPDATE … WHERE id=? AND version=?`
  (0 filas afectadas) → `ObjectOptimisticLockingFailureException` → `OptimisticLockMapper` → `409`. En el caso "mismo pedido" cuenta el `@Version` de `Order` y/o el de `Robot`
  (ambos buscan el mismo AMR); en el caso "pedidos distintos" lo para el `@Version` de `Robot`, porque ambos eligen el primer AMR libre (AMR-01) y solo uno puede cambiarlo a `BUSY`.
  (Solo capturé el cuerpo del perdedor en una ronda del caso distinto: `Conflicto de concurrencia…`; en el resto solo anoté los códigos.)
- **No logré provocar el mismo AMR en dos pedidos.** Pero la seguridad cuesta disponibilidad: el perdedor recibe 409 aunque AMR-02 estuviera libre; el cliente debe reintentar.
- **Hallazgo — evento fantasma en Modo A:** el log mostró 40 líneas `evento order.dispatched` para 20 despachos exitosos. Con RabbitMQ desactivado,
  `DispatchEventPublisher.publishAfterCommit` registra en el log **al instante**, sin esperar al commit, así que el perdedor (que hace rollback) también "publica".
  Con RabbitMQ activo sí usa `afterCommit` y el perdedor no emitiría nada (no lo probé: requiere Modo B). No lo corregí; es material de la Parte 7.

**★ Invariantes del sistema y si la BD las garantiza hoy** (DDL observado con `show-sql` en H2; `ddl-auto=update`):
| # | Invariante | ¿La BD la garantiza? | Cómo se cumple hoy |
|---|---|---|---|
| 1 | `code` de pedido y de robot únicos | **Sí** | `UNIQUE` |
| 2 | Pedido siempre con almacén; robot siempre con almacén; FK válidas | **Sí** | `NOT NULL` + `FOREIGN KEY` |
| 3 | `status` solo toma valores válidos | **Sí** (en H2, columna `enum(...)`) | tipo enum de la BD; incluye `RETURNED` por ser una BD nueva |
| 4 | Transiciones válidas (`CREATED→DISPATCHED→…`) | **No** | solo `OrderStatus.canGoTo` en Java; un `UPDATE` directo salta estados |
| 5 | Un AMR `BUSY` tiene exactamente un pedido `DISPATCHED/IN_TRANSIT` | **No** | `OrderService` + `@Version` de `Robot` (lo vi aguantar 20 rondas) |
| 6 | Un AMR no tiene dos pedidos activos a la vez | **No** | no hay índice único parcial sobre `robot_id` para estados activos; solo el bloqueo optimista |
| 7 | Pedido `DISPATCHED/IN_TRANSIT` tiene robot (y `CREATED` no) | **No** | `robot_id` es nullable y sin `CHECK`; lo mantiene `dispatch`/`transition` |
| 8 | Despachar exige AMR `IDLE` con batería ≥ 20 % | **No** | comprobación-y-luego-escritura en Java (`MIN_BATTERY`) |
| 9 | Batería entre 0 y 100 | **No** | `Robot.setBattery` recorta; sin `CHECK` |
| 10 | Solo se borra un pedido `CREATED`; destino dentro de la grilla y no bloqueado | **No** | `OrderService.delete` / `planner.isWalkable` (la grilla vive en memoria) |
| 11 | Escrituras concurrentes sobre la misma fila no se pisan | **Parcial** | la columna `version` es de la BD, pero la lógica de comparar es de Hibernate |

**Riesgo no verificado:** con `ddl-auto=update` sobre una base ya existente (p. ej. el volumen de Postgres del compose), Hibernate no modifica
restricciones o tipos existentes; no probé si ahí `RETURNED` sería rechazado. Es un argumento para el proyecto de cierre con Flyway + `CHECK`.

**Qué no hice:** la prueba de carrera es un script manual, no un test automático; no probé Modo B; no probé 3+ despachos simultáneos.

## Parte 6 · RestClient y resiliencia

**Entorno:** Modo C (API con Maven en el puerto **8081**, `SERVER_PORT=8081`, porque otro contenedor mío ocupa 127.0.0.1:8080; y solo `carrier-mock` = WireMock 3.9.1 en Docker).

**Qué cambié:** `OrderService.dispatch` ya no es `@Transactional`. Ahora: (1) lee el pedido y comprueba que la transición es legal (para no llamar al transportista en vano),
(2) llama a `CarrierClient.etaMinutes` **sin transacción abierta**, (3) ejecuta la parte de base de datos (`dispatchInTx`: revalidar estado, elegir AMR, planificar A*, actualizar, publicar evento)
en una transacción corta con `TransactionTemplate`. Uso `TransactionTemplate` y no un segundo `@Transactional` porque una llamada interna dentro del mismo bean no pasa por el proxy y la anotación no se aplicaría.

**6.1 `LP-SLOW1`** (medido): tarda **2,02 s** (WireMock tardaría 5 s; `CarrierClient` corta a los 2 s) y devuelve `etaMinutes: 36`.
★ **¿Qué ETA queda y de dónde sale?** 36 = `route.cost()` (18, ruta A* de (0,0) a (11,7)) × 2: es el ETA local de respaldo de `OrderService`
(`carrierEta.orElse(route.cost() * 2)`), no los 99 que respondería WireMock.
★ **¿Se avisa en el log?** Sí, hay un `WARN` de `CarrierClient`: `Transportista no disponible para LP-SLOW1: I/O error on GET request … Request cancelled`. Pero es insuficiente:
no dice que fue un timeout, ni que se usó un ETA estimado, y la respuesta de la API no distingue un ETA del transportista de uno estimado (ambos son `etaMinutes`).

**6.2 Transportista apagado** (`docker compose stop carrier-mock`): el despacho responde `200` en **0,01 s** (conexión rechazada, falla rápido), ETA 36, log `…: null` (aún menos útil que el timeout).
Al volver a encenderlo, el siguiente pedido recibió de nuevo ETA 25 en 0,25 s.

**★ 6.3 Trampa: HTTP dentro de la transacción.** Riesgo: la transacción retiene una conexión de la pool de BD (Hikari, máx. 10 aquí) durante toda la espera al servicio externo (hasta 2 s por el timeout),
aunque la conexión no haga nada. Con unas pocas peticiones lentas se agota la pool y el resto de la API, incluso endpoints sin relación, se queda esperando conexión. Además la transacción
queda abierta más tiempo (más ventana para conflictos de `@Version` y, en Postgres, sesiones "idle in transaction").
Medición (app nueva cada vez, 12 despachos `LP-SLOW01…12` simultáneos y un `GET /api/robots` lanzado 0,6 s después):
| | Antes (HTTP dentro de la tx) | Después (HTTP antes de la tx) |
|---|---|---|
| `GET /api/robots` sin carga | 0,003 s | 0,003 s |
| `GET /api/robots` con 12 despachos lentos | **1,47 s** | **0,008 s** |
| Despacho más lento | **4,10 s** (12 > 10 conexiones: dos tandas de 2 s) | 2,10 s |
Funcionalidad intacta: normal 200/ETA 25; `LP-SLOW99` 200/ETA 36 en 2,02 s; repetido 409 sin llamar a WireMock; inexistente 404. `mvn test`: 21 tests, 0 fallos.

**Costes del diseño nuevo:** se puede consultar el ETA y luego fallar con 409 (sin AMR libre o conflicto de `@Version`): una llamada externa "desperdiciada".
El ETA puede quedar algo desfasado respecto al momento del commit. El despacho sigue durando 2 s en el caso lento: el refactor protege la pool, no la latencia de esa petición.

**Qué no hice:** el reto 6.4 (reintento con backoff solo para 5xx); no hay test automático (el test de Mockito de `dispatch` es de la Parte 9); las cifras de carga son de una sola ejecución en mi máquina.

## Parte 7 · Mensajería, consistencia y Outbox

**Actualización (hecha durante la Parte 8, con el stack Docker completo):** al principio no pude levantar RabbitMQ (la imagen local `rabbitmq:3.13-management` fallaba con
`Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces`, 3 variantes probadas), así que verifiqué esta parte en Modo A y con tests. Más tarde `docker compose up` con `rabbitmq:4-management` (4.4.0) sí arrancó, y comprobé en vivo:
- Flujo real: `dispatch` → log `Evento order.dispatched publicado id=1` (OutboxPublisher) → `Flota recibió order.dispatched id=1` → `Pedido 1 → IN_TRANSIT`; la cola `orders.dispatched` quedó con 0 listos / 0 sin confirmar y 1 consumidor.
- **Duplicado (7.5):** publiqué otra vez el mensaje `1` desde la API de gestión de RabbitMQ (`routed: true`): el log dijo `Mensaje duplicado u obsoleto para el pedido 1 (ya no está DISPATCHED): se ignora`, el pedido siguió en `IN_TRANSIT` y la cola quedó en 0 (no se reencoló).
- Sigue **sin** observarse 7.2 (parar el consumidor y ver acumularse mensajes); no lo repetí.

**Qué cambié:**
- `OutboxEvent` + `OutboxEventRepository` (tabla `outbox_event`: `type`, `payload`, `createdAt`, `sentAt` nulo hasta publicar).
- `OrderService.dispatchInTx` ahora hace `outbox.save(new OutboxEvent("order.dispatched", id))` **dentro de la misma transacción** que el cambio de estado.
- `OutboxPublisher` (`@Scheduled`, cada 1 s, configurable con `lab.outbox.poll-ms`): publica las pendientes en orden y las marca enviadas; si el broker falla, se detiene y reintenta en la siguiente pasada.
  `@EnableScheduling` en `LabApplication`. Eliminé `DispatchEventPublisher` (quedaba sin uso).
- `OrderService.startTransit` + `FleetListener`: el consumidor solo avanza `DISPATCHED → IN_TRANSIT`; en cualquier otro caso (duplicado, pedido ya avanzado/cancelado, inexistente) registra "ignorado" y confirma el mensaje.

**★ 7.3 `afterCommit`: qué fallo evita y cuál sigue existiendo**
- Evita publicar un evento de una transacción que **no** llegó a commit (rollback por conflicto `@Version`, error de BD) y que el consumidor lea el pedido antes de que el cambio sea visible.
  Lo vi en la práctica con el camino "lite", que escribe el evento sin esperar al commit: en las 20 rondas concurrentes de la Parte 4 hubo **40 eventos para 20 despachos** (el perdedor, que hacía rollback, también "publicaba").
- Sigue existiendo: si el proceso cae **entre el commit y el envío**, la BD dice `DISPATCHED` y el AMR `BUSY`, pero el evento se perdió: el pedido nunca pasa a `IN_TRANSIT` y nadie se entera.
  (También es de esperar que, con el broker caído en el `afterCommit`, el cliente vea un error aunque el despacho ya esté confirmado; no lo comprobé.)

**★ 7.4 Outbox** (medido en Modo A, mismas 20 rondas concurrentes): **20 despachos exitosos → 20 eventos, sin ids repetidos** (antes 40). Esto cierra la ventana del commit-envío: el evento queda
confirmado junto con el cambio de estado y el publicador lo reintenta hasta lograr enviarlo.
Límites del diseño: garantía **al menos una vez** (si cae tras enviar y antes de marcar `sentAt`, se reenvía → hace falta consumidor idempotente); la latencia añade hasta ~1 s del sondeo;
las filas enviadas nunca se purgan (la tabla crece); no hay confirmaciones del publicador (`publisher confirms`); con varias instancias de la API haría falta `SELECT … FOR UPDATE SKIP LOCKED` para no enviar doble; sin índice en `sent_at`.

**★ 7.5 Idempotencia: ¿qué pasa hoy si llega dos veces el mismo mensaje?**
- El código original llamaba a `transition(id, IN_TRANSIT)`. La segunda vez el pedido ya está `IN_TRANSIT`, `canGoTo(IN_TRANSIT)` es falso (comprobado en `OrderStatusTest`: ningún estado se transita a sí mismo) y lanza `ApiException(409)` desde el listener.
  Con la configuración por defecto de Spring AMQP, una excepción en el listener hace que el mensaje se **reencole y se reintente sin fin** (esto lo deduzco del comportamiento por defecto de Spring AMQP; **no observé el antes**, solo comprobé que con la guarda el duplicado se ignora).
- Ahora `startTransit` solo actúa si el estado es `DISPATCHED`; un duplicado devuelve `false`, se registra y se confirma. La guarda también protege de un mensaje obsoleto: sin ella, un pedido `DELIVERED` podría volver a `IN_TRANSIT`
  (el test falla si quito la guarda; lo comprobé).
- Si dos consumidores procesan el mismo mensaje a la vez, el `@Version` hace fallar a uno; esa excepción no se captura a propósito: el broker reentrega y el reintento ya ve el estado final (→ "ignorado").
- No resuelto: un mensaje con payload no numérico seguiría reintentándose indefinidamente (no hay cola de mensajes muertos).

**Tests nuevos** (`mvn test`: **32 tests, 0 fallos**; antes 21): `OutboxPublisherTest` (5: envía en orden y marca, broker caído deja pendiente, fallo a mitad de lote, modo lite, sin pendientes),
`FleetListenerIdempotencyTest` (4), `DispatchOutboxTest` (2, con Spring + H2: un despacho exitoso crea un evento; los despachos rechazados —ya despachado, sin AMR— no crean ninguno).
Nota: la primera versión de la prueba de integración se llamaba `…IT` y Surefire no la ejecutaba; la renombré a `…Test` y la corrí.

**Qué no hice:** 7.2 (consumidor parado y mensajes acumulándose); ver el "antes" del duplicado (bucle de reencolado); un test de que el evento se revierte si la transacción falla *después* del insert; purga de eventos enviados.

## Parte 8 · Observabilidad y "guerra de resiliencia"

**Entorno:** stack completo con `docker compose up -d --build` (Postgres 17, Redis 7, RabbitMQ 4.4.0, WireMock, API, Prometheus 3.0.1, Grafana 11.4.0), usando una **copia del compose** (fuera del repo) que publica la API en el **8081**,
porque otro contenedor mío ocupa 127.0.0.1:8080. Grafana en `localhost:3000`, Prometheus en `localhost:9090`.
No hice capturas de pantalla de Grafana: las cifras de abajo salen de consultas a la API de Prometheus y de scripts con tiempos; las capturas hay que tomarlas a mano.

**Hallazgos previos (cambian lo que dice LAB.md):**
1. **No existía `http_server_requests_*`.** Spring Boot 4.1 no registra métricas HTTP de servidor para Jersey (solo `http_client_requests`), así que el panel 5 del dashboard y el 8.4 no podían funcionar.
   Solución: `HttpMetricsFilter` (filtro Jersey) que registra el `Timer` `http.server.requests` con `method`, `uri` (normalizada: `/api/orders/{id}`), `status`, `outcome`; más la propiedad
   `management.metrics.distribution.percentiles-histogram.http.server.requests=true`. Verificado: 138 series de buckets en `/actuator/prometheus`.
2. **El panel 1 consultaba una serie inexistente** (`logistpulse_orders_created_total`; Prometheus reserva el sufijo `_created`, el contador se expone como `logistpulse_orders_total`). Lo corregí en `logistpulse.json`.

**8.1 / 8.2 Carga y flota agotada:** 40 pedidos con el comando del LAB (43 en total). Despachando seguidos, los 3 primeros dieron `200` (AMR-02, 03, 04, con AMR-01 ya ocupado) y los siguientes (pedidos 5–12) **`409 "No hay AMR disponible (libre y con batería ≥ 20%)"`**.
★ **¿Qué estado HTTP y qué panel lo muestra?** `409 Conflict`. Lo muestra "HTTP: peticiones/s por estado" (serie `status=409`, que antes del arreglo 1 estaba vacío) y "AMR en misión". Prometheus dio `logistpulse_robots_busy = 4`.
Observación: **"AMR libres" marcó 1** aunque ninguno era despachable: AMR-05 estaba `IDLE` con 15 % de batería. El panel engaña, porque cuenta libres sin mirar el umbral `MIN_BATTERY`. Tras `POST /api/robots/5/charge` (batería 100) el despacho funcionó (`200`).

**8.3 Caos** (un servicio cada vez; tiempos medidos con script, 1 ejecución cada uno):
| Servicio parado | Qué ocurre | Cuánto tarda en recuperarse |
|---|---|---|
| **RabbitMQ** | `dispatch` sigue en `200` (0,28 s) gracias al Outbox; `health` → `503` (`rabbit: DOWN`); el pedido se queda `DISPATCHED`; el log avisa `Outbox: no se pudo publicar…` una vez por segundo (10 avisos en ~9 s) | pedido a `IN_TRANSIT` **4,2 s** después de arrancar el broker |
| **Redis** | `/api/routes`, `/actuator/health` y `dispatch` se **cuelgan 60 s** y luego `500` / `503` (`redis: DOWN`); `/api/robots` (no usa caché) sigue en `200` en 0,02 s | rutas OK **9,7 s** después de arrancar Redis |
| **WireMock (transportista)** | `dispatch` `200` en 0,14 s con ETA de respaldo (30 = coste 15 × 2); `health` sigue `UP` (no hay indicador del transportista); solo queda un `WARN` en el log | ETA del transportista (25) de nuevo **1,4 s** después de arrancar |
| **Postgres** | `/actuator/health` → `503` a los 30 s; `/api/robots` → `500` a los 30 s; **`/actuator/prometheus` tarda 60 s** → Prometheus (timeout de scrape 5 s) pierde la API: `min_over_time(up[20m]) = 0` | `/api/robots` `200` **8,3 s** después de arrancar Postgres |
Sobre los paneles: lo anterior lo vi por métricas y logs, **no** comprobé visualmente qué panel cambió en Grafana. Con las métricas de HTTP corregidas deberían verse los `5xx` en "HTTP: peticiones/s por estado".

**Problemas que destapó el caos (no corregidos, salvo indicación):**
- **Monitoreo ciego cuando cae la BD:** `FleetMetrics` calcula los gauges `robots_idle/busy` con una consulta SQL **en cada scrape**; sin BD, cada gauge espera 30 s (pool) y el scrape tarda 60 s. Arreglo propuesto (no hecho): refrescarlos periódicamente en memoria, como hice con las métricas del Outbox.
- **Redis es dependencia dura:** sin Redis la caché lanza error en vez de degradar (se pierde el despacho). Propuesta: un `CacheErrorHandler` que ignore los fallos de caché y calcule la ruta. Además `dispatch` llama a `planner.plan` **dentro** de la transacción, así que con Redis caído retendría una conexión de la pool hasta 60 s: es la misma trampa de la Parte 6 (lo deduzco; no medí el agotamiento de la pool).
- El transportista caído solo deja un `WARN`: ni métrica ni panel ni `health`.

**8.4 Panel p95:** `Latencia p95 HTTP (s)` (id 7) en `infra/grafana/dashboards/logistpulse.json`, con `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/.*"}[1m])))` y una serie solo para `dispatch`.
Con mi tráfico: p95 de `dispatch` = **0,33 s**, p95 de `/api/**` = 0,15 s (esta segunda de una muestra pequeña, sin las lecturas de `/api/orders`, que mi script no llegó a ejecutar por un error de comillas en zsh). Grafana cargó el dashboard con 8 paneles.
Añadí también el panel 8 "Outbox: eventos pendientes y antigüedad" y las métricas `logistpulse_outbox_pending` y `logistpulse_outbox_oldest_pending_seconds` (calculadas en cada pasada de `OutboxPublisher`, sin consultar la BD en el scrape).
Verificado con RabbitMQ parado: pendientes = 1 y antigüedad 17 s → 27 s; al arrancarlo, 0.

**★ 8.5 Tres SLOs y su PromQL** (las tres consultas se ejecutan en Prometheus; los resultados son con mi tráfico de prueba, no una medición de 30 días):
1. **Disponibilidad del despacho ≥ 99,5 % (30 días).** Cuentan como fallo solo los `5xx`; un `409` (sin AMR, estado ilegal) o `404` es un rechazo correcto, no indisponibilidad.
   `1 - ((sum(rate(http_server_requests_seconds_count{uri="/api/orders/{id}/dispatch",status=~"5.."}[30d])) or vector(0)) / sum(rate(http_server_requests_seconds_count{uri="/api/orders/{id}/dispatch"}[30d])))` → con ventana de 1 h dio 1,0.
2. **Latencia p95 del despacho ≤ 500 ms.** `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/orders/{id}/dispatch"}[5m]))) <= 0.5` → 0,32 s. El umbral sale de lo medido (~0,3 s con el transportista normal, que responde en ~120 ms);
   un transportista lento (corte a 2 s) lo incumpliría, que es justo lo que debe detectar.
3. **Tiempo máximo en `DISPATCHED` ≤ 60 s.** Proxy: `max_over_time(logistpulse_outbox_oldest_pending_seconds[5m]) <= 60` → 27 s en el corte de RabbitMQ.
   **Limitación importante:** mide solo el retraso del lado del Outbox. Si el consumidor muere con el Outbox vacío, los pedidos se quedarían en `DISPATCHED` y este SLO seguiría "verde". La medida exacta requeriría guardar `dispatchedAt` en el pedido o un gauge por estado
   (`logistpulse_orders_by_status`, el reto 8.6), que **no implementé**.

**Qué no hice:** el reto 8.6 (gauge por estado y alerta "AMR libres = 0 durante 1 min"); arreglar `FleetMetrics` y el `CacheErrorHandler`; capturas de Grafana; repetir cada caos más de una vez; ver Grafana (solo comprobé por su API que cargó los 8 paneles).
`mvn test`: **36 tests, 0 fallos** (+2 del Outbox, +2 de `HttpMetricsFilter`).

## Parte 9 · Pruebas y contenedores

**9.1 Postman** (`postman/logistpulse.postman_collection.json`). La colección ya traía tests de `201` y un `Location` flojo (`include('/api/orders/')`) en "Crear pedido". Añadí 3 tests nuevos que no se solapan con esos:
| Test nuevo | Dónde | Qué comprueba |
|---|---|---|
| Código de estado | "Listar pedidos" (no tenía ninguno) | `200` y `Content-Type` con `application/json` |
| `Location` | "Crear pedido" | es **exactamente** `{{host}}/api/orders/<id del cuerpo>` |
| Esquema | "Crear pedido" | `pm.response.to.have.jsonSchema(...)`: campos obligatorios y tipos del `OrderDto`, `code` con patrón `^LP-`, `status` = `CREATED`, `robot`/`etaMinutes` nulos permitidos, `links` con `self`, `collection`, `dispatch` |
**⚠ No ejecuté el Runner de Postman** (es gráfico y no tengo `newman`; no quise descargarlo sin permiso). Lo que sí verifiqué: el JSON de la colección es válido; los 8 scripts pasan `node --check`;
y contra una respuesta real de la API (`POST /api/orders` → `201`, `Location: http://localhost:8081/api/orders/60`) el `Location` coincide exacto y el cuerpo cumple el esquema (comprobado con un verificador a mano de `required`, tipos, patrón y `enum`, **no** con Ajv, que es lo que usa Postman).
Cambié un test mío antes de dar esto por bueno: usaba `have.header(nombre, /regex/)` y no estoy seguro de que Postman acepte regex ahí; lo sustituí por `expect(headers.get(...)).to.include(...)`.
Nota: la variable `host` de la colección vale `http://localhost:8080`; en esta máquina 8080 es de otro proyecto, así que en el Runner hay que poner `host = http://localhost:8081`.

**9.2 Test de Mockito de `OrderService.dispatch`** (`OrderServiceDispatchTest`, 7 tests, sin BD ni red):
- sin robots → `409 "No hay AMR disponible…"` y el pedido sigue `CREATED`, sin evento en el Outbox;
- pedido ya despachado → `409 "DISPATCHED → DISPATCHED"` y **no** se llama al transportista;
- con el ETA caído (`Optional.empty()`) → `etaMinutes = route.cost() * 2` (18 × 2 = 36), pedido `DISPATCHED`, robot `BUSY`;
- extras: si el transportista responde se usa su ETA (25); el despacho exitoso guarda el evento `order.dispatched` y hace commit; el transportista se consulta **antes** de abrir la transacción (`InOrder`, protege el refactor de la Parte 6); pedido inexistente → `404`.
Comprobé que los tests detectan fallos con 2 mutaciones temporales (ya revertidas): cambiar `cost() * 2` por `cost()` hace fallar el test del ETA; quitar la validación previa de estado hace fallar el de "no llama al transportista".
`mvn test`: **43 tests, 0 fallos**.

**★ 9.3 Dockerfile** (respuestas basadas en la imagen que construimos con `docker compose`):
- **¿Por qué dos etapas?** La primera (`maven:3.9-eclipse-temurin-21`) compila; la segunda (`eclipse-temurin:21-jre`) solo ejecuta y recibe únicamente `app.jar`. Así la imagen final no lleva Maven, ni compilador, ni el código fuente, ni la caché de dependencias.
  Lo comprobé dentro del contenedor: `mvn` y `javac` no están (solo JRE). No pude comparar tamaños (la imagen de compilación no quedó en la lista local de Docker); la final pesa 626 MB.
- **¿Qué se cachea con `dependency:go-offline`?** Se copia solo el `pom.xml` y se descargan todas las dependencias en una capa propia; mientras el `pom.xml` no cambie, Docker reutiliza esa capa y no vuelve a descargarlas.
  Lo comprobé: tras cambiar solo un archivo de `src/`, en la reconstrucción `COPY pom.xml` y `RUN mvn … dependency:go-offline` salieron `CACHED`, y solo se repitieron `COPY src` y `mvn package`.
- **¿Por qué `USER appuser`?** Para que el proceso no corra como root. Dentro del contenedor el proceso 1 es `appuser` (uid 999) y `/app` es de root: `touch /app/prueba` da `Permission denied`, o sea que una app comprometida no puede modificar el jar ni su carpeta, y no tiene privilegios de root dentro del contenedor.
  Es una capa de defensa, no una garantía total (no impide por sí solo un escape del contenedor).

**Reto 9.4 (no hecho):** el `healthcheck` del servicio `api` en el compose. Dato relevante: el enunciado dice que la imagen JRE no tiene `curl`, pero en `eclipse-temurin:21-jre` **sí está** (`/usr/bin/curl`), así que `curl -fsS http://localhost:8080/actuator/health` serviría. Solo lo comprobé en esa imagen.

**Qué no hice:** ejecutar el Runner de Postman; el reto 9.4; los tests de Postman cubren solo "Crear pedido" y "Listar pedidos".
