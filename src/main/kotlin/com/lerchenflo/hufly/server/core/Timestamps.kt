package com.lerchenflo.hufly.server.core

/** Upper bound for epoch-millisecond request fields (3000-01-01T00:00:00Z); larger values would overflow Instant. */
const val MAX_EPOCH_MILLIS = 32503680000000L
