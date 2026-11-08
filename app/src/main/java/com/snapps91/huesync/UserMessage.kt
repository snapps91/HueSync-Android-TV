package com.snapps91.huesync

import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Short, actionable messages for errors surfaced in the TV interface. */
internal object UserMessage {
    fun from(error: Throwable): String = when (error) {
        is SSLException ->
            English.HUE_CERTIFICATE_COULD_NOT_BE_VERIFIED
        is SocketTimeoutException -> English.BRIDGE_UNREACHABLE_REQUEST_TIMED_OUT
        else -> error.message?.take(180) ?: error.javaClass.simpleName
    }
}
