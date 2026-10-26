# Events of the loyalty service

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/loyalty`,
record key and `subject` = the user id (one customer's rewards arrive in order). Consumers must ignore unknown fields.

## `loyalty.reward.v1` (outbox, published with the award)

| `type` | When | `data` |
|---|---|---|
| `nothingeats.loyalty.reward.earned.v1` | A delivered order was rewarded (`cause` `ORDER_DELIVERED`), or the wheel gave bonus XP or a badge (`WHEEL`) | `userId`, `cause`, `orderId` (null for the wheel), `xp` (`gained`, `total`, `level`, `previousLevel`, `levelStartXp`, `nextLevelXp`), `spinsGranted`, `spinsAvailable`, `streakDays`, `badges` (`key`, `tier`, `name` `{de, en}`), `stampCard` (`restaurantId`, `restaurantSlug`, `restaurantName`, `stamps`, `perCard`, `completed`; null for the wheel), `coupons` (`code`, `kind`, `value`, `maxDiscountCents`, `minimumOrderCents`, `restaurantSlug`, `restaurantName`, `expiresAt`), `analytics` |

`analytics` is the customer's analytics consent at the time of the order (or the spin): consumers send
server-side analytics for this reward only when it is `true`.

## Consumed

- `ordering.order.v1`: `placed` remembers the order (fields read: `orderId`, `userId`, `placedAt`, `restaurant.id/slug/name`, `lines[].quantity/unitPriceCents/regularUnitPriceCents`, `totalCents`, `calories`, `coupon.code`, `analytics`) and redeems its coupon; `delivered` (`orderId`) rewards it.
- `identity.user.v1`: `user.deleted` erases the account, stamp cards, badges, coupons and remembered orders.
