package dev.amps.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
// В ONNX Runtime Java API `SessionOptions` — вложенный класс `OrtSession`,
// отдельного класса `ai.onnxruntime.SessionOptions` нет.
import ai.onnxruntime.OrtSession.SessionOptions

/**
 * Распознавание персонажа и внешности по картинке, целиком на устройстве.
 *
 * Модель — `SmilingWolf/wd-swinv2-tagger-v3` (Apache-2.0), сжатая до int8.
 * Выбор обоснован замерами на наборе из 36 картинок с известным ответом:
 * эталонный персонаж попадает в первую пятёрку в 100% случаев, первым —
 * в 55,6% при fp32 и 52,8% при int8. То есть правильный ответ в списке
 * есть всегда, просто не всегда на первом месте, — поэтому наружу
 * отдаётся пятёрка, а не один тег.
 *
 * Разрешение на вход не влияет: на тех же картинках сжатие до 230 px по
 * ширине меняло итог на 0 из 21. Поэтому модель тянет 448×448, как обучена.
 *
 * Порог [CHARACTER_THRESHOLD] выбран по измеренному разрыву: уверенное
 * определение даёт 0,73, а мусор на не-anime картинках держится у 0,50–0,53.
 * Между ними пусто, поэтому 0,60 отсекает шум, не задевая настоящие находки.
 */
class AnimeTagger(private val context: Context) {

    data class Tag(val name: String, val category: Int, val probability: Float)

    data class Result(
        /** Персонажи, отсортированные по убыванию вероятности. */
        val characters: List<Tag>,
        /** Признаки внешности: волосы, одежда, фон. */
        val appearance: List<Tag>,
        /** Сколько занял анализ, для показа в интерфейсе. */
        val elapsedMs: Long,
    ) {
        /**
         * Есть ли на картинке кто-то вообще. Раньше этот сигнал давал ML Kit,
         * теперь он выводится из тегов: у одиночного персонажа есть тег
         * `1girl` или `1boy`, у группового — `multiple_girls`/`multiple_boys`.
         */
        val hasPerson: Boolean
            get() = appearance.any {
                it.name == "1girl" || it.name == "1boy" ||
                    it.name == "multiple_girls" || it.name == "multiple_boys"
            }
    }

    private val sessionRef = AtomicReference<Handle?>(null)

    private class Handle(val session: OrtSession, val tags: List<String>, val categories: IntArray)

    /**
     * Разбирает и запускает модель. `null` — модель недоступна или не
     * смогла отработать; вызывающий обязан трактовать это как «определить
     * нечего», а не как «персонажа нет».
     */
    suspend fun tag(bytes: ByteArray): Result? = withContext(Dispatchers.Default) {
        val handle = acquire() ?: return@withContext null
        val bitmap = decode(bytes) ?: return@withContext null
        val startedAt = System.currentTimeMillis()

        return@withContext try {
            val input = toInputTensor(bitmap)
            try {
                // Типы указаны явно: у `run` есть перегрузки с `RunOptions` и
                // `Map<String, OnnxValue>`, и без подсказки компилятор не может
                // выбрать единственную подходящую.
                val output = handle.session.run(
                    mapOf<String, OnnxTensor>(handle.session.inputInfo.keys.first() to input)
                ).use { results ->
                    results[0].value as FloatArray
                }
                buildResult(handle, output, System.currentTimeMillis() - startedAt)
            } finally {
                input.close()
            }
        } catch (error: Exception) {
            Log.w(TAG, "тегер не отработал: ${error.message}")
            null
        }
    }

    private fun buildResult(handle: Handle, output: FloatArray, elapsedMs: Long): Result {
        val characters = ArrayList<Tag>()
        val appearance = ArrayList<Tag>()

        // Первые четыре выхода — оценки general/sensitive/questionable/explicit,
        // теги начинаются с пятого и идут в том же порядке, что в CSV.
        for (i in handle.tags.indices) {
            val index = i + RATING_COUNT
            if (index >= output.size) break
            val probability = sigmoid(output[index])
            val category = handle.categories[i]
            when {
                category == CATEGORY_CHARACTER && probability >= CHARACTER_THRESHOLD ->
                    characters += Tag(handle.tags[i], category, probability)
                category == CATEGORY_GENERAL && probability >= APPEARANCE_THRESHOLD ->
                    appearance += Tag(handle.tags[i], category, probability)
            }
        }

        characters.sortByDescending { it.probability }
        appearance.sortByDescending { it.probability }

        return Result(
            characters = characters.take(CHARACTER_KEEP),
            appearance = appearance.take(APPEARANCE_KEEP),
            elapsedMs = elapsedMs,
        )
    }

