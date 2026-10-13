package codeloupe.jobs

import codeloupe.config.JobsConfig
import codeloupe.config.SlotConfig
import codeloupe.events.Event
import codeloupe.events.EventBus
import codeloupe.events.EventTypes
import codeloupe.events.Scrubber
import codeloupe.events.Webhooks
import codeloupe.platform.ExitCodes
import codeloupe.platform.IsoTime
import codeloupe.secrets.KeyProtector
import codeloupe.secrets.KeyProtectors
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs commands for agents, detached from any agent session: policy check, slot queue, process, compact summary,
 * completion steps, events. Nothing polls: a queued job suspends on its slot, a running one on its process's exit,
 * a waiting client on the next state change.
 *
 * A restart keeps the queue: each job's launch ([JobSpec]) is stored sealed while it is queued or running, so the next daemon
 * queues the waiting jobs again in their order and takes back a running job whose process survived.
 */
class JobRunner(
    private val home: Path,
    config: JobsConfig,
    private val bus: EventBus,
    private val webhooks: Webhooks,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    keyProtector: (wrapped: String?) -> KeyProtector = { KeyProtectors.choose(System.getenv(), it) },
) : AutoCloseable {
    private val store = JobStore(home.resolve("jobs.db"))
    private val logs = home.resolve("jobs")
    private val policy = PolicyHook(config.policyHook, config.policyTimeoutMs)
    private val slots = Slots().apply { configure(config.slots, config.invalidSlots) }
    private val configWatch = SlotConfigWatch(home.resolve("config.json"), ::reloadSlots)
    private val specKey = JobSpecKey(home.resolve("jobs.key"), keyProtector)
    private val seq = AtomicLong(store.specs().maxOfOrNull { it.seq } ?: 0)
    private val warnedNoKey = AtomicBoolean(false)
    private val active = ConcurrentHashMap<String, Active>()
    private val changes = MutableStateFlow(0L)

    @Volatile
    private var stopping = false

    /** A job this daemon still owns. Command and environment live here in memory, and sealed in the store while it lives. */
    private class Active(
        @Volatile var record: JobRecord,
        val command: List<String>,
        val env: Map<String, String>,
        val then: List<Action>,
        val onFailure: List<Action>,
        /** Judged by the policy at submission; checked again only if it then had to wait for its slot. */
        val prechecked: Boolean,
        /** Its place among the queued jobs, kept across a restart. */
        @Volatile var seq: Long,
    ) {
        @Volatile var process: JobProcess? = null

        /** The process of a job a previous daemon started, found still running. */
        @Volatile var adopted: ProcessHandle? = null
        var exitWatch: ExitCodes.Watch? = null

        @Volatile var ticket: Slots.Ticket? = null

        /** Its [JobSpec] is in the store: a restart does not lose it. */
        @Volatile var stored = false

        @Volatile var cancelled = false

        @Volatile var entered = false
        lateinit var task: Job
        val id: String get() = record.id

        /** Ends its process tree, whoever started it; false when it has none. */
        fun kill(): Boolean {
            process?.let { it.kill(); return true }
            adopted?.let { JobProcess.kill(it); return true }
            return false
        }
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
        withContext(Dispatchers.IO) { launch(job) }
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
        if (!job.kill() && job.entered) job.task.cancel()
        return job.record
    }

    /**
     * Moves the queued job [id] within its slot's queue; the slot and its new order. Running jobs never move: [IllegalArgumentException]
     * for one, for an ended job, or for a [position] naming a job that does not wait in the same slot; [NoSuchElementException] for an unknown id.
     */
    fun move(id: String, position: QueuePosition): Pair<String, List<String>> {
        val record = get(id) ?: throw NoSuchElementException("no such job: $id")
        when (record.status) {
            JobStatus.QUEUED -> Unit
            JobStatus.RUNNING -> throw IllegalArgumentException("$id is running: only waiting jobs move")
            else -> throw IllegalArgumentException("$id has ended (${record.status.label}): only waiting jobs move")
        }
        val (slot, order) = slots.move(id, position)
        // The queue's jobs keep the places they had among them, handed out in the new order: what a restart reads back.
        val jobs = order.mapNotNull { active[it] }
        jobs.zip(jobs.map { it.seq }.sorted()).forEach { (job, place) -> job.seq = place }
        runCatching { store.reorder(jobs.filter { it.stored }.associate { it.id to it.seq }) }
            .onFailure { log("jobs: the new order of slot $slot was not stored: ${it.message}") }
        log("job $id moved in slot $slot: ${order.joinToString(" ")}")
        runCatching { bus.emit(EventTypes.JOB_REORDERED, JobEvents.reordered(id, slot, order)) }
        changed()
        return slot to order
    }

    /** Sets the capacity of slot [name] in `config.json` and at once: a raise starts waiting jobs, a cut ends nobody. */
    fun setCapacity(name: String, capacity: Int): SlotSnapshot {
        require(SLOT_NAME.matches(name)) { "bad slot name: $name" }
        require(capacity in 1..MAX_CAPACITY) { "capacity must be 1 to $MAX_CAPACITY" }
        SlotConfig.set(home, name, capacity)
        configWatch.check(force = true)
        return slots.snapshot().first { it.name == name }
    }

    fun slots(): List<SlotSnapshot> = slots.snapshot()

    fun ahead(job: JobRecord): Int = job.slot?.let { slots.ahead(it, job.id) } ?: 0

    /** Jobs a stop would lose: the running ones, and queued ones whose launch could not be stored. */
    fun atRisk(): Int = active.values.count { it.record.status == JobStatus.RUNNING || !it.stored }

    fun snapshot() = JobsSnapshot(
        running = active.values.count { it.record.status == JobStatus.RUNNING },
        queued = active.values.count { it.record.status == JobStatus.QUEUED },
        policyHook = policy.configured,
        slots = slots.snapshot(),
    )

    /**
     * Takes over what a previous daemon left: a running job whose process survived is watched to its end, one whose process is gone
     * is lost (its log stays); queued jobs are queued again in their order. A queued job without a readable launch is lost as well.
     * From now on a change of `config.json` `slots` applies at once.
     */
    fun recover() {
        checkHistory()
        val specs = store.specs().associateBy { it.id }
        val unfinished = store.unfinished().filter { !active.containsKey(it.id) }
        val ids = unfinished.mapTo(HashSet()) { it.id }
        specs.keys.filter { it !in ids }.forEach(store::deleteSpec)
        // Survivors first: they hold their slots before any queued job is handed a place.
        unfinished.filter { it.status == JobStatus.RUNNING }.forEach { adopt(it, specs[it.id]) }
        unfinished.filter { it.status == JobStatus.QUEUED }
            .sortedBy { specs[it.id]?.seq ?: Long.MAX_VALUE }
            .mapNotNull { requeue(it, specs[it.id]) }
            .forEach(::launch)
        configWatch.start(scope)
        changed()
    }

    /**
     * Says in the log how many records the daemon found. Logs of jobs are removed together with their records, so more logs than records
     * means the records were removed from outside (the file replaced or deleted while the daemon was down).
     */
    private fun checkHistory() {
        val records = store.count()
        val logFiles = runCatching { Files.list(logs).use { files -> files.filter { it.fileName.toString().endsWith(".log") }.count() } }.getOrDefault(0L)
        log("jobs: $records records kept")
        if (logFiles > records) log("jobs: $logFiles job logs lie in $logs but jobs.db holds $records records: the others were removed outside the daemon")
    }

    /** The daemon is stopping: what runs is killed and lost, with its log kept; queued jobs stay queued for the next daemon. */
    fun shutdown() {
        stopping = true
        for (job in active.values) {
            job.task.cancel()
            if (job.record.status == JobStatus.QUEUED && job.stored) continue
            job.kill()
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
        return active(record, command, env, then, onFailure, prechecked, place = 0)
    }

    private fun active(
        record: JobRecord, command: List<String>, env: Map<String, String>, then: List<Action>, onFailure: List<Action>, prechecked: Boolean, place: Long,
    ) = Active(record, command, env, then, onFailure, prechecked, place).also { job ->
        // Created lazily, so cancel() finds a task even before the job is launched.
        job.task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(job) }
    }

    /** Stores the job's launch sealed; without a key it lives only in memory and a restart loses the job, as it always did. */
    private fun persist(job: Active) {
        val spec = JobSpec(job.command, job.env, job.then.map { it.spec }, job.onFailure.map { it.spec })
        val sealed = runCatching { specKey.seal(job.id, spec) }.getOrNull()
        if (sealed == null) {
            if (warnedNoKey.compareAndSet(false, true)) log("jobs: queued jobs will not outlive a restart, their launch cannot be sealed: ${specKey.problem}")
            return
        }
        runCatching { store.putSpec(job.id, job.seq, sealed) }
            .onSuccess { job.stored = true }
            .onFailure { log("job ${job.id}: launch not stored, a restart would lose it: ${it.message}") }
    }

    /**
     * Takes its place in its slot's queue now, so the queue has the order of the launches, and stores its launch under that place
     * (a job queued again after a restart keeps the place it had).
     */
    private fun launch(job: Active) {
        synchronized(slots) {
            if (job.seq == 0L) job.seq = seq.incrementAndGet()
            job.record.slot?.let { job.ticket = slots.enter(it, job.id, Instant.parse(job.record.createdAt)) }
        }
        if (!job.stored) persist(job)
        job.task.start()
    }

    /** A job a previous daemon ran: watched on if its process is still the one it started, else lost. */
    private fun adopt(record: JobRecord, row: JobStore.SpecRow?) {
        val handle = record.pid?.let { pid ->
            ProcessHandle.of(pid).filter { h -> h.isAlive && h.info().startInstant().map { it.toString() == record.pidStart }.orElse(false) }.orElse(null)
        }
        if (handle == null) {
            lose(record, "daemon restarted")
            return
        }
        // Without a readable launch the steps come from the record, scrubbed as they are, and follow-up jobs get no extra environment.
        val spec = row?.let { specKey.open(record.id, it.sealed) }
        val then = actions(spec?.then ?: record.then)
        val onFailure = actions(spec?.onFailure ?: record.onFailure)
        val job = active(record, spec?.command.orEmpty(), spec?.env.orEmpty(), then, onFailure, prechecked = true, place = row?.seq ?: seq.incrementAndGet())
        job.adopted = handle
        job.exitWatch = ExitCodes.watch(handle.pid())
        job.stored = row != null
        record.slot?.let { slots.adopt(it, record.id, record.startedAt?.let(Instant::parse) ?: Instant.now()) }
        active[job.id] = job
        job.task.start()
        log("job ${record.id} taken over: its process ${handle.pid()} survived the restart")
    }

    /** A job a previous daemon left queued, ready to be launched again; null when it is lost. */
    private fun requeue(record: JobRecord, row: JobStore.SpecRow?): Active? {
        val spec = row?.let { specKey.open(record.id, it.sealed) }
        val then = spec?.let { runCatching { it.then.map(ActionParser::parse) }.getOrNull() }
        val onFailure = spec?.let { runCatching { it.onFailure.map(ActionParser::parse) }.getOrNull() }
        if (row == null || spec == null || then == null || onFailure == null || spec.command.isEmpty()) {
            lose(record, if (row == null) "daemon restarted" else "daemon restarted, and its stored launch could not be read" + specKey.problem?.let { ": $it" }.orEmpty())
            return null
        }
        // Judged again before it starts: the policy may have changed with the daemon.
        val job = active(record, spec.command, spec.env, then, onFailure, prechecked = false, place = row.seq)
        job.stored = true
        active[job.id] = job
        log("job ${record.id} queued again after the restart")
        return job
    }

    private fun actions(specs: List<String>): List<Action> = specs.mapNotNull { runCatching { ActionParser.parse(it) }.getOrNull() }

    private fun reloadSlots(config: JobsConfig) {
        val before = slots.configured()
        slots.configure(config.slots, config.invalidSlots)
        if (config.slots == before) return
        log("jobs: slot capacities now ${config.slots.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "1 each (none configured)" }}")
        changed()
    }

    private suspend fun execute(job: Active) {
        job.entered = true
        val ended = try {
            val ticket = job.ticket
            when {
                job.adopted != null -> watch(job)
                job.cancelled -> {
                    ticket?.let(slots::leave)
                    Ended(JobStatus.CANCELLED, reason = "cancelled")
                }
                ticket == null -> run(job, waited = false)
                else -> slots.hold(ticket, onQueued = ::changed) { waited -> run(job, waited) }
            }
        } catch (e: CancellationException) {
            job.kill()
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
        // A stopping daemon starts nothing: the job stays queued for the next one.
        if (stopping) throw CancellationException("daemon stopping")
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

    /** Waits for the end of a process a previous daemon started; its exit code where the OS still tells it. */
    private suspend fun watch(job: Active): Ended {
        val handle = job.adopted ?: error("not an adopted job")
        try {
            handle.onExit().await()
            val exit = job.exitWatch?.exitCode()
            return when {
                job.cancelled -> Ended(JobStatus.CANCELLED, exit, "cancelled")
                exit != null -> Ended(JobStatus.DONE, exit)
                else -> Ended(JobStatus.LOST, reason = "ended after a daemon restart; its exit code cannot be read here")
            }
        } finally {
            job.exitWatch?.close()
            job.record.slot?.let { slots.release(it, job.id) }
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
            if (job.stored) runCatching { store.deleteSpec(job.id) }
            next?.let(::launch)
            changed()
        }
    }

    private fun ended(record: JobRecord, ended: Ended): JobRecord {
        val started = record.startedAt
        // The log is what an agent may read afterwards: stored secret values leave it before anything summarises it.
        runCatching { LogMask.apply(Path.of(record.log), Scrubber.knownValues()) }.onFailure { log("job ${record.id}: log not masked: ${it.message}") }
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
        runCatching { store.deleteSpec(record.id) }
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
        const val MAX_CAPACITY = 64
    }
}
