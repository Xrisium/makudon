package io.github.xrisium.makudon

import android.inputmethodservice.InputMethodService

/** The instrumentation owns candidate updates; no second IME may finish them. */
class FixtureInputMethod : InputMethodService() {
    override fun onEvaluateInputViewShown() = false
}
