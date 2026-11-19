package id.straikcam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Mode { OFF, LOADING, STANDBY, RECORDING, ERROR }

data class Status(val mode: Mode, val message: String)

/** Shared status so the screen can show what the background service is doing. */
object AppState {
    private val _status = MutableStateFlow(Status(Mode.OFF, "Siaga mati"))
    val status: StateFlow<Status> = _status.asStateFlow()

    fun set(mode: Mode, message: String) {
        _status.value = Status(mode, message)
    }
}
