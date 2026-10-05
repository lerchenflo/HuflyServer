package com.lerchenflo.hufly.server.realtime

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import com.lerchenflo.hufly.server.note.model.StableNote
import com.lerchenflo.hufly.server.paddock.model.HorseConflict
import com.lerchenflo.hufly.server.paddock.model.HorseGroup
import com.lerchenflo.hufly.server.paddock.model.Paddock
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.tag.model.Tag
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
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

    override fun onAfterSave(event: AfterSaveEvent<Any>) = publish(event.source)

    /** Also for saves that bypass the repository events, like an atomic `@Update`. */
    fun publish(saved: Any) {
        val (target, collection, id) = when (saved) {
            is UserSettings -> Triple(HintTarget.User(saved.userId), "usersettings", saved.userId)
            is Stable -> Triple(HintTarget.Stable(saved.id), "stable", saved.id)
            is User -> Triple(HintTarget.Stable(saved.stableId), "users", saved.id)
            is Tag -> Triple(HintTarget.Stable(saved.stableId), "tags", saved.id)
            is Horse -> Triple(HintTarget.Stable(saved.stableId), "horses", saved.id)
            is FoodPlan -> Triple(HintTarget.Stable(saved.stableId), "foodplans", saved.id)
            is HorseLogEntry -> Triple(HintTarget.Stable(saved.stableId), "horselog", saved.id)
            is Paddock -> Triple(HintTarget.Stable(saved.stableId), "paddocks", saved.id)
            is HorseGroup -> Triple(HintTarget.Stable(saved.stableId), "horsegroups", saved.id)
            is HorseConflict -> Triple(HintTarget.Stable(saved.stableId), "horseconflicts", saved.id)
            is PaddockAssignment -> Triple(HintTarget.Stable(saved.stableId), "paddockassignments", saved.id)
            is StableTask -> Triple(HintTarget.Stable(saved.stableId), "tasks", saved.id)
            is Event -> Triple(HintTarget.Stable(saved.stableId), "events", saved.id)
            is EventInvitation -> Triple(HintTarget.Stable(saved.stableId), "eventinvitations", saved.id)
            is EventOccurrence -> Triple(HintTarget.Stable(saved.stableId), "eventoccurrences", saved.id)
            is EventOccurrenceAnswer -> Triple(HintTarget.Stable(saved.stableId), "eventoccurrenceanswers", saved.id)
            is TaskOccurrence -> Triple(HintTarget.Stable(saved.stableId), "taskoccurrences", saved.id)
            is StableNote -> Triple(HintTarget.Stable(saved.stableId), "note", saved.id)
            else -> return
        }
        queueOrSend(notifier, target, collection, id)
    }
}
