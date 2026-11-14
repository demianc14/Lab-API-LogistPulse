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
(Pendiente de comprobar en ejecución cuando toque tocar `JerseyConfig` en la Parte 1.)

**Qué no hice:** Modo B (Docker) no se probó en esta parte.
