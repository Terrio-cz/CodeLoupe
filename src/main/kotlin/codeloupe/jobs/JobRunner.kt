package codeloupe.jobs

import codeloupe.config.JobsConfig
import codeloupe.events.Event
import codeloupe.events.EventBus
import codeloupe.events.EventTypes
import codeloupe.events.Scrubber
import codeloupe.events.Webhooks
import codeloupe.platform.IsoTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs commands for agents, detached from any agent session: policy check, slot queue, process, compact summary,
 * completion steps, events. Nothing polls: a queued job suspends on its slot, a running one on its process's exit,
 * a waiting client on the next state change.
 */
class JobRunner(
    home: Path,
    config: JobsConfig,
    private val bus: EventBus,
    private val webhooks: Webhooks,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val store = JobStore(home.resolve("jobs.db"))
    private val logs = home.resolve("jobs")
    private val policy = PolicyHook(config.policyHook, config.policyTimeoutMs)
    private val slots = Slots(config.slots)
    private val active = ConcurrentHashMap<String, Active>()
    private val changes = MutableStateFlow(0L)

    @Volatile
    private var stopping = false

    /** A job this daemon still owns. Command and environment live only here, in memory. */
    private class Active(
        @Volatile var record: JobRecord,
        val command: List<String>,
        val env: Map<String, String>,
        val then: List<Action>,
        val onFailure: List<Action>,
        /** Judged by the policy at submission; checked again only if it then had to wait for its slot. */
        val prechecked: Boolean,
    ) {
        @Volatile var process: JobProcess? = null

        @Volatile var cancelled = false

        @Volatile var entered = false
        lateinit var task: Job
        val id: String get() = record.id
    }

    private data class Ended(val status: JobStatus, val exit: Int? = null, val reason: String? = null)

    suspend fun submit(request: JobRequest): Submission {
        val then = request.then.map(ActionParser::parse)
        val onFailure = request.onFailure.map(ActionParser::parse)
        validate(request, then + onFailure)
        val decision = withContext(Dispatchers.IO) { policy.check(CommandLine.withEnv(request.env, request.command), request.cwd) }
        if (!decision.allowed) {
            log("job refused (${decision.verdict.name.lowercase()}): ${Scrubber.text(CommandLine.join(request.command)).take(200)}: ${decision.reason}")
            return Submission.Refused(decision)
        }
        val wake = request.wake?.let(Wake::parse) ?: if (then.isEmpty()) Wake.ALWAYS else Wake.FAILURE
        val job = create(
            request.command, request.cwd, request.env, request.slot, then, onFailure, wake, request.tag,
            parent = null, failureBranch = false, prechecked = true,
        )
        active[job.id] = job
        store.put(job.record)
        launch(job)
        return Submission.Accepted(job.record)
    }

    fun get(id: String): JobRecord? = active[id]?.record ?: store.get(id)

    fun list(limit: Int): List<JobRecord> = store.recent(limit)

    /** [id] and the follow-up jobs its completion steps started, in order. */
    fun chain(id: String): List<JobRecord> = generateSequence(get(id)) { it.nextId?.let(::get) }.toList()

    /** Suspends until the chain of [id] ends or [timeoutMs] passes; the chain as it then is. */
    suspend fun await(id: String, timeoutMs: Long): List<JobRecord> {
        withTimeoutOrNull(timeoutMs) { changes.first { chain(id).lastOrNull()?.status?.terminal != false } }
        return chain(id)
    }

    /** Cancels the live job of [id]'s chain: queued ones never start, a running one ends with all it started. */
    fun cancel(id: String): JobRecord? {
        val live = chain(id).firstOrNull { !it.status.terminal } ?: return get(id)
        val job = active[live.id] ?: return live
        job.cancelled = true
        // A task whose body has not run yet sees the flag when it does (cancelling it then would skip finish());
        // one already inside is woken from its slot queue. Both flags are volatile, so one side always sees the other.
        job.process?.kill() ?: run { if (job.entered) job.task.cancel() }
        return job.record
    }

    fun ahead(job: JobRecord): Int = job.slot?.let { slots.ahead(it, job.id) } ?: 0

    /** Queued and running jobs. */
    fun pending(): Int = active.size

    fun snapshot() = JobsSnapshot(
        running = active.values.count { it.record.status == JobStatus.RUNNING },
        queued = active.values.count { it.record.status == JobStatus.QUEUED },
        policyHook = policy.configured,
        slots = slots.snapshot(),
    )

    /** A previous daemon's queued and running jobs are lost: their processes (if any survived) end, their logs stay. */
    fun recover() {
        for (record in store.unfinished().filter { !active.containsKey(it.id) }) {
            record.pid?.let { pid ->
                ProcessHandle.of(pid)
                    .filter { h -> h.info().startInstant().map { it.toString() == record.pidStart }.orElse(false) }
                    .ifPresent(JobProcess::kill)
            }
            lose(record, "daemon restarted")
        }
    }

    /** The daemon is stopping: what still runs is killed and lost, with its log kept. */
    fun shutdown() {
        stopping = true
        for (job in active.values) {
            job.task.cancel()
            job.process?.kill()
            lose(job.record, "daemon stopped")
        }
        active.clear()
        changed()
    }

    override fun close() = store.close()

    private fun validate(request: JobRequest, actions: List<Action>) {
        require(request.command.isNotEmpty() && request.command.first().isNotBlank()) { "command is empty" }
        val cwd = runCatching { Path.of(request.cwd) }.getOrNull()
        require(cwd != null && cwd.isAbsolute && Files.isDirectory(cwd)) { "cwd must be an existing absolute directory: ${request.cwd}" }
        request.env.keys.firstOrNull { !ENV_NAME.matches(it) }?.let { throw IllegalArgumentException("bad environment variable name: $it") }
        (listOfNotNull(request.slot) + actions.mapNotNull { (it as? Action.RunJob)?.slot })
            .firstOrNull { !SLOT_NAME.matches(it) }?.let { throw IllegalArgumentException("bad slot name: $it") }
        require((request.tag?.length ?: 0) <= MAX_TAG) { "tag longer than $MAX_TAG characters" }
        for (command in listOf(request.command) + actions.mapNotNull { (it as? Action.RunJob)?.command }) {
            JobProcess.problem(command, cwd, request.env)?.let { throw IllegalArgumentException(it) }
        }
        for (action in actions) {
            if (action is Action.Webhook) webhooks.refusal(action.url)?.let { throw IllegalArgumentException("action \"${Scrubber.text(action.spec)}\": $it") }
        }
    }

    private fun create(
        command: List<String>, cwd: String, env: Map<String, String>, slot: String?, then: List<Action>, onFailure: List<Action>,
        wake: Wake, tag: String?, parent: JobRecord?, failureBranch: Boolean, prechecked: Boolean,
    ): Active {
        val id = newId()
        val record = JobRecord(
            id = id, rootId = parent?.rootId ?: id, parentId = parent?.id, tag = tag?.let(Scrubber::text),
            command = Scrubber.text(CommandLine.join(command)).take(MAX_COMMAND), cwd = cwd, envNames = env.keys.sorted(), slot = slot,
            status = JobStatus.QUEUED, createdAt = IsoTime.now(), log = logs.resolve("$id.log").toString(), wakeOn = wake,
            then = then.map { Scrubber.text(it.spec) }, onFailure = onFailure.map { Scrubber.text(it.spec) }, failureBranch = failureBranch,
        )
        return Active(record, command, env, then, onFailure, prechecked).also { job ->
            // Created lazily, so cancel() finds a task even before the job is launched.
            job.task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(job) }
        }
    }

    private fun launch(job: Active) {
        job.task.start()
    }

    private suspend fun execute(job: Active) {
        job.entered = true
        val ended = try {
            val slot = job.record.slot
            when {
                job.cancelled -> Ended(JobStatus.CANCELLED, reason = "cancelled")
                slot == null -> run(job, waited = false)
                else -> slots.withSlot(slot, job.id, onQueued = ::changed) { waited -> run(job, waited) }
            }
        } catch (e: CancellationException) {
            job.process?.kill()
            if (!job.cancelled || stopping) throw e
            Ended(JobStatus.CANCELLED, reason = "cancelled")
        } catch (e: Exception) {
            Ended(JobStatus.ERROR, reason = e.message ?: e.toString())
        }
        withContext(NonCancellable) { finish(job, ended) }
    }

    private suspend fun run(job: Active, waited: Boolean): Ended {
        if (!job.prechecked || waited) {
            val decision = policy.check(CommandLine.withEnv(job.env, job.command), job.record.cwd)
            if (!decision.allowed) return Ended(JobStatus.DENIED, reason = "${decision.verdict.name.lowercase()}: ${decision.reason}")
        }
        if (job.cancelled) return Ended(JobStatus.CANCELLED, reason = "cancelled")
        Files.createDirectories(logs)
        val process = try {
            JobProcess.start(job.command, Path.of(job.record.cwd), job.env, Path.of(job.record.log))
        } catch (e: Exception) {
            return Ended(JobStatus.ERROR, reason = e.message?.substringAfter("error=")?.let { "cannot run ${job.command.first()}: $it" } ?: e.toString())
        }
        job.process = process
        try {
            job.record = job.record.copy(
                status = JobStatus.RUNNING, startedAt = IsoTime.now(), pid = process.process.pid(),
                pidStart = process.process.info().startInstant().map(Instant::toString).orElse(null),
            )
            store.put(job.record)
            log("job ${job.id} started: ${job.record.command.take(200)}")
            bus.emit(EventTypes.JOB_STARTED, JobEvents.started(job.record))
            changed()
            if (job.cancelled) process.kill()
            val exit = process.process.onExit().await().exitValue()
            return if (job.cancelled) Ended(JobStatus.CANCELLED, exit, "cancelled") else Ended(JobStatus.DONE, exit)
        } finally {
            // Whatever ended the wait, no process outlives its record untracked, and the job object is closed.
            if (process.process.isAlive) process.kill()
            process.release()
        }
    }

    /** Records the end, plans the completion steps, emits; the bookkeeping that unblocks waiters always happens. */
    private fun finish(job: Active, ended: Ended) {
        if (stopping) return
        var next: Active? = null
        try {
            val record = ended(job.record, ended)
            val plan = StepPlan.of(if (record.ok) job.then else job.onFailure, record, startJobs = record.status != JobStatus.CANCELLED)
            next = plan.next?.let { step ->
                create(
                    step.command, record.cwd, job.env, step.slot,
                    then = plan.rest, onFailure = if (record.ok) job.onFailure else plan.rest,
                    wake = record.wakeOn, tag = job.record.tag, parent = record,
                    failureBranch = record.failureBranch || !record.ok, prechecked = false,
                )
            }
            next?.let {
                active[it.id] = it
                store.put(it.record)
            }
            job.record = record.copy(nextId = next?.id)
            store.put(job.record)
            log("job ${job.id} ${JobReport.line(job.record).substringAfter(' ').take(200)}")
            val finished = runCatching { bus.emit(EventTypes.JOB_FINISHED, JobEvents.finished(job.record, next == null, chainUpTo(job.record))) }
                .onFailure { log("job ${job.id}: job.finished not emitted: ${it.message}") }
                .getOrNull()
            plan.now.forEach { act(it, job.record, finished) }
        } catch (e: Exception) {
            log("job ${job.id}: finishing failed: ${e.message}")
        } finally {
            active.remove(job.id)
            next?.let(::launch)
            changed()
        }
    }

    private fun ended(record: JobRecord, ended: Ended): JobRecord {
        val started = record.startedAt
        val summary = started?.let { runCatching { scrub(SummaryReader.read(Path.of(record.log))) }.getOrNull() }
        return record.copy(
            status = ended.status, exit = ended.exit, reason = ended.reason?.let(Scrubber::text), endedAt = IsoTime.now(),
            durationMs = started?.let { Duration.between(Instant.parse(it), Instant.now()).toMillis() }, summary = summary,
        )
    }

    private fun act(action: Action, record: JobRecord, finished: Event?) {
        try {
            when (action) {
                is Action.Notify -> bus.emit(EventTypes.JOB_NOTIFY, JobEvents.notify(record, action.message, chainUpTo(record)))
                is Action.Webhook -> webhooks.send(action.url, finished ?: return)
                is Action.RunJob -> error("job steps start a follow-up job")
            }
        } catch (e: Exception) {
            log("job ${record.id}: action \"${Scrubber.text(action.spec)}\" failed: ${e.message}")
        }
    }

    private fun lose(record: JobRecord, reason: String) {
        val lost = ended(record, Ended(JobStatus.LOST, reason = reason))
        store.put(lost)
        log("job ${record.id} lost: $reason")
        runCatching { bus.emit(EventTypes.JOB_FINISHED, JobEvents.finished(lost, final = true, chainUpTo(lost))) }
    }

    private fun chainUpTo(record: JobRecord): List<JobRecord> {
        val chain = chain(record.rootId).map { if (it.id == record.id) record else it }
        return chain.take(chain.indexOfFirst { it.id == record.id } + 1).ifEmpty { listOf(record) }
    }

    private fun scrub(summary: JobSummary) = summary.copy(failures = summary.failures.map(Scrubber::text), tail = summary.tail.map(Scrubber::text))

    private fun changed() = changes.update { it + 1 }

    private fun newId(): String {
        while (true) {
            val id = "J" + ID_TIME.format(Instant.now()) + "-" + (1..4).map { ID_CHARS.random() }.joinToString("")
            if (active[id] == null && store.get(id) == null) return id
        }
    }

    private companion object {
        val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
        val SLOT_NAME = Regex("[A-Za-z0-9._:-]{1,64}")
        val ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        const val ID_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"
        const val MAX_TAG = 200
        const val MAX_COMMAND = 1000
    }
}
