package uz.ods.sso.identity

import org.springframework.http.HttpStatus
import uz.ods.sso.shared.AppException
import kotlin.math.min

object FullNameNormalizer {
    private val cyrillicPattern = Regex("^[\\p{IsCyrillic}\\s'’-]+$")
    private val latinPattern = Regex("^[A-Za-z\\s'’-]+$")
    private val spaces = Regex("\\s+")

    fun requireCyrillicField(raw: String?, label: String): String {
        val value = normalize(raw)
        if (value == null) {
            throw validation("$label is required")
        }
        if (!cyrillicPattern.matches(value)) {
            throw validation("$label must contain only Cyrillic letters")
        }
        return value
    }

    fun optionalCyrillicField(raw: String?, label: String): String? {
        val value = normalize(raw) ?: return null
        if (!cyrillicPattern.matches(value)) {
            throw validation("$label must contain only Cyrillic letters")
        }
        return value
    }

    fun requireLatinField(cyrillic: String, rawLatin: String?, label: String): String {
        val defaultLatin = transliterateCyrillic(cyrillic)
        val value = normalize(rawLatin)?.let(::titleLatin)
            ?: throw validation("$label is required")
        validateLatinField(value, defaultLatin, label)
        return value
    }

    fun optionalLatinField(cyrillic: String?, rawLatin: String?, label: String): String? {
        val value = normalize(rawLatin)?.let(::titleLatin)
        if (cyrillic == null && value == null) {
            return null
        }
        if (cyrillic == null || value == null) {
            throw validation("$label must be filled together with the matching Cyrillic field")
        }
        validateLatinField(value, transliterateCyrillic(cyrillic), label)
        return value
    }

    fun joinParts(vararg parts: String?): String? =
        parts.mapNotNull { normalize(it) }.takeIf(List<String>::isNotEmpty)?.joinToString(" ")

    fun transliterateCyrillic(value: String): String =
        titleLatin(
            value.map { char -> transliteration[char] ?: char.toString() }
                .joinToString("")
                .replace(spaces, " ")
                .trim(),
        )

    private fun normalize(raw: String?): String? =
        raw?.trim()?.replace(spaces, " ")?.takeIf(String::isNotBlank)

    private fun titleLatin(value: String): String =
        value.lowercase().replace(Regex("(^|[\\s'’-])([a-z])")) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }

    private fun distanceKey(value: String): String =
        value.lowercase().filter { it in 'a'..'z' }

    private fun validateLatinField(value: String, defaultLatin: String, label: String) {
        if (!latinPattern.matches(value)) {
            throw validation("$label must contain only Latin letters")
        }
        if (editDistance(distanceKey(defaultLatin), distanceKey(value)) > 3) {
            throw validation("$label can differ from automatic transliteration by no more than 3 characters")
        }
    }

    private fun editDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + if (left[i] == right[j]) 0 else 1,
                )
            }
            val next = previous
            previous = current
            current = next
        }
        return previous[right.length]
    }

    private fun validation(message: String): AppException =
        AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", message)

    private val transliteration = mapOf(
        'А' to "A", 'а' to "a",
        'Б' to "B", 'б' to "b",
        'В' to "V", 'в' to "v",
        'Г' to "G", 'г' to "g",
        'Д' to "D", 'д' to "d",
        'Е' to "E", 'е' to "e",
        'Ё' to "Yo", 'ё' to "yo",
        'Ж' to "Zh", 'ж' to "zh",
        'З' to "Z", 'з' to "z",
        'И' to "I", 'и' to "i",
        'Й' to "Y", 'й' to "y",
        'К' to "K", 'к' to "k",
        'Л' to "L", 'л' to "l",
        'М' to "M", 'м' to "m",
        'Н' to "N", 'н' to "n",
        'О' to "O", 'о' to "o",
        'П' to "P", 'п' to "p",
        'Р' to "R", 'р' to "r",
        'С' to "S", 'с' to "s",
        'Т' to "T", 'т' to "t",
        'У' to "U", 'у' to "u",
        'Ф' to "F", 'ф' to "f",
        'Х' to "Kh", 'х' to "kh",
        'Ц' to "Ts", 'ц' to "ts",
        'Ч' to "Ch", 'ч' to "ch",
        'Ш' to "Sh", 'ш' to "sh",
        'Щ' to "Shch", 'щ' to "shch",
        'Ъ' to "", 'ъ' to "",
        'Ы' to "Y", 'ы' to "y",
        'Ь' to "", 'ь' to "",
        'Э' to "E", 'э' to "e",
        'Ю' to "Yu", 'ю' to "yu",
        'Я' to "Ya", 'я' to "ya",
        'Қ' to "Q", 'қ' to "q",
        'Ғ' to "G", 'ғ' to "g",
        'Ҳ' to "H", 'ҳ' to "h",
        'Ў' to "O", 'ў' to "o",
        'Ң' to "Ng", 'ң' to "ng",
        'І' to "I", 'і' to "i",
    )
}
