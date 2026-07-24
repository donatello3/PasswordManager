package io.kmanager.app.utils

/**
 * Валидация мастер-пароля по стандарту NIST SP 800-63B + отраслевая практика.
 *
 * Требования:
 *  - минимум 8 символов
 *  - хотя бы 1 заглавная буква (A-Z)
 *  - хотя бы 1 строчная буква (a-z)
 *  - хотя бы 1 цифра (0-9)
 *  - хотя бы 1 спецсимвол (!@#$%^&*()_+-=[]{}|;':\",./<>?)
 */
object PasswordValidator {

    private const val MIN_LENGTH = 8

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun validate(password: String): Result {
        if (password.length < MIN_LENGTH)
            return Result.Invalid("Password must be at least $MIN_LENGTH characters long")

        if (!password.any { it.isUpperCase() })
            return Result.Invalid("Password must contain at least one uppercase letter (A–Z)")

        if (!password.any { it.isLowerCase() })
            return Result.Invalid("Password must contain at least one lowercase letter (a–z)")

        if (!password.any { it.isDigit() })
            return Result.Invalid("Password must contain at least one digit (0–9)")

        if (!password.any { it in "!@#\$%^&*()_+-=[]{}|;':\",./<>?" })
            return Result.Invalid("Password must contain at least one special character (e.g. !@#\$%^&*)")

        return Result.Valid
    }

    /** Короткая подсказка — для hint под полем ввода */
    const val HINT = "Min 8 chars · uppercase · lowercase · digit · special character"
}


