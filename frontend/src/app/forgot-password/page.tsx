"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";
import { onAuth } from "@/lib/domains";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await api("/api/v1/auth/forgot-password", {
        method: "POST",
        body: JSON.stringify({ email }),
      });
      setMessage("Если аккаунт существует, письмо уже отправлено.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось отправить письмо");
    }
  }

  return (
    <AuthCard title="Сброс пароля" subtitle="Отправим безопасную одноразовую ссылку">
      {message ? (
        <div className="alert success">{message}</div>
      ) : (
        <form onSubmit={submit} className="stack">
          {error && <div className="alert error">{error}</div>}
          <label>
            Email
            <input
              type="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <button className="button">Отправить ссылку</button>
        </form>
      )}
      <div className="auth-links">
        <Link href={onAuth("/login")}>Вернуться ко входу</Link>
      </div>
    </AuthCard>
  );
}
