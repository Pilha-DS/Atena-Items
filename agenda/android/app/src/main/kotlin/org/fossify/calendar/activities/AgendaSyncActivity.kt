package org.fossify.calendar.activities

import android.os.Bundle
import android.text.format.DateUtils
import org.fossify.calendar.R
import org.fossify.calendar.databases.EventsDatabase
import org.fossify.calendar.databinding.ActivityAgendaSyncBinding
import org.fossify.calendar.extensions.config
import org.fossify.calendar.helpers.AgendaSyncClient
import org.fossify.calendar.helpers.AgendaSyncEngine
import org.fossify.calendar.helpers.AgendaSyncErrorKind
import org.fossify.calendar.helpers.AgendaSyncResult
import org.fossify.calendar.jobs.AgendaSyncWorker
import kotlinx.coroutines.runBlocking
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Atena Hub settings: URL, token and device name. Saving validates nothing on
 * the network side except the fields themselves; the first sync reports a
 * wrong token or an unreachable server in the status line.
 */
class AgendaSyncActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityAgendaSyncBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.agendaSyncUrl.setText(config.agendaSyncUrl)
        binding.agendaSyncToken.setText(config.agendaSyncToken)
        binding.agendaSyncDevice.setText(config.agendaSyncDeviceName)
        binding.agendaSyncSave.setOnClickListener { saveAndSync() }
        binding.agendaSyncDisconnect.setOnClickListener { confirmDisconnect() }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.agendaSyncAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.agendaSyncHolder)
        refreshStatus()
    }

    private fun saveAndSync() {
        val url = binding.agendaSyncUrl.text?.toString().orEmpty().trim()
        val token = binding.agendaSyncToken.text?.toString().orEmpty().trim()
        val deviceName = binding.agendaSyncDevice.text?.toString().orEmpty().trim()

        if (url.isEmpty() || token.isEmpty()) {
            toast(R.string.agenda_sync_missing_fields)
            return
        }

        val previousBase = AgendaSyncClient.baseUrl(config.agendaSyncUrl)
        val newBase = AgendaSyncClient.baseUrl(url)
        if (previousBase.isNotEmpty() && previousBase != newBase) {
            // A different hub has its own cursor and knows nothing about the
            // versions acknowledged by the previous one.
            config.agendaSyncCursor = 0L
            ensureBackgroundThread {
                EventsDatabase.getInstance(applicationContext)
                    .AgendaSyncDao()
                    .resetAllSyncedTimes()
            }
        }

        config.agendaSyncUrl = url
        config.agendaSyncToken = token
        if (deviceName.isNotEmpty()) {
            config.agendaSyncDeviceName = deviceName
        }

        hideKeyboard()
        AgendaSyncWorker.schedulePeriodic(this)
        runSync(showResult = true)
    }

    private fun runSync(showResult: Boolean) {
        if (!config.agendaSyncConfigured) {
            toast(R.string.agenda_sync_missing_fields)
            return
        }

        binding.agendaSyncSave.isEnabled = false
        binding.agendaSyncStatus.setText(R.string.agenda_sync_running)

        ensureBackgroundThread {
            val result = runBlocking { AgendaSyncEngine.sync(applicationContext) }
            runOnUiThread {
                if (isDestroyed) {
                    return@runOnUiThread
                }
                binding.agendaSyncSave.isEnabled = true
                renderResult(result, showResult)
            }
        }
    }

    private fun refreshStatus() {
        binding.agendaSyncStatus.text = when {
            !config.agendaSyncConfigured -> getString(R.string.agenda_sync_not_configured)
            config.agendaSyncLastAt == 0L -> getString(R.string.agenda_sync_never_synced)
            else -> lastSyncText()
        }
    }

    private fun renderResult(result: AgendaSyncResult, showResult: Boolean) {
        binding.agendaSyncStatus.text = when {
            !result.configured -> getString(R.string.agenda_sync_not_configured)
            result.ok && showResult -> getString(
                R.string.agenda_sync_result,
                result.pushed,
                result.pulled
            )

            result.ok -> lastSyncText()
            else -> errorText(result)
        }
    }

    private fun lastSyncText(): String {
        val relative = DateUtils.getRelativeTimeSpanString(
            config.agendaSyncLastAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        return getString(R.string.agenda_sync_last_sync, relative)
    }

    private fun errorText(result: AgendaSyncResult): String = when (result.error) {
        AgendaSyncErrorKind.UNAUTHORIZED -> getString(R.string.agenda_sync_error_unauthorized)
        AgendaSyncErrorKind.NETWORK -> getString(R.string.agenda_sync_error_network)
        AgendaSyncErrorKind.INVALID_RESPONSE -> getString(R.string.agenda_sync_error_invalid_response)
        AgendaSyncErrorKind.SERVER -> getString(
            R.string.agenda_sync_error_server,
            result.errorDetail.orEmpty()
        )

        else -> getString(R.string.agenda_sync_not_configured)
    }

    private fun confirmDisconnect() {
        ConfirmationDialog(
            activity = this,
            messageId = R.string.agenda_sync_disconnect_confirmation
        ) {
            config.clearAgendaSync()
            AgendaSyncWorker.cancelPeriodic(this)
            AgendaSyncWorker.cancelPending(this)
            ensureBackgroundThread {
                EventsDatabase.getInstance(applicationContext)
                    .AgendaSyncDao()
                    .resetAllSyncedTimes()
            }
            binding.agendaSyncUrl.setText("")
            binding.agendaSyncToken.setText("")
            refreshStatus()
        }
    }
}
