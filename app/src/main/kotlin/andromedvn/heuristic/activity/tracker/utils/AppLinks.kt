package andromedvn.heuristic.activity.tracker.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Voluntary sponsorship link. Opened via Intent.ACTION_VIEW so HAT itself makes
 * no network request — this app declares no INTERNET permission, and that claim
 * must stay literally true. Swapping platforms (Ko-fi / GitHub Sponsors) means
 * changing only this constant.
 */
const val SPONSOR_URL = "https://ko-fi.com/andromedvn"

/**
 * Hands a URL to the user's browser. Returns false if no installed app could
 * handle it, so callers can surface a message instead of silently doing nothing.
 */
fun Context.openExternalWebLink(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (e: Exception) {
    false
}
