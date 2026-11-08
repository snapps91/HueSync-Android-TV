package com.snapps91.huesync

import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Short, actionable messages for errors surfaced in the TV interface. */
internal object UserMessage {
    fun from(error: Throwable): String = when (error) {
        is SSLException ->
            "Hue certificate could not be verified. Check the IP address, TV date, and Bridge firmware."
        is SocketTimeoutException -> "Bridge unreachable: request timed out."
        else -> error.message?.take(180) ?: error.javaClass.simpleName
    }
}
