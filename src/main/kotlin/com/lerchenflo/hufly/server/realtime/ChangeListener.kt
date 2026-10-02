package com.lerchenflo.hufly.server.realtime

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import com.lerchenflo.hufly.server.paddock.model.HorseConflict
import com.lerchenflo.hufly.server.paddock.model.HorseGroup
import com.lerchenflo.hufly.server.paddock.model.Paddock
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.tag.model.Tag
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.user.model.User
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent
import org.springframework.stereotype.Component

/**
 * Every save of a synced document becomes a change hint, so no feature can forget to notify. The collection names
 * match the sync endpoints. Add new synced documents here.
 */
@Component
class ChangeListener(private val notifier: ChangeNotifier) : AbstractMongoEventListener<Any>() {

    override fun onAfterSave(event: AfterSaveEvent<Any>) {
        when (val saved = event.source) {
            is UserSettings -> notifier.notifyUser(saved.userId, "usersettings")
            is Stable -> notifier.notifyStable(saved.id, "stable")
            is User -> notifier.notifyStable(saved.stableId, "users")
            is Tag -> notifier.notifyStable(saved.stableId, "tags")
            is Horse -> notifier.notifyStable(saved.stableId, "horses")
            is FoodPlan -> notifier.notifyStable(saved.stableId, "foodplans")
            is HorseLogEntry -> notifier.notifyStable(saved.stableId, "horselog")
            is Paddock -> notifier.notifyStable(saved.stableId, "paddocks")
            is HorseGroup -> notifier.notifyStable(saved.stableId, "horsegroups")
            is HorseConflict -> notifier.notifyStable(saved.stableId, "horseconflicts")
            is PaddockAssignment -> notifier.notifyStable(saved.stableId, "paddockassignments")
            is StableTask -> notifier.notifyStable(saved.stableId, "tasks")
            is Event -> notifier.notifyStable(saved.stableId, "events")
            is EventInvitation -> notifier.notifyStable(saved.stableId, "eventinvitations")
        }
    }
}
