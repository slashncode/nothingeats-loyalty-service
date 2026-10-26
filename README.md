# nothingeats-loyalty-service

Loyalty bounded context of [NothingEats](https://github.com/slashncode/nothingeats): XP and levels, daily streaks, badges, a stamp card per restaurant, the wheel, and coupons. Everything is earned by orders of nothing that arrived.

## How rewards work

| What | Rule |
|---|---|
| XP per delivered order | 25, plus 1 per euro not spent (up to 50), plus 5 per streak day beyond the first (up to 30), plus 15 for a restaurant's first order |
| Levels | level 2 at 100 XP, every next level needs 50 XP more than the one before (100, 150, 200, …) |
| Streak | days in a row with an order, counted in Hamburg time; broken after a whole day without one |
| Stamp card | one per restaurant; five deliveries fill it and bring a coupon for one free dish there (up to 15 €) |
| Wheel | one spin per level reached, decided on the server: 10 % or 20 % off, 5 € off, free delivery, a free dish (up to 12 €), 100 bonus XP or the rare golden fork badge |
| Badges | data, not code: [`badges.json`](adapters/src/main/resources/badges.json) (20 badges, rules like `orders`, `streak`, `placedBetween`, `orderTotal`) |
| Coupons | valid 30 days, priced and reserved by ordering over gRPC, redeemed by the `order placed` event |

The wheel's odds come from the PostHog flag `spin-wheel-odds` (payload `{"weights": {"PERCENT_10": 25, …}}`, per variant for an experiment) for customers with analytics consent; everyone else, and every broken payload, gets the defaults.

## API

REST (`/api/loyalty/v1`, signed-in customer, texts in `?lang=de|en` or Accept-Language):

| Method | Path | What |
|---|---|---|
| `GET` | `/me` | Level and XP, spins, streak, totals, every badge (earned first), stamp cards, usable coupons |
| `GET` | `/coupons` | Usable coupons, soonest expiry first |
| `POST` | `/spins` | One turn of the wheel; 409 `NO_SPINS_LEFT` |
| `GET` | `/me/export` | GDPR export |

gRPC (`nothingeats.loyalty.v1.LoyaltyService`, port 9090, the customer's token forwarded): `PriceCoupon` (no side effects) and `RedeemCoupon` (reserves the coupon for one checkout, idempotent per reservation id). Errors carry `COUPON_NOT_FOUND`, `COUPON_USED`, `COUPON_EXPIRED`, `COUPON_IN_USE`, `COUPON_OTHER_RESTAURANT`, `COUPON_MINIMUM_NOT_REACHED`. Contract: [`grpc-api/src/main/proto`](grpc-api/src/main/proto), checked by `buf lint` and `buf breaking`.

Events: consumes `ordering.order.v1` (`placed`, `delivered`) and `identity.user.v1` (`user.deleted`), publishes `loyalty.reward.v1`: see [docs/events.md](docs/events.md).

PostHog server events (`xp_earned` with the level and streak as person properties, `level_reached`, `badge_earned`, `stamp_card_completed`, `wheel_spun`) are sent only for customers who agreed to analytics; the consent travels with the order's events.

## Run

```sh
./gradlew build                       # needs Docker (Testcontainers: Postgres, Kafka)
./gradlew :app:run --args=migrate     # DATABASE__URL, DATABASE__USER, DATABASE__PASSWORD
./gradlew :app:run                    # serve (HTTP 8080, gRPC 9090)
```

## License

MIT
