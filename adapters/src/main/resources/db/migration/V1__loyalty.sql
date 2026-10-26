-- A customer's loyalty account: totals that badges look at, XP (the level follows from it),
-- spins not yet taken and the daily streak (days in shop time, Europe/Berlin).
create table members
(
    user_id               text primary key,
    xp                    bigint  not null default 0,
    spins_available       integer not null default 0 check (spins_available >= 0),
    orders                integer not null default 0,
    money_not_spent_cents bigint  not null default 0,
    calories_avoided      bigint  not null default 0,
    restaurants           integer not null default 0,
    deal_orders           integer not null default 0,
    coupon_orders         integer not null default 0,
    stamp_cards_completed integer not null default 0,
    streak_current        integer not null default 0,
    streak_longest        integer not null default 0,
    streak_last_day       date
);

-- One card per customer and restaurant; five stamps make a free dish.
create table stamp_cards
(
    user_id         text    not null,
    restaurant_id   text    not null,
    restaurant_slug text    not null,
    restaurant_name text    not null,
    stamps          integer not null,
    completed       integer not null,
    primary key (user_id, restaurant_id)
);

create table earned_badges
(
    user_id   text        not null,
    badge     text        not null,
    earned_at timestamptz not null,
    primary key (user_id, badge)
);

-- Coupons from full stamp cards and the wheel. state: ACTIVE, RESERVED (held for one checkout
-- until reserved_until), REDEEMED (used by order_id).
create table coupons
(
    id                  uuid primary key,
    code                text        not null constraint coupons_code_key unique,
    user_id             text        not null,
    kind                text        not null,
    value               bigint      not null,
    max_discount_cents  bigint,
    minimum_order_cents bigint      not null,
    restaurant_id       text,
    restaurant_slug     text,
    restaurant_name     text,
    source              text        not null,
    created_at          timestamptz not null,
    expires_at          timestamptz not null,
    state               text        not null,
    reservation_id      text,
    reserved_until      timestamptz,
    order_id            text,
    redeemed_at         timestamptz
);

create index coupons_user on coupons (user_id);

-- Placed orders, remembered until their delivery is rewarded (awarded_at), then kept as the record
-- of what earned what. Deleted with the customer.
create table orders
(
    order_id        text primary key,
    user_id         text        not null,
    restaurant_id   text        not null,
    restaurant_slug text        not null,
    restaurant_name text        not null,
    total_cents     bigint      not null,
    calories        integer     not null,
    items           integer     not null,
    placed_at       timestamptz not null,
    placed_hour     integer     not null,
    with_deal       boolean     not null,
    coupon_code     text,
    analytics       boolean     not null,
    awarded_at      timestamptz
);

create index orders_user on orders (user_id);
