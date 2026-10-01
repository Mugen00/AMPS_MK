package dev.kagami.app.util

import java.io.IOException

/** A message worth showing to a user instead of a stack trace. */
fun Throwable.readableMessage(): String = when (this) {
    is IOException -> message?.takeIf { it.isNotBlank() } ?: "Нет связи с сервером"
    is IllegalStateException -> message?.takeIf { it.isNotBlank() } ?: "Сервер ответил ошибкой"
    else -> message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
}
