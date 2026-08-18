package com.hardplay.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardplay.telegram.GatewayResult
import com.hardplay.telegram.TelegramAuthState
import com.hardplay.telegram.TelegramGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sign-in.
 *
 * The screen's *stage* — phone, code, password — comes from
 * [TelegramGateway.authState], never from local state. TDLib owns that machine and
 * can move it on its own (a session restore, a code arriving on another device, a
 * remote log-out), so a second copy here would go stale and strand the user on a
 * screen the library has already moved past. What this ViewModel owns is only what
 * TDLib has no opinion about: the half-typed field contents and whether a call is
 * in flight.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val gateway: TelegramGateway,
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = gateway.authState

    private val _ui = MutableStateFlow(LoginUiState(isDemo = gateway.isDemo))
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { gateway.start() }
    }

    fun onPhoneChange(value: String) {
        // Keep the leading + and digits; drop the spaces, brackets and dashes people
        // paste in from a contacts app.
        val cleaned = buildString {
            value.forEachIndexed { index, c ->
                if (c.isDigit() || (c == '+' && index == 0)) append(c)
            }
        }
        _ui.update { it.copy(phone = cleaned, error = null) }
    }

    fun onCodeChange(value: String) = _ui.update { it.copy(code = value, error = null) }

    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, error = null) }

    fun submitPhone() = run("Requesting a code") {
        gateway.requestVerificationCode(_ui.value.phone)
    }

    fun submitCode() = run("Checking the code") {
        gateway.submitVerificationCode(_ui.value.code)
    }

    fun submitPassword() = run("Checking the password") {
        gateway.submitPassword(_ui.value.password)
    }

    fun resendCode() = run("Resending") {
        gateway.resendVerificationCode()
    }

    /**
     * Runs one gateway call with the busy flag and error handling around it.
     *
     * The guard on [LoginUiState.busy] matters more than it looks: submitting a code
     * twice is not merely wasteful, it burns the code — Telegram invalidates it on
     * first use, so the second attempt fails and the user is told their correct code
     * is wrong.
     */
    private fun run(label: String, call: suspend () -> GatewayResult<Unit>) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, busyLabel = label, error = null) }
        viewModelScope.launch {
            val result = call()
            _ui.update { state ->
                when (result) {
                    is GatewayResult.Success -> state.copy(
                        busy = false,
                        busyLabel = null,
                        // Clear the code on success so moving to the password step
                        // doesn't leave a stale one behind to be resubmitted.
                        code = "",
                        error = null,
                    )
                    is GatewayResult.Failure -> state.copy(
                        busy = false,
                        busyLabel = null,
                        error = result.message,
                        retryAfterSeconds = result.retryAfterSeconds,
                    )
                }
            }
        }
    }
}

data class LoginUiState(
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val error: String? = null,
    val retryAfterSeconds: Int = 0,
    val isDemo: Boolean = false,
) {
    /** Enough digits to be a number at all. Telegram does the real validation. */
    val phoneLooksUsable: Boolean get() = phone.count(Char::isDigit) >= 6
}
