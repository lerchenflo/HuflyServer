package com.lerchenflo.hufly.server.testdata

import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.Tag
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import java.time.Instant

val STABLE_ID = ObjectId("66f0000000000000000000a1")
val OTHER_STABLE_ID = ObjectId("66f0000000000000000000a2")

fun testUser(
    id: ObjectId = ObjectId.get(),
    stableId: ObjectId = STABLE_ID,
    email: String = "${id.toHexString()}@hufly.test",
    hashedPassword: String = "unused",
    roleTagIds: List<ObjectId> = emptyList(),
    updatedAt: Instant = Instant.EPOCH,
    deleted: Boolean = false,
) = User(
    id = id,
    stableId = stableId,
    email = email,
    displayName = email.substringBefore('@'),
    phoneNumber = null,
    profilePictureUrl = null,
    hashedPassword = hashedPassword,
    roleTagIds = roleTagIds,
    createdAt = Instant.EPOCH,
    updatedAt = updatedAt,
    updatedBy = id,
    deleted = deleted,
)

fun testStable(
    id: ObjectId = STABLE_ID,
    adminUserId: ObjectId,
    name: String = "Hof Lerchenfeld",
) = Stable(
    id = id,
    name = name,
    adminUserId = adminUserId,
    subscriptionStatus = SubscriptionStatus.TRIAL,
    subscriptionValidUntil = null,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
    updatedBy = adminUserId,
)

fun testTag(
    id: ObjectId = ObjectId.get(),
    stableId: ObjectId = STABLE_ID,
    type: TagType = TagType.USER_ROLE,
    permissions: Set<Permission> = emptySet(),
    updatedAt: Instant = Instant.EPOCH,
    deleted: Boolean = false,
) = Tag(
    id = id,
    stableId = stableId,
    name = "tag-${id.toHexString()}",
    type = type,
    color = "#888888",
    permissions = permissions,
    updatedAt = updatedAt,
    updatedBy = id,
    deleted = deleted,
)

fun testHorse(
    id: ObjectId = ObjectId.get(),
    stableId: ObjectId = STABLE_ID,
    name: String = "Blitz",
    updatedAt: Instant = Instant.EPOCH,
    deleted: Boolean = false,
) = Horse(
    id = id,
    stableId = stableId,
    name = name,
    description = "",
    pictureUrl = null,
    birthDate = null,
    breed = "",
    color = "",
    ownerUserId = null,
    medicalNotes = "",
    vetContact = "",
    medications = emptyList(),
    foodPlanId = null,
    updatedAt = updatedAt,
    updatedBy = id,
    deleted = deleted,
)
