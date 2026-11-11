package app.familygem.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Explicit application-lifetime scope for jobs that must survive activity changes,
 * such as persisting family trees. Screen-only tasks should use lifecycleScope.
 */
internal object AppCoroutineScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
