package uz.ods.sso.identity

import jakarta.mail.internet.InternetAddress
import org.springframework.boot.mail.autoconfigure.MailProperties
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

data class DeliveredMail(val recipient: String, val subject: String, val text: String)

@Service
class MailService(
    private val sender: JavaMailSender,
    private val mailProperties: MailProperties,
    private val properties: OdsProperties,
) {
    val outbox = CopyOnWriteArrayList<DeliveredMail>()
    val available: Boolean get() = mailProperties.host.orEmpty().isNotBlank() || !properties.productionLike

    fun sendVerification(email: String, code: String) = send(
        email,
        "Код подтверждения ODS",
        "Ваш код подтверждения ODS: $code\nВведите его на странице регистрации. Код действует ограниченное время.",
        "Код подтверждения",
        "Введите этот код на странице регистрации: $code",
        "${properties.accountUrl}/register",
        "Вернуться к регистрации",
    )

    fun sendPasswordReset(email: String, token: String) = send(
        email,
        "Сброс пароля ODS",
        "Откройте одноразовую ссылку, чтобы задать новый пароль:\n${properties.accountUrl}/reset-password?token=$token",
        "Сбросить пароль",
        "Ссылка действует ограниченное время и может быть использована только один раз.",
        "${properties.accountUrl}/reset-password?token=$token",
    )

    fun trySendPasswordChanged(email: String) {
        runCatching {
            send(
                email,
                "Пароль ODS изменён",
                "Пароль вашей учетной записи ODS был изменён. Если это сделали не вы, немедленно обратитесь в поддержку.",
                "Пароль изменён",
                "Все ранее активные сессии завершены. Если это были не вы, свяжитесь с поддержкой ODS.",
                properties.accountUrl,
                "Открыть кабинет",
            )
        }
    }

    private fun send(
        recipient: String,
        subject: String,
        text: String,
        heading: String,
        description: String,
        actionUrl: String,
        actionLabel: String = heading,
    ) {
        if (mailProperties.host.orEmpty().isBlank() && !properties.productionLike) {
            outbox += DeliveredMail(recipient, subject, text)
            return
        }
        check(mailProperties.host.orEmpty().isNotBlank()) { "SMTP_HOST is required outside development" }
        val message = sender.createMimeMessage()
        MimeMessageHelper(message, true, StandardCharsets.UTF_8.name()).apply {
            setFrom(InternetAddress(properties.mailFrom))
            setTo(recipient)
            setSubject(subject)
            properties.mailReplyTo.trim().takeIf(String::isNotEmpty)?.let(::setReplyTo)
            setText(text, htmlTemplate(heading, description, actionUrl, actionLabel))
        }
        sender.send(message)
    }

    private fun htmlTemplate(heading: String, description: String, actionUrl: String, actionLabel: String) = """
        <!doctype html>
        <html lang="ru">
          <body style="margin:0;background:#08111f;color:#edf5ff;font-family:Arial,sans-serif">
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="padding:32px 16px">
              <tr><td align="center">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0"
                       style="max-width:560px;background:#111d2e;border:1px solid #263a53;border-radius:18px;padding:32px">
                  <tr><td style="font-size:18px;font-weight:700;padding-bottom:28px">ODS Identity</td></tr>
                  <tr><td><h1 style="font-size:28px;line-height:1.2;margin:0 0 16px">$heading</h1></td></tr>
                  <tr><td style="color:#b7c8dc;font-size:16px;line-height:1.6;padding-bottom:24px">$description</td></tr>
                  <tr><td>
                    <a href="$actionUrl"
                       style="display:inline-block;background:#4f8cff;color:#fff;text-decoration:none;font-weight:700;padding:13px 20px;border-radius:10px">
                      $actionLabel
                    </a>
                  </td></tr>
                  <tr><td style="color:#7f95ae;font-size:12px;line-height:1.5;padding-top:28px">
                    Не пересылайте это письмо. Если вы не запрашивали действие, просто проигнорируйте его.
                  </td></tr>
                </table>
              </td></tr>
            </table>
          </body>
        </html>
    """.trimIndent()
}
