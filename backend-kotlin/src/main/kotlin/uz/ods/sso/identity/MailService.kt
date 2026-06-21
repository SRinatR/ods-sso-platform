package uz.ods.sso.identity

import org.springframework.boot.mail.autoconfigure.MailProperties
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
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

    fun sendVerification(email: String, token: String) = send(
        email,
        "Verify your ODS account",
        "Open this link to verify email:\n${properties.accountUrl}/verify-email?token=$token",
    )

    fun sendPasswordReset(email: String, token: String) = send(
        email,
        "Reset your ODS password",
        "Open this link to reset password:\n${properties.accountUrl}/reset-password?token=$token",
    )

    private fun send(recipient: String, subject: String, text: String) {
        if (mailProperties.host.orEmpty().isBlank() && !properties.productionLike) {
            outbox += DeliveredMail(recipient, subject, text)
            return
        }
        check(mailProperties.host.orEmpty().isNotBlank()) { "SMTP_HOST is required outside development" }
        sender.send(
            SimpleMailMessage().apply {
                from = mailProperties.username.orEmpty().ifBlank { "no-reply@ods.uz" }
                setTo(recipient)
                setSubject(subject)
                setText(text)
            },
        )
    }
}
