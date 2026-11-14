-- Invariantes de la Parte 4 que antes solo vivían en Java. Portable (H2 + PostgreSQL).

-- Un pedido en vuelo (DISPATCHED / IN_TRANSIT) siempre tiene robot asignado.
alter table orders add constraint orders_active_has_robot
    check (status not in ('DISPATCHED', 'IN_TRANSIT') or robot_id is not null);

-- Un pedido recién creado o cancelado no tiene robot (DELIVERED / RETURNED conservan el robot como histórico).
alter table orders add constraint orders_idle_states_have_no_robot
    check (status not in ('CREATED', 'CANCELLED') or robot_id is null);

alter table orders add constraint orders_eta_not_negative
    check (eta_minutes is null or eta_minutes >= 0);

alter table robot add constraint robot_battery_range
    check (battery between 0 and 100);