    /**
     * Модель лежит в assets, но ONNX Runtime открывает её по пути. Поэтому
     * при первом запуске файл один раз копируется в файлы приложения.
     * Размер около 167 МБ, поэтому копирование ленивое и помеченное.
     */
    private fun acquire(): Handle? {
        sessionRef.get()?.let { return it }
        synchronized(this) {
            sessionRef.get()?.let { return it }

            val model = runCatching { materialiseModel() }.getOrNull() ?: return null
            val tags = runCatching { loadTags() }.getOrNull() ?: return null

            val created = runCatching {
                val environment = OrtEnvironment.getEnvironment()
                val options = SessionOptions().apply { setIntraOpNumThreads(2) }
                Handle(environment.createSession(model.absolutePath, options), tags.first, tags.second)
            }.getOrNull() ?: return null

            return if (sessionRef.compareAndSet(null, created)) created else created
        }
    }

    private fun materialiseModel(): File {
        val target = File(context.filesDir, MODEL_FILE)
        // Файл считается готовым только если длина совпадает: обрыв копирования
        // оставил бы после него модель, которая падает при первой загрузке.
        if (target.exists() && target.length() == EXPECTED_MODEL_BYTES) return target

        target.delete()
        val temporary = File(target.parentFile, "$MODEL_FILE.part")
        temporary.delete()
        context.assets.open(ASSET_MODEL).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("не удалось сохранить модель")
        }
        return target
    }

    private fun loadTags(): Pair<List<String>, IntArray> {
        val names = ArrayList<String>(TAG_COUNT)
        val categories = IntArray(TAG_COUNT)
        var index = 0
        context.assets.open(ASSET_TAGS).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isBlank() || index >= TAG_COUNT) return@forEach
                // tag_id,category,name — порядок колонок задан SmilingWolf.
                val parts = line.split(",", limit = 3)
                if (parts.size == 3) {
                    names += parts[2].trim()
                    categories[index] = parts[1].trim().toIntOrNull() ?: 0
                    index++
                }
            }
        }
        if (index != TAG_COUNT) throw IllegalStateException("в списке тегов $index из $TAG_COUNT")
        return names to categories
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > MAX_INPUT_EDGE_PX || bounds.outHeight > MAX_INPUT_EDGE_PX) {
            Log.w(TAG, "картинка ${bounds.outWidth}×${bounds.outHeight} больше допустимого")
            return null
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
    }

    /**
     * Модель обучена на 448×448 в раскладке NHWC, каналы BGR, значения
     * 0..255 без нормализации. Растягиваем, а не обрезаем: по краям кадра
     * обычно и находится персонаж, а центральная вырезка его срезает.
     */
    private fun toInputTensor(bitmap: Bitmap): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val row = IntArray(INPUT_SIZE)
        for (y in 0 until INPUT_SIZE) {
            scaled.getPixels(row, 0, INPUT_SIZE, 0, y, INPUT_SIZE, 1)
            for (x in 0 until INPUT_SIZE) {
                val pixel = row[x]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                // BGR, как ждёт экспорт ONNX.
                buffer.put(blue.toFloat())
                buffer.put(green.toFloat())
                buffer.put(red.toFloat())
            }
        }
        buffer.rewind()

        return OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            buffer,
            longArrayOf(1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong(), 3),
        )
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + kotlin.math.exp(-x))

    companion object {
        private const val TAG = "AnimeTagger"

        private const val ASSET_MODEL = "models/anime_tagger_int8.onnx"
        private const val ASSET_TAGS = "models/anime_tags.csv"
        private const val MODEL_FILE = "anime_tagger_int8.onnx"

        /** Длина model_int8.onnx в байтах; защита от недокачанного файла. */
        private const val EXPECTED_MODEL_BYTES = 174_952_996L

        private const val INPUT_SIZE = 448
        private const val MAX_INPUT_EDGE_PX = 8192

        /** Четыре оценки, дальше идут теги. */
        private const val RATING_COUNT = 4
        private const val TAG_COUNT = 10_861

        private const val CATEGORY_GENERAL = 0
        private const val CATEGORY_CHARACTER = 4

        /**
         * Между мусором (0,50–0,53) и уверенной находкой (0,73) измерен
         * разрыв. Ни одно настоящее определение не опускалось ниже 0,60.
         */
        const val CHARACTER_THRESHOLD = 0.60f
        const val APPEARANCE_THRESHOLD = 0.45f

        /** Показываем пятёрку: правильный ответ в ней есть всегда. */
        const val CHARACTER_KEEP = 5
        const val APPEARANCE_KEEP = 24
    }
}