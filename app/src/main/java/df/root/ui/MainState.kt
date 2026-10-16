package df.root.ui

/** Module execution snapshot; manager and startup configuration live in the plan. */
data class MainUiState(
    val advancedLog: Boolean = false,
    val running: Boolean = false,
    val rooted: Boolean = false,
    val failedRun: Boolean = false,
    val logText: String = "",
    val hasRun: Boolean = false,
    val progress1: Float = 0f,
    val progress2: Float = 0f,
    val statusLeft: String = "",
    val statusRight: String = "",
    val updateAvailable: Boolean = false,
    val versionName: String = "",
    val runArmed: Boolean = false,
) {
    val canRun: Boolean get() = !running
}

interface MainCallbacks {
    fun onCancelRunConfirmation()
    fun onRunClick()
    fun onSaveLog()
    fun onAdvancedLogChange(enabled: Boolean)
    fun onOpenGithub()
}
