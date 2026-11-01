-- Phase 6: badges and the wheel come from the backoffice (journal record 040).

-- Badges as the backoffice published them; empty until it has (then the bundled badges.json applies).
create table badge_definitions
(
    key            text primary key,
    tier           text    not null,
    name_de        text    not null,
    name_en        text    not null,
    description_de text    not null,
    description_en text    not null,
    rule           text    not null,
    position       integer not null,
    active         boolean not null
);

-- The wheel's prizes and odds profiles as the backoffice published them last (one row, JSON).
create table wheel
(
    id         integer primary key default 1 constraint wheel_single check (id = 1),
    definition text not null
);
