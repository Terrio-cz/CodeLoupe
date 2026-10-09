package codeloupe.reconcile

/** A confirm was built on a plan that is no longer the daemon's; [current] is the plan to show the person again. Nothing was removed. */
class StalePlan(val current: ReconcilePlan) : RuntimeException("the cleanup plan changed since it was shown")
