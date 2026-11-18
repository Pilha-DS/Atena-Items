package org.fossify.calendar.adapters

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.databinding.ItemQuickTimerBinding
import org.fossify.calendar.helpers.QuickTimerTimes
import org.fossify.calendar.models.QuickTimer
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor

/**
 * The running and finished quick timers. The countdown is recomputed from the
 * absolute deadline on every tick, so a rebuilt list or a device that slept
 * never shows stale numbers.
 */
class QuickTimersAdapter(
    private val activity: SimpleActivity,
    private val timers: List<QuickTimer>,
    private val onDelete: (QuickTimer) -> Unit
) : RecyclerView.Adapter<QuickTimersAdapter.ViewHolder>() {

    private var now: Long = System.currentTimeMillis()

    inner class ViewHolder(val binding: ItemQuickTimerBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickTimerBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bind(holder, timers[position])
    }

    override fun getItemCount() = timers.size

    /** Called once a second; only the labels change. */
    fun update(newNow: Long) {
        if (newNow / 1000L == now / 1000L) {
            return
        }
        now = newNow
        notifyItemRangeChanged(0, itemCount)
    }

    private fun bind(holder: ViewHolder, timer: QuickTimer) {
        val context = activity
        val textColor = context.getProperTextColor()
        val primaryColor = context.getProperPrimaryColor()
        val finished = QuickTimerTimes.isFinished(timer, now)

        holder.binding.apply {
            quickTimerTitle.text = timer.title.ifBlank {
                context.getString(R.string.quick_timer_default_title)
            }
            quickTimerTitle.setTextColor(textColor)

            quickTimerRemaining.text = if (finished) {
                context.getString(R.string.quick_timer_finished)
            } else {
                QuickTimerTimes.remainingLabel(timer.endTime, now)
            }
            quickTimerRemaining.setTextColor(if (finished) textColor else primaryColor)

            quickTimerEnd.text = context.getString(
                R.string.quick_timer_ends_at,
                QuickTimerTimes.endLabel(timer.endTime)
            )
            quickTimerEnd.setTextColor(textColor)

            quickTimerDelete.drawable?.mutate()?.setTint(textColor)
            quickTimerDelete.setOnClickListener { onDelete(timer) }
        }
    }
}
