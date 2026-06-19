import asyncio
import smtplib
from dataclasses import dataclass
from email.message import EmailMessage
from functools import lru_cache

from app.config import settings


@dataclass(frozen=True)
class DeliveredMail:
    recipient: str
    subject: str
    text: str


class MailService:
    def __init__(self) -> None:
        self.outbox: list[DeliveredMail] = []

    async def send_verification(self, email: str, token: str) -> None:
        url = f"{settings.account_url}/verify-email?token={token}"
        await self.send(email, "Verify your ODS account", f"Open this link to verify email:\n{url}")

    async def send_password_reset(self, email: str, token: str) -> None:
        url = f"{settings.account_url}/reset-password?token={token}"
        await self.send(
            email, "Reset your ODS password", f"Open this link to reset password:\n{url}"
        )

    async def send(self, recipient: str, subject: str, text: str) -> None:
        if settings.env in {"dev", "test"} and not settings.smtp_host:
            self.outbox.append(DeliveredMail(recipient=recipient, subject=subject, text=text))
            return
        if not settings.smtp_host:
            raise RuntimeError("SMTP_HOST is required outside development")
        message = EmailMessage()
        message["From"] = settings.mail_from
        message["To"] = recipient
        message["Subject"] = subject
        message.set_content(text)
        await asyncio.to_thread(self._send_sync, message)

    @staticmethod
    def _send_sync(message: EmailMessage) -> None:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=15) as smtp:
            if settings.smtp_starttls:
                smtp.starttls()
            if settings.smtp_user:
                smtp.login(settings.smtp_user, settings.smtp_password)
            smtp.send_message(message)


@lru_cache
def get_mail_service() -> MailService:
    return MailService()
