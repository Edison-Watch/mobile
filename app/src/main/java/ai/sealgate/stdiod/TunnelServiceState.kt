package ai.sealgate.stdiod

import ai.sealgate.stdiod.tunnel.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide mirror of the tunnel's live state, written by [TunnelService]
 * and observed by [MainActivity]. Service and activity share a process but
 * hold no reference to each other, so a shared StateFlow is the cheapest
 * honest channel — no binder, no broadcasts. `null` means the service is not
 * running (as opposed to running-but-disconnected).
 */
object TunnelServiceState {

    private val _state = MutableStateFlow<TunnelState?>(null)
    val state: StateFlow<TunnelState?> = _state

    /** Only [TunnelService] publishes here; everyone else observes [state]. */
    fun publish(state: TunnelState?) {
        _state.value = state
    }
}
