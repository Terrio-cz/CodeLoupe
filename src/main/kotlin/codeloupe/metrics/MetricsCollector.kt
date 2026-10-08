package codeloupe.metrics

import java.nio.file.Path
import java.time.Instant

/** Reads the transcripts of a period and sums them up into a [MetricsReport]. */
class MetricsCollector(private val categorizer: Categorizer, private val now: () -> Instant = Instant::now) {
    fun collect(projectDirs: List<Path>, label: String, since: Instant?, until: Instant?): MetricsReport {
        val runs = runs(projectDirs, since, until).map(RunSummary::of).toList()
        return MetricsReport(label, since?.toString(), until?.toString(), now().toString(), MetricsReport.WEIGHTS, Aggregator.aggregate(runs), runs)
    }

    /** The runs that started in the period and took at least one turn, read one at a time: a month of transcripts is gigabytes. */
    fun runs(projectDirs: List<Path>, since: Instant?, until: Instant?): Sequence<Run> {
        val reader = TranscriptReader(categorizer)
        return TranscriptFinder(since, until).find(projectDirs).asSequence().map(reader::read).filter { it.turns > 0 && inPeriod(it.start, since, until) }
    }

    // A run is dated by its first line; one without any is kept, as the file already passed the finder's time check.
    private fun inPeriod(start: String?, since: Instant?, until: Instant?): Boolean {
        val at = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        return (since == null || at >= since) && (until == null || at <= until)
    }
}
