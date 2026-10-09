package codeloupe.reconcile

import codeloupe.docker.DockerApi
import codeloupe.docker.DockerObject
import codeloupe.docker.DockerUnavailable
import codeloupe.docker.OwnershipClass
import codeloupe.docker.Ownership
import codeloupe.processes.ProcessStopper
import java.io.IOException
import java.nio.file.Path

/**
 * Removes one planned target. It trusts nothing but the entry it is given, which comes from a fresh plan, and never
 * forces: a resource that is in use stays and the attempt is reported as blocked.
 */
class ReconcileExecutor(
    private val docker: () -> DockerApi,
    private val stopper: ProcessStopper = ProcessStopper(),
    private val removeDirectory: (Path) -> Unit = DirectoryRemover::remove,
) {
    fun execute(entry: PlanEntry): ActionResult {
        fun result(outcome: ActionOutcome, detail: String = "") = ActionResult(entry.key, entry.kind, entry.name, entry.workspace, outcome, detail)
        return try {
            when (entry.kind) {
                TargetKind.PROCESS -> process(entry, ::result)
                TargetKind.CONTAINER -> container(entry, ::result)
                TargetKind.NETWORK -> outcome(docker().removeNetwork(entry.key.substringAfter(':')), ::result)
                TargetKind.VOLUME -> outcome(docker().removeVolume(entry.key.substringAfter(':')), ::result)
                TargetKind.IMAGE -> outcome(docker().removeImage(entry.key.substringAfter(':')), ::result)
                TargetKind.DIRECTORY -> directory(entry.key.substringAfter(':'), ::result)
            }
        } catch (e: DockerUnavailable) {
            result(ActionOutcome.FAILED, e.message.orEmpty())
        }
    }

    // The plan is seconds old: a stopped container that somebody started since (compose reuses the id) is not ours to stop.
    private fun container(entry: PlanEntry, result: (ActionOutcome, String) -> ActionResult): ActionResult {
        val id = entry.key.substringAfter(':')
        val api = docker()
        val current = api.container(id) ?: return result(ActionOutcome.GONE, "")
        changedSincePlan(entry, current)?.let { return result(ActionOutcome.BLOCKED, it) }
        api.stopContainer(id)
        return outcome(api.removeContainer(id), result)
    }

    private fun changedSincePlan(entry: PlanEntry, current: DockerObject): String? {
        if (current.state in ReconcilePlanner.RUNNING && !entry.running) return "it was started again after the plan was made"
        if (entry.ownership != OwnershipClass.OWNED) return null
        val owner = Ownership.of(current.labels)
        val same = owner != null && owner.workspace.equals(entry.workspace, ignoreCase = true) && owner.repo.equals(entry.repo.orEmpty(), ignoreCase = true)
        return if (same) null else "its labels no longer name ${entry.workspace}"
    }

    private fun outcome(removal: DockerApi.Removal, result: (ActionOutcome, String) -> ActionResult): ActionResult = when (removal) {
        DockerApi.Removal.REMOVED -> result(ActionOutcome.REMOVED, "")
        DockerApi.Removal.GONE -> result(ActionOutcome.GONE, "")
        is DockerApi.Removal.Conflict -> result(ActionOutcome.BLOCKED, removal.message)
    }

    // The key is `process:<pid>:<start>`; the stopper checks the process against the workspace directory again.
    private fun process(entry: PlanEntry, result: (ActionOutcome, String) -> ActionResult): ActionResult {
        val parts = entry.key.split(':')
        val pid = parts.getOrNull(1)?.toLongOrNull()
        val start = parts.getOrNull(2)?.toLongOrNull()
        val path = entry.path
        if (pid == null || start == null || path == null) return result(ActionOutcome.FAILED, "malformed process target ${entry.key}")
        return when (val stopped = stopper.stop(pid, start, path)) {
            ProcessStopper.Outcome.Stopped -> result(ActionOutcome.REMOVED, "")
            ProcessStopper.Outcome.Gone -> result(ActionOutcome.GONE, "")
            is ProcessStopper.Outcome.Blocked -> result(ActionOutcome.BLOCKED, stopped.reason)
        }
    }

    private fun directory(path: String, result: (ActionOutcome, String) -> ActionResult): ActionResult = try {
        removeDirectory(Path.of(path))
        result(ActionOutcome.REMOVED, "")
    } catch (e: IOException) {
        // On Windows a process that still holds a file keeps the directory; it is retried until the lock is gone.
        result(ActionOutcome.BLOCKED, "${e::class.simpleName}: ${e.message.orEmpty().lineSequence().first()}")
    }
}
