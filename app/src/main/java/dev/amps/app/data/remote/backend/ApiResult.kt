package dev.amps.app.data.remote.backend

/**
 * 1.1.0: Результат API запиту.
 * Використовуємо прості data class замість sealed class для уникнення багу компілятора.
 * Усі вкладені класи не мають type parameters для сумісності з офлайн-збіркою.
 */
sealed class ApiResult {
    data class Success(val data: Any?) : ApiResult()
    data class Failure(val code: Int, val error: String, val errorCode: String?) : ApiResult()
    data class NetworkError(val message: String) : ApiResult()
}