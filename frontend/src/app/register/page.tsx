"use client";

import Link from "next/link";
import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";
import { onAuth } from "@/lib/domains";

type RegistrationResponse = {
  message: string;
  verification_required: boolean;
};

function RegisterForm() {
  const partner = useSearchParams().get("kind") === "partner";
  const [form, setForm] = useState({ name: "", email: "", password: "" });
  const [accepted, setAccepted] = useState(false);
  const [message, setMessage] = useState("");
  const [verificationRequired, setVerificationRequired] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      const result = await api<RegistrationResponse>("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({ ...form, accept_terms: accepted }),
      });
      setVerificationRequired(result.verification_required);
      setMessage(
        result.verification_required
          ? "Аккаунт создан. Откройте письмо и подтвердите email."
          : "Аккаунт создан. Теперь можно войти.",
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Регистрация не выполнена");
    }
  }

  return (
    <AuthCard
      title={partner ? "Регистрация контрагента" : "Регистрация"}
      subtitle={
        partner
          ? "Сначала создайте личную учетную запись, затем организацию и её кабинет"
          : "Создайте единую учетную запись"
      }
    >
      {message ? (
        <>
          <div className="alert success">{message}</div>
          {verificationRequired && (
            <button
              className="button secondary link-button"
              onClick={async () => {
                await api("/api/v1/auth/resend-verification", {
                  method: "POST",
                  body: JSON.stringify({ email: form.email }),
                });
                setMessage("Письмо отправлено повторно. Проверьте также папку «Спам».");
              }}
            >
              Отправить письмо ещё раз
            </button>
          )}
        </>
      ) : (
        <form onSubmit={submit} className="stack">
          {error && <div className="alert error">{error}</div>}
          <label>
            Имя
            <input
              required
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </label>
          <label>
            Email
            <input
              type="email"
              required
              value={form.email}
              onChange={(event) => setForm({ ...form, email: event.target.value })}
            />
          </label>
          <label>
            Пароль
            <input
              type="password"
              minLength={12}
              maxLength={128}
              required
              value={form.password}
              onChange={(event) => setForm({ ...form, password: event.target.value })}
            />
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={accepted}
              onChange={(event) => setAccepted(event.target.checked)}
            />
            Принимаю условия использования и политику конфиденциальности
          </label>
          <button className="button">Создать аккаунт</button>
        </form>
      )}
      <div className="auth-links">
        <Link href={onAuth(`/login${partner ? "?return_to=/partner" : ""}`)}>
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
