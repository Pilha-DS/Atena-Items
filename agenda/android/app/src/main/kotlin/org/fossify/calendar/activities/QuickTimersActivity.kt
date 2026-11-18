package org.fossify.calendar.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import org.fossify.calendar.R
import org.fossify.calendar.adapters.QuickTimersAdapter
import org.fossify.calendar.databinding.ActivityQuickTimersBinding
import org.fossify.calendar.extensions.quickTimersDB
import org.fossify.calendar.helpers.QuickTimerAlarms
import org.fossify.calendar.helpers.QuickTimerNotification
import org.fossify.calendar.helpers.QuickTimerTimes
import org.fossify.calendar.jobs.AgendaSyncWorker
import org.fossify.calendar.models.QuickTimer
import org.fossify.calendar.models.QuickTimerTombstone
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyButton
import java.util.UUID

/**
 * "Assuntos rápidos": short countdowns, like the phone's clock timer.
 *
 * The screen only asks for a duration and a name; the deadline is stored and
 * an exact alarm rings when it is reached, with the app open or not. Each
 * device rings its own copy, so the sync carries just the deadline.
 */
@Suppress("TooManyFunctions")
class QuickTimersActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityQuickTimersBinding::inflate)
    private val handler = Handler(Looper.getMainLooper())
    private var adapter: QuickTimersAdapter? = null

    private val ticker = object : Runnable {
        override fun run() {
            adapter?.update(System.currentTimeMillis())
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupPresets()
        binding.quickTimerStart.setOnClickListener { startTimer() }
        binding.quickTimerTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startTimer()
                true
            } else {
                false
            }
        }
        binding.quickTimersToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.quick_timers_clear_finished -> {
                    clearFinished()
                    true
                }

                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.quickTimersAppbar, NavigationIcon.Arrow)
        setupEdgeToEdge(padBottomSystem = listOf(binding.quickTimersList))
        updateTextColors(binding.quickTimersContent)
        loadTimers()
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    private fun setupPresets() {
        val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        for (minutes in QuickTimerTimes.presets) {
            val button = MyButton(this).apply {
                text = presetLabel(minutes)
                isAllCaps = false
                setOnClickListener {
                    binding.quickTimerDuration.setText(minutes.toString())
                    binding.quickTimerDuration.setSelection(
                        binding.quickTimerDuration.text?.length ?: 0
                    )
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = margin
            }
            binding.quickTimersPresets.addView(button, params)
        }
    }

    private fun presetLabel(minutes: Int): String = when {
        minutes >= MINUTES_PER_HOUR && minutes % MINUTES_PER_HOUR == 0 ->
            getString(R.string.quick_timer_preset_hours, minutes / MINUTES_PER_HOUR)

        else -> getString(R.string.quick_timer_preset_minutes, minutes)
    }

    private fun startTimer() {
        val raw = binding.quickTimerDuration.text?.toString().orEmpty()
        val duration = QuickTimerTimes.parseDurationMillis(raw)
        if (duration == null) {
            toast(R.string.quick_timer_invalid_duration)
            return
        }

        val title = binding.quickTimerTitle.text?.toString().orEmpty().trim()
        hideKeyboard()
        // A timer that cannot notify is useless, so the permission is asked
        // here, the first time, instead of failing silently at the deadline.
        handleNotificationAvailability { createTimer(title, duration) }
    }

    private fun createTimer(title: String, duration: Long) {
        val startedAt = System.currentTimeMillis()
        val timer = QuickTimer(
            uuid = UUID.randomUUID().toString(),
            title = title,
            endTime = startedAt + duration,
            createdTime = startedAt,
            updatedTime = startedAt
        )

        ensureBackgroundThread {
            applicationContext.quickTimersDB.insert(timer)
            QuickTimerAlarms.schedule(applicationContext, timer)
            AgendaSyncWorker.syncSoon(applicationContext)
            runOnUiThread {
                if (isDestroyed) {
                    return@runOnUiThread
                }
                binding.quickTimerDuration.setText("")
                binding.quickTimerTitle.setText("")
                loadTimers()
            }
        }
    }

    private fun loadTimers() {
        ensureBackgroundThread {
            val timers = applicationContext.quickTimersDB.getAll()
            runOnUiThread {
                if (isDestroyed) {
                    return@runOnUiThread
                }
                adapter = QuickTimersAdapter(this, timers) { deleteTimer(it) }
                binding.quickTimersList.adapter = adapter
                if (timers.isEmpty()) {
                    binding.quickTimersEmpty.beVisible()
                    binding.quickTimersList.beGone()
                } else {
                    binding.quickTimersEmpty.beGone()
                    binding.quickTimersList.beVisible()
                }
                adapter?.update(System.currentTimeMillis())
            }
        }
    }

    private fun deleteTimer(timer: QuickTimer) {
        ConfirmationDialog(
            activity = this,
            messageId = R.string.quick_timer_delete_confirmation
        ) {
            ensureBackgroundThread {
                deleteRow(timer)
                runOnUiThread {
                    if (!isDestroyed) {
                        loadTimers()
                    }
                }
            }
        }
    }

    private fun clearFinished() {
        ensureBackgroundThread {
            val now = System.currentTimeMillis()
            val finished = applicationContext.quickTimersDB.getAll()
                .filter { QuickTimerTimes.isFinished(it, now) }
            if (finished.isEmpty()) {
                runOnUiThread {
                    if (!isDestroyed) {
                        toast(R.string.quick_timer_nothing_to_clear)
                    }
                }
                return@ensureBackgroundThread
            }

            finished.forEach { deleteRow(it) }
            runOnUiThread {
                if (!isDestroyed) {
                    loadTimers()
                }
            }
        }
    }

    /** A hard delete locally, plus the tombstone the next push needs. */
    private fun deleteRow(timer: QuickTimer) {
        val dao = applicationContext.quickTimersDB
        dao.deleteByUuid(timer.uuid)
        dao.insertTombstone(
            QuickTimerTombstone(
                uuid = timer.uuid,
                deletedAt = maxOf(System.currentTimeMillis(), timer.updatedTime + 1L)
            )
        )
        QuickTimerAlarms.cancel(applicationContext, timer.uuid)
        QuickTimerNotification.dismiss(applicationContext, timer.uuid)
        AgendaSyncWorker.syncSoon(applicationContext)
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MINUTES_PER_HOUR = 60
    }
}
