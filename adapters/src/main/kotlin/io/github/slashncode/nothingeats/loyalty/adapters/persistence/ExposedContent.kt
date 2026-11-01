package io.github.slashncode.nothingeats.loyalty.adapters.persistence

import io.github.slashncode.nothingeats.loyalty.adapters.badges.RuleJson
import io.github.slashncode.nothingeats.loyalty.adapters.badges.domain
import io.github.slashncode.nothingeats.loyalty.adapters.badges.json
import io.github.slashncode.nothingeats.loyalty.adapters.content.ContentJson
import io.github.slashncode.nothingeats.loyalty.adapters.content.WheelJson
import io.github.slashncode.nothingeats.loyalty.adapters.content.domain
import io.github.slashncode.nothingeats.loyalty.adapters.content.json
import io.github.slashncode.nothingeats.loyalty.application.BadgeDefinitions
import io.github.slashncode.nothingeats.loyalty.application.Wheels
import io.github.slashncode.nothingeats.loyalty.domain.BadgeDefinition
import io.github.slashncode.nothingeats.loyalty.domain.BadgeTier
import io.github.slashncode.nothingeats.loyalty.domain.Text
import io.github.slashncode.nothingeats.loyalty.domain.Wheel
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

internal object BadgeDefinitionsTable : Table("badge_definitions") {
    val key = text("key")
    val tier = text("tier")
    val nameDe = text("name_de")
    val nameEn = text("name_en")
    val descriptionDe = text("description_de")
    val descriptionEn = text("description_en")
    val rule = text("rule")
    val position = integer("position")
    val active = bool("active")

    override val primaryKey = PrimaryKey(key)
}

internal object WheelTable : Table("wheel") {
    val id = integer("id")
    val definition = text("definition")

    override val primaryKey = PrimaryKey(id)
}

/** Badges from the backoffice; [bundled] until it has published any. */
class ExposedBadgeDefinitions(
    private val db: PostgresDatabase,
    private val bundled: List<BadgeDefinition>,
) : BadgeDefinitions {
    override suspend fun all(): List<BadgeDefinition> =
        db
            .transaction(readOnly = true) {
                BadgeDefinitionsTable
                    .selectAll()
                    .orderBy(BadgeDefinitionsTable.position to SortOrder.ASC, BadgeDefinitionsTable.key to SortOrder.ASC)
                    .map {
                        BadgeDefinition(
                            key = it[BadgeDefinitionsTable.key],
                            tier = BadgeTier.valueOf(it[BadgeDefinitionsTable.tier]),
                            name = Text(it[BadgeDefinitionsTable.nameDe], it[BadgeDefinitionsTable.nameEn]),
                            description = Text(it[BadgeDefinitionsTable.descriptionDe], it[BadgeDefinitionsTable.descriptionEn]),
                            rule = ContentJson.decodeFromString(RuleJson.serializer(), it[BadgeDefinitionsTable.rule]).domain(),
                            position = it[BadgeDefinitionsTable.position],
                            active = it[BadgeDefinitionsTable.active],
                        )
                    }
            }.ifEmpty { bundled }

    override suspend fun save(definition: BadgeDefinition) {
        db.transaction {
            BadgeDefinitionsTable.upsert(BadgeDefinitionsTable.key) {
                it[key] = definition.key
                it[tier] = definition.tier.name
                it[nameDe] = definition.name.de
                it[nameEn] = definition.name.en
                it[descriptionDe] = definition.description.de
                it[descriptionEn] = definition.description.en
                it[rule] = ContentJson.encodeToString(RuleJson.serializer(), definition.rule.json())
                it[position] = definition.position
                it[active] = definition.active
            }
        }
    }
}

class ExposedWheels(
    private val db: PostgresDatabase,
) : Wheels {
    override suspend fun current(): Wheel? =
        db.transaction(readOnly = true) {
            WheelTable.selectAll().singleOrNull()?.let {
                ContentJson
                    .decodeFromString(
                        WheelJson.serializer(),
                        it[WheelTable.definition],
                    ).domain()
            }
        }

    override suspend fun save(wheel: Wheel) {
        db.transaction {
            WheelTable.upsert(WheelTable.id) {
                it[id] = 1
                it[definition] = ContentJson.encodeToString(WheelJson.serializer(), wheel.json())
            }
        }
    }
}
