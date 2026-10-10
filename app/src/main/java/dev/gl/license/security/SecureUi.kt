package dev.gl.license.security

import android.app.Activity
import android.view.WindowManager
import androidx.annotation.MainThread

object SecureUi {
    @MainThread
    fun enableFlagSecure(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}

object SecureLog {
    fun redact(s: String): String {
        return s
            .replace(Regex("\\+?[0-9]{8,15}"), "[tel]")
            .replace(Regex("\"ci\"\\s*:\\s*\"[^\"]+\""), "\"ci\":\"[redacted]\"")
            .replace(Regex("\"nonce\"\\s*:\\s*\"[^\"]+\""), "\"nonce\":\"[redacted]\"")
            .replace(Regex("\"ct\"\\s*:\\s*\"[^\"]+\""), "\"ct\":\"[redacted]\"")
            .replace(Regex("\"sig\"\\s*:\\s*\"[^\"]+\""), "\"sig\":\"[redacted]\"")
    }
}
