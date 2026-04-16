package com.example.attentionawaremediacontrolleraiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * All UI state in one snapshot — makes the Activity's collector trivial.
 *
 * [facePresent]  — raw per-frame signal (updates every analyzed frame)
 * [shouldPlay]   — debounced decision: the player follows this, not facePresent
 * [latencyMs]    — time from ImageProxy.analyze() to ML Kit callback, for display
 */
data class UiState(
    val facePresent: Boolean = false,
    val shouldPlay: Boolean = true,  // start optimistic: assume face is there
    val latencyMs: Long = 0L
)

class PlayerViewModel : ViewModel() {

    companion object {
        private const val PAUSE_DEBOUNCE_MS  = 700L  // no face for this long → pause
        private const val RESUME_DEBOUNCE_MS = 500L  // face present for this long → play
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Track the last signal direction so we only start a new debounce on a flip
    private var lastFacePresent: Boolean? = null
    private var debounceJob: Job? = null

    /**
     * Called from the camera thread (via FaceAnalyzer) on every processed frame.
     * Thread-safe: StateFlow.update and coroutine launch are both safe from any thread.
     */
    fun onFaceDetected(facePresent: Boolean, latencyMs: Long) {
        // Always refresh the raw display values
        _uiState.update { it.copy(facePresent = facePresent, latencyMs = latencyMs) }

        // Only kick off a debounce timer when the signal flips direction.
        // This means: if face disappears, we wait 700 ms before pausing.
        // If face reappears before 700 ms, we cancel that job and wait 500 ms to resume.
        if (facePresent == lastFacePresent) return
        lastFacePresent = facePresent

        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            val waitMs = if (facePresent) RESUME_DEBOUNCE_MS else PAUSE_DEBOUNCE_MS
            delay(waitMs)
            _uiState.update { it.copy(shouldPlay = facePresent) }
        }
    }
}
