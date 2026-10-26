# CLAUDE.md

Loyalty service of NothingEats (XP, levels, streaks, badges, stamp cards, the wheel, coupons). Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker for Testcontainers (Postgres, Kafka).
- `./gradlew ktlintFormat` — auto-format
- `./gradlew :app:run --args=migrate` / `./gradlew :app:run` — needs `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`
- `./gradlew :app:jibDockerBuild -Djib.from.platforms=linux/arm64` — local image

## Rules

- Respect the module direction: `grpc-api`, `domain` ← `application` ← `adapters` ← `app`. Never add a framework dependency to `domain`.
- Reward rules live in the domain (`Rewards`, `Levels`, `Streak`, `StampCard`, `Prize`); badge definitions are data in `adapters/src/main/resources/badges.json`. A new badge rule type needs a `BadgeRule` and its JSON form in `BadgeCatalog`.
- The wheel is decided on the server only; odds from the PostHog flag `spin-wheel-odds`, never from the client.
- Server-side analytics only with consent: events from consumers carry `analytics`; HTTP requests have it when the browser sent its distinct id (`AnalyticsContext.consented()`).
- Wiring happens only in `app/.../ServiceComponent.kt`. Lifecycle components, health checks, routes, gRPC services, event handlers and cron tasks are contributed with `@IntoSet`.
- Expected failures are `AppError` subtypes with an `UPPER_SNAKE` code, thrown from use cases.
- Schema changes are new Flyway files in `adapters/src/main/resources/db/migration`; never edit an applied migration.
- The gRPC contract (`grpc-api/src/main/proto`) and the event contract (`docs/events.md`) change only compatibly; `buf breaking` guards the first.
