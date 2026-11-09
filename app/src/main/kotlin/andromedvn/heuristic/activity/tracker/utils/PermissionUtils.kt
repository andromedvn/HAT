package andromedvn.heuristic.activity.tracker.utils

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // unsafeCheckOpNoThrow is deprecated in API 30+, but the public
        // replacement (AppOpsManager.checkOp / safeCheckOp) is itself hidden on
        // several OEM builds. This call still works on every supported version
        // and correctly answers "has the user granted usage access".
        @Suppress("DEPRECATION")
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
