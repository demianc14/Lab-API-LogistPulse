# ADR 0001 — Migraciones con Flyway y restricciones de integridad en la base de datos

- **Estado:** aceptada
- **Contexto del curso:** proyecto de cierre del laboratorio LOGISTPULSE (opción "Flyway en lugar de `ddl-auto=update`").

## Contexto

Hasta ahora el esquema lo creaba Hibernate con `spring.jpa.hibernate.ddl-auto=update`. Eso tiene tres problemas que aparecieron durante el laboratorio:

1. **El esquema no está versionado ni se puede revisar**: no hay historial de cambios y `update` nunca modifica ni borra columnas ni restricciones existentes
   (p. ej. un `CHECK` de `status` creado antes de existir `RETURNED` no se habría actualizado en una BD ya existente).
2. **Las invariantes de negocio vivían solo en Java** (Parte 4). Con la BD ya en marcha comprobé que aceptaba: dos pedidos activos con el mismo AMR, un pedido `DISPATCHED` sin robot,
   un pedido `CREATED` con robot y una batería de 150 %. Solo rechazaba valores de `status` inexistentes.
3. Hibernate genera DDL distinto en H2 (modo lite) y en PostgreSQL (modo docker), sin que nadie lo revise.

## Decisión

- Flyway gestiona el esquema desde `src/main/resources/db/migration`; Hibernate solo lo **valida** (`ddl-auto=validate`).
- Las migraciones se separan en `common/` (SQL portable para H2 y PostgreSQL) y `postgresql/` (SQL que H2 no soporta). `spring.flyway.locations` usa el marcador `{vendor}` para elegir la carpeta.
  - `V1__baseline.sql`: esquema equivalente al que generaba Hibernate.
  - `V2__integrity_constraints.sql` (común): `orders_active_has_robot` (un pedido `DISPATCHED`/`IN_TRANSIT` tiene robot), `orders_idle_states_have_no_robot`
    (`CREATED`/`CANCELLED` no tienen robot; `DELIVERED`/`RETURNED` lo conservan como histórico), `orders_eta_not_negative`, `robot_battery_range` (0–100).
  - `V3__one_active_order_per_robot.sql` (solo PostgreSQL): índice **único parcial** sobre `orders(robot_id) where status in ('DISPATCHED','IN_TRANSIT')`.
- `spring.flyway.baseline-on-migrate=true` con `baseline-version=1`, para adoptar bases ya creadas por `ddl-auto=update`: se asume que equivalen a `V1` y se aplican `V2` en adelante.
- La API traduce una violación de **unicidad** (SQLState `23505`) a `409 Conflicto de concurrencia` (`IntegrityViolationMapper`), igual que el `@Version`; cualquier otra violación (`CHECK`, `NOT NULL`, FK) es `500`, porque indica un fallo del programa.

## Qué invariantes quedan garantizadas por la BD y cuáles no

| Invariante (tabla de la Parte 4) | Tras este ADR |
|---|---|
| Un AMR no tiene dos pedidos activos | **PostgreSQL: sí** (índice parcial). **H2: no** (no soporta índices parciales; sigue dependiendo de `@Version`) |
| Un pedido activo tiene robot; `CREATED`/`CANCELLED` no | **Sí**, en ambos (`CHECK`) |
| Batería entre 0 y 100; ETA no negativo | **Sí**, en ambos (`CHECK`) |
| Valores válidos de `status` | Sí (ya lo estaba; ahora definido en `V1`) |
| Un AMR `BUSY` tiene exactamente un pedido activo | **No**: cruza dos tablas (`robot.status` y `orders`); un `CHECK` no puede verlo. Solo el índice garantiza el "como máximo uno" |
| Transiciones válidas (`CREATED → DISPATCHED → …`) | **No**: exigiría un *trigger* o una tabla de transiciones; sigue en `OrderStatus` |
| Despachar exige batería ≥ 20 % | **No**: es una regla de negocio del momento del despacho, no un estado |

## Alternativas consideradas

- **Liquibase**: equivalente en función; Flyway se eligió por usar SQL plano, más fácil de revisar en un curso de bases de datos y de defender.
- **Mantener `ddl-auto=update` y añadir las restricciones con `@Check`/`@Table`**: no versiona y no puede expresar el índice parcial.
- **Triggers** para las transiciones y la regla `BUSY ⇔ pedido activo`: descartado por complejidad y por no ser portable a H2.

## Consecuencias

- (+) El esquema y las reglas de integridad son revisables y versionados; la BD frena datos incoherentes aunque el código tenga un bug o alguien use SQL directo.
- (+) Se comprobó el camino de adopción (BD existente → *baseline* + `V2`/`V3`), el de BD vacía (`V1`–`V3`) y el de H2 (`V1`–`V2`).
- (−) **El modo lite (H2) es más débil que PostgreSQL**: no tiene el índice parcial. Las dos bases ya no tienen exactamente las mismas garantías.
- (−) Cambiar el esquema pasa a exigir escribir una migración nueva; **nunca se edita una migración ya aplicada** (Flyway comprueba su *checksum*).
- (−) `baseline-on-migrate` confía en que la BD existente sea equivalente a `V1`; no lo verifica (Hibernate `validate` detecta solo parte de las diferencias).
- (−) Una migración con restricciones falla si los datos existentes las incumplen: hay que revisar antes (aquí se comprobó con consultas previas que no había filas incoherentes).
- (−) Flyway avisa de que H2 2.4.240 es más nuevo que lo que tiene verificado; funciona, pero es una advertencia en el log.
- Nota: al activar el índice, dos despachos concurrentes de **pedidos distintos** pasaron a chocar con él antes que con el `@Version` de `Robot`, y el perdedor recibía `500`;
  por eso existe `IntegrityViolationMapper` (el `409` anterior no se perdió: se restituyó).
