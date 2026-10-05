package com.lerchenflo.hufly.server.notification.model

enum class PushPlatform { ANDROID, IOS }

/** Shown as is by the system; [data] tells the app what to open (`type` plus ids). */
data class PushMessage(val title: String, val body: String, val data: Map<String, String>)
