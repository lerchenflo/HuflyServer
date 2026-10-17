package com.lerchenflo.hufly.server.horse.model

import org.bson.types.ObjectId

enum class HorseNoteArea { GENERAL, FEEDING, PADDOCK, RIDING }

/** A note on a horse; [id] is the client's own (a UUID), kept as given. The area decides who may write it. */
data class HorseNote(val id: String, val area: HorseNoteArea, val text: String)

/** Checked by the app while planning, never by the server; an empty [allowedPaddockIds] allows every paddock. */
data class PaddockRules(
    val allowedPaddockIds: List<ObjectId>,
    val neverAloneInStable: Boolean,
    val neverAloneOnPaddock: Boolean,
) {
    /** Null when nothing is left to keep. */
    fun orNullWhenEmpty(): PaddockRules? = takeIf { allowedPaddockIds.isNotEmpty() || neverAloneInStable || neverAloneOnPaddock }
}

data class PaddockRulesResponse(val allowedPaddockIds: List<String>, val neverAloneInStable: Boolean, val neverAloneOnPaddock: Boolean)

fun PaddockRules.toPaddockRulesResponse() =
    PaddockRulesResponse(allowedPaddockIds.map { it.toHexString() }, neverAloneInStable, neverAloneOnPaddock)
