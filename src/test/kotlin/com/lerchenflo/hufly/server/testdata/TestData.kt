package com.lerchenflo.hufly.server.testdata

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.Tag
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId

val STABLE_ID = ObjectId("66f0000000000000000000a1")
val OTHER_STABLE_ID = ObjectId("66f0000000000000000000a2")

fun testUser(
    id: ObjectId = ObjectId.get(),
    stableId: ObjectId = STABLE_ID,
    email: String = "${id.toHexString()}@hufly.test",
    roleTagIds: List<ObjectId> = emptyList(),
    updatedAt: Long = 0L,
    deleted: Boolean = false,
) = User(
    id = id,
    stableId = stableId,
    email = email,
    displayName = email.substringBefore('@'),
    phoneNumber = null,
    profilePictureUrl = null,
    roleTagIds = roleTagIds,
    createdAt = 0L,
    updatedAt = updatedAt,
    updatedBy = id,
    deleted = deleted,
)

fun testAccount(
    id: ObjectId = ObjectId.get(),
    email: String = "${id.toHexString()}@hufly.test",
    hashedPassword: String = "unused",
    mustChangePassword: Boolean = false,
    createdByStableId: ObjectId? = null,
    deleted: Boolean = false,
) = Account(
    id = id,
    email = email,
    displayName = email.substringBefore('@'),
    hashedPassword = hashedPassword,
    mustChangePassword = mustChangePassword,
    createdByStableId = createdByStableId,
    createdAt = 0L,
    updatedAt = 0L,
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
    createdAt = 0L,
    updatedAt = 0L,
    updatedBy = adminUserId,
)

fun testTag(
    id: ObjectId = ObjectId.get(),
    stableId: ObjectId = STABLE_ID,
    type: TagType = TagType.USER_ROLE,
    permissions: Set<Permission> = emptySet(),
    updatedAt: Long = 0L,
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
    updatedAt: Long = 0L,
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
