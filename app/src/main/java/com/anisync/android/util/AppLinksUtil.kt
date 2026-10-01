package com.anisync.android.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

object AppLinksUtil {

    /**
     * Opens [url] in a web browser rather than in this app. Locates an installed browser via a
     * neutral host, then targets that package explicitly, so no app that claims the link (this one
     * included) can intercept it. Falls back to a chooser without this app.
     */
    fun openInBrowser(context: Context, url: String) {
        val uri = url.toUri()
        val pm = context.packageManager
        val ownPackage = context.packageName

        val probe = Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browserPackage = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != ownPackage }

        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            if (browserPackage != null) {
                context.startActivity(view.setPackage(browserPackage))
            } else {
                context.startActivity(chooserExcludingSelf(pm, ownPackage, view))
            }
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    chooserExcludingSelf(
                        pm,
                        ownPackage,
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                )
            }
        }
    }

    /** A chooser for [view] without this app's own handlers (last resort when no browser resolves). */
    private fun chooserExcludingSelf(pm: PackageManager, ownPackage: String, view: Intent): Intent {
        val excluded = pm.queryIntentActivities(view, PackageManager.MATCH_ALL)
            .map { it.activityInfo }
            .filter { it.packageName == ownPackage }
            .map { ComponentName(it.packageName, it.name) }
            .toTypedArray()
        return Intent.createChooser(view, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (excluded.isNotEmpty()) putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded)
        }
    }
}
