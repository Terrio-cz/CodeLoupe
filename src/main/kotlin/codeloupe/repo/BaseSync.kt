package codeloupe.repo

import kotlinx.coroutines.Deferred

/** A base moving to [commit]; an [inline] sync is small enough for queries to wait for it. */
class BaseSync(val commit: String, val inline: Boolean, val job: Deferred<*>)
