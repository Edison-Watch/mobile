package ai.sealgate.stdiod

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.sealgate.stdiod.databinding.ActivityExecutionLogBinding
import ai.sealgate.stdiod.databinding.ItemExecutionLogBinding
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class ExecutionLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExecutionLogBinding
    private val expandedIds = mutableSetOf<Long>()
    private val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    private var showAll = false
    private var entries: List<ExecutionLogEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExecutionLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        binding.backButton.setOnClickListener { finish() }
        binding.showMoreButton.setOnClickListener {
            showAll = true
            render()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ExecutionLogStore.entries.collect { latest ->
                    entries = latest
                    render()
                }
            }
        }
    }

    private fun render() {
        binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val shownEntries = if (showAll) entries else entries.take(DEFAULT_VISIBLE_ENTRIES)
        binding.logList.removeAllViews()
        shownEntries.forEach { entry -> addEntry(entry) }
        binding.showMoreButton.visibility =
            if (!showAll && entries.size > DEFAULT_VISIBLE_ENTRIES) View.VISIBLE else View.GONE
    }

    private fun addEntry(entry: ExecutionLogEntry) {
        val row = ItemExecutionLogBinding.inflate(
            LayoutInflater.from(this),
            binding.logList,
            false,
        )
        val expanded = entry.id in expandedIds
        val meta = entry.metaLine()
        row.headlineText.text = entry.headline
        row.metaText.text = meta
        row.statusIndicator.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                when {
                    entry.isRunning -> R.color.signal_amber
                    entry.exitCode == 0 -> R.color.circuit_green
                    else -> R.color.infra_red
                },
            ),
        )
        row.expandIndicator.text = if (expanded) "⌃" else "⌄"
        row.detailPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        row.commandText.text = entry.script.ifBlank { getString(R.string.logs_empty_command) }
        row.resultText.text = entry.resultText()
        row.summaryRow.contentDescription = getString(
            if (expanded) R.string.logs_collapse_entry else R.string.logs_expand_entry,
            "${entry.headline}, $meta",
        )
        row.summaryRow.setOnClickListener {
            if (!expandedIds.add(entry.id)) expandedIds.remove(entry.id)
            render()
        }
        binding.logList.addView(row.root)
    }

    private fun ExecutionLogEntry.metaLine(): String {
        val time = timeFormatter.format(Date(startedAtMillis))
        val status = when {
            isRunning -> getString(R.string.logs_running)
            exitCode == 0 -> getString(R.string.logs_done)
            else -> getString(R.string.logs_failed, exitCode ?: 1)
        }
        val duration = durationMillis?.let { " · ${formatDuration(it)}" }.orEmpty()
        return "$time · $status$duration"
    }

    private fun ExecutionLogEntry.resultText(): String {
        if (isRunning) return getString(R.string.logs_running_detail)
        return buildString {
            if (stdout.isNotEmpty()) append(stdout.trimEnd())
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(getString(R.string.logs_stderr)).append('\n').append(stderr.trimEnd())
            }
            if (isEmpty()) append(getString(R.string.logs_no_output))
        }
    }

    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis}ms"
        millis < 10_000 -> String.format(Locale.getDefault(), "%.1fs", millis / 1_000.0)
        else -> "${millis / 1_000}s"
    }

    companion object {
        private const val DEFAULT_VISIBLE_ENTRIES = 5
    }
}
