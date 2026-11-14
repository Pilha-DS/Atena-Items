package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.R

/**
 * One sentence for a finished round. The settings screen and the toolbar
 * action both report through here, so a new error kind only has to be taught
 * once.
 */
fun AgendaSyncResult.statusMessage(context: Context): String = when {
    !configured -> context.getString(R.string.agenda_sync_not_configured)
    ok -> context.getString(R.string.agenda_sync_result, pushed, pulled)
    else -> errorMessage(context)
}

private fun AgendaSyncResult.errorMessage(context: Context): String = when (error) {
    AgendaSyncErrorKind.UNAUTHORIZED -> context.getString(R.string.agenda_sync_error_unauthorized)
    AgendaSyncErrorKind.NETWORK -> context.getString(R.string.agenda_sync_error_network)
    AgendaSyncErrorKind.INVALID_RESPONSE ->
        context.getString(R.string.agenda_sync_error_invalid_response)
    AgendaSyncErrorKind.SERVER ->
        context.getString(R.string.agenda_sync_error_server, errorDetail.orEmpty())
    else -> context.getString(R.string.agenda_sync_not_configured)
}
