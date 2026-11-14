-- Un AMR no puede tener dos pedidos activos a la vez. Índice único PARCIAL: solo PostgreSQL lo soporta
-- (H2, el modo lite, no), por eso esta migración vive en db/migration/postgresql.
create unique index uk_orders_one_active_per_robot
    on orders (robot_id)
    where status in ('DISPATCHED', 'IN_TRANSIT');
