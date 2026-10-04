package dev.amps.app.imaging

import dev.amps.app.data.model.AppearanceLexicon
import dev.amps.app.data.model.CharacterHypothesis
import dev.amps.app.data.model.CharacterTagName
import dev.amps.app.data.model.ContentTag
import dev.amps.app.data.model.FrameContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Говорит, что **есть** на картинке, и это делает по-настоящему.
 *
 * **1.0.6: вместо общих словарей — аниме-тегер.** До этой версии здесь стоял
 * ML Kit, и весь его вклад в приложение был свёрнут в одну фразу «на картинке
 * есть человек»: общие английские категории вроде `person`, `sky`, `building`
 * не отличают одного персонажа от другого и стоили 20 МБ APK, то есть 81 % его
 * размера. Тегер `wd-swinv2-tagger-v3` вместо этого выдаёт бо́ру-теги, из
 * которых получаются и имя персонажа (через AniList), и описание внешности
 * (через [AppearanceLexicon]).
 *
 * Здесь живёт только превращение ответа модели в [FrameContent]. Имена
 * персонажей до AniList здесь не доходят: это сетевой запрос, и делать его
 * внутри «разбора картинки» было бы смешением двух разных дел.
 *
 * **Здесь не бывает исключений.** Любой сбой — это [FrameContent] с
 * `analyzed = false` и русским [FrameContent.unavailableReason], а телефон
 * продолжает искать дальше. При этом `null` от тегера означает «модель
 * недоступна», и это не то же самое, что «персонажа нет»: первое попадает в
 * `unavailableReason`, второе остаётся пустым списком при `analyzed = true`.
 */
class ContentAnalyzer(private val tagger: AnimeTagger) {

    /**
     * Разбирает один кадр.
     *
     * [jpegBytes] должен быть закодированной картинкой — вызывающий это
     * гарантирует: на вход приходит ровно то, что уже готовил
     * `ImageLoader.prepare` для отправки в IQDB, то есть не больше 1280 px по
     * длинной стороне. Тегер сам тянет модель на 448×448, и замеры показали,
     * что уменьшение картинки до 230 px меняло итог на 0 из 21 проверки, —
     * поэтому ничего лишнего сжимать здесь не нужно.
     *
     * Всё считается на [Dispatchers.Default], вызывающий никогда не платит за
     * это на главном потоке. Первый вызов дополнительно копирует модель из
     * assets в файлы приложения — это около 167 МБ, поэтому в первый замер
     * входит и оно, и [FrameContent.elapsedMs] показывает полное время.
     */
    suspend fun analyze(jpegBytes: ByteArray): FrameContent = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()

        if (jpegBytes.isEmpty()) return@withContext unavailable(EMPTY_INPUT_REASON, startedAt)
        // ~12 МБ JPEG — это далеко за пределами кадра; отказываем до того, как
        // модель попытается его декодировать.
        if (jpegBytes.size > MAX_INPUT_BYTES) return@withContext unavailable(TOO_LARGE_REASON, startedAt)

        val result = runCatching { tagger.tag(jpegBytes) }.getOrNull()
            ?: return@withContext unavailable(TAGGER_UNAVAILABLE_REASON, startedAt)

        FrameContent(
            characters = result.characters.map { tag ->
                CharacterHypothesis(
                    tag = tag.name,
                    // Имена ещё нет: до него запрос в AniList, а он не здесь.
                    // Пока показываем то, что можно показать честно, — имя,
                    // вытащенное из бо́ру-тега по [CharacterTagName].
                    name = CharacterTagName.normalize(tag.name) ?: tag.name,
                    probability = tag.probability,
                )
            },
            appearance = result.appearance.mapNotNull { tag ->
                // Тега нет в словаре — пропускаем молча. Заглушка «признак №7»
                // была бы выдумкой, которой пользователь поверит.
                val phrase = AppearanceLexicon.phrase(tag.name) ?: return@mapNotNull null
                ContentTag(tag = tag.name, probability = tag.probability, phrase = phrase)
            },
            description = AppearanceLexicon.describe(result.appearance.map { it.name to it.probability }),
            hasPerson = result.hasPerson,
            analyzed = true,
            unavailableReason = null,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun unavailable(reason: String, startedAt: Long): FrameContent =
        FrameContent(analyzed = false, unavailableReason = reason, elapsedMs = System.currentTimeMillis() - startedAt)

    private companion object {

        /** ~12 МБ JPEG — это не кадр, а чудовищная картинка. */
        const val MAX_INPUT_BYTES = 12 * 1024 * 1024

        const val EMPTY_INPUT_REASON = "Кадр пустой"
        const val TOO_LARGE_REASON = "Кадр слишком большой для анализа"

        /**
         * Модель не смогла отработать: файла нет, он недокачан, или устройство
         * не тянет. Это честное «определить нечего», а не «персонажа нет».
         */
        const val TAGGER_UNAVAILABLE_REASON =
            "Распознавание на устройстве недоступно: модель не смогла запуститься"
    }
}
