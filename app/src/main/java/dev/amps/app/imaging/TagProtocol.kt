package dev.amps.app.imaging

/**
 * Разговор между основным процессом и процессом тегера.
 *
 * Тегер живёт в отдельном процессе `:tagger` не по вкусу, а по необходимости:
 * падение внутри ONNX Runtime — это нативный SIGSEGV, который не ловится
 * ни одним Java-обработчиком и уносит процесс вместе с приложением.
 * Отдельный процесс стоит стеной: упал он — упал только он.
 *
 * Обмен идёт через `Messenger`, а картинка передаётся не в `Bundle`, а путём
 * до файла: JPEG весит мегабайты, а лимит одной транзакции Binder — около
 * 1 МБ, и большая картинка уронила бы связь сама по себе.
 */
internal object TagProtocol {

    /** Основной процесс → процесс тегера: разобрать картинку. */
    const val MSG_ANALYZE = 1

    /** Процесс тегера → основной: результат разбора. */
    const val MSG_RESULT = 2

    /** Процесс тегера → основной: разобрать не удалось. */
    const val MSG_UNAVAILABLE = 3

    const val KEY_PATH = "path"
    const val KEY_REPLY_TO = "replyTo"
    const val KEY_TOKEN = "token"

    const val KEY_OK = "ok"
    const val KEY_REASON = "reason"
    const val KEY_ELAPSED_MS = "elapsedMs"

    /** Имена тегов и их вероятности идут параллельными массивами. */
    const val KEY_NAMES = "names"
    const val KEY_PROBS = "probs"

    /** Ключ, по которому основной процесс узнаёт, что тегер умер. */
    const val KEY_DISCONNECTED = "disconnected"
}