"use client";

import Link from "next/link";
import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";
import { onAuth, onPartners } from "@/lib/domains";

type RegistrationResponse = {
  message: string;
  email: string;
};

function RegisterForm() {
  const partner = useSearchParams().get("kind") === "partner";
  const [email, setEmail] = useState("");
  const [confirmedEmail, setConfirmedEmail] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"email" | "code">("email");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);

  async function requestCode(event?: FormEvent) {
    event?.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const result = await api<RegistrationResponse>("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({ email }),
      });
      setConfirmedEmail(result.email);
      setMessage("Мы отправили код подтверждения на указанную почту.");
      setStep("code");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось отправить код");
    } finally {
      setSubmitting(false);
    }
  }

  async function verifyCode(event: FormEvent) {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await api("/api/v1/auth/verify-email", {
        method: "POST",
        body: JSON.stringify({ email: confirmedEmail, code }),
      });
      window.location.href = onAuth("/profile");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Код не подтвержден");
    } finally {
      setSubmitting(false);
    }
  }

  async function resendCode() {
    setError("");
    setResending(true);
    try {
      await api<RegistrationResponse>("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({ email: confirmedEmail || email }),
      });
      setMessage("Мы отправили новый код подтверждения.");
      setCode("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось отправить код повторно");
    } finally {
      setResending(false);
    }
  }

  return (
    <AuthCard
      title={partner ? "Регистрация контрагента" : "Регистрация"}
      subtitle={
        partner
          ? "Подтвердите email, затем заполните обязательные данные профиля"
          : "Для создания аккаунта достаточно email и кода из письма"
      }
    >
      {step === "email" && (
        <form onSubmit={requestCode} className="stack">
          {error && <div className="alert error">{error}</div>}
          <label>
            Email
            <input
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <p className="legal-consent">
            Нажимая «Получить код», вы принимаете{" "}
            <Link href="/privacy">условия использования и политику конфиденциальности</Link>.
          </p>
          <button className="button" disabled={submitting}>
            {submitting ? "Отправляем код…" : "Получить код"}
          </button>
        </form>
      )}

      {step === "code" && (
        <form onSubmit={verifyCode} className="stack">
          {message && <div className="alert success">{message}</div>}
          {error && <div className="alert error">{error}</div>}
          <label>
            Код из письма
            <input
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="\d{6}"
              maxLength={6}
              required
              value={code}
              onChange={(event) =>
                setCode(event.target.value.replace(/\D/g, "").slice(0, 6))
              }
            />
            <span className="field-hint">
              Отправили код на {confirmedEmail}. Проверьте папки «Входящие» и «Спам».
            </span>
          </label>
          <button className="button" disabled={submitting || code.length !== 6}>
            {submitting ? "Проверяем…" : "Подтвердить и перейти к профилю"}
          </button>
          <button
            type="button"
            className="button secondary link-button"
            disabled={resending}
            onClick={resendCode}
          >
            {resending ? "Отправляем…" : "Отправить код еще раз"}
          </button>
          <button
            type="button"
            className="button secondary link-button"
            onClick={() => {
              setStep("email");
              setCode("");
              setMessage("");
              setError("");
            }}
          >
            Изменить email
          </button>
        </form>
      )}

      <div className="auth-links">
        <Link
          href={onAuth(
            partner ? `/login?return_to=${encodeURIComponent(onPartners("/"))}` : "/login",
          )}
        >
          Вернуться ко входу
        </Link>
        {!partner && <Link href={onAuth("/register?kind=partner")}>Для контрагентов</Link>}
      </div>
    </AuthCard>
  );
}

export default function RegisterPage() {
  return (
    <Suspense>
      <RegisterForm />
    </Suspense>
  );
}
