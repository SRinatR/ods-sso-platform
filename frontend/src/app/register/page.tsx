"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";

export default function RegisterPage() {
  const [form, setForm] = useState({ name: "", email: "", password: "" });
  const [accepted, setAccepted] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await api("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({ ...form, accept_terms: accepted }),
      });
      setMessage("Аккаунт создан. Откройте письмо и подтвердите email.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Регистрация не выполнена");
    }
  }

  return (
    <AuthCard title="Регистрация" subtitle="Создайте единую учетную запись">
      {message ? (
        <div className="alert success">{message}</div>
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
        <Link href="/login">Вернуться ко входу</Link>
      </div>
    </AuthCard>
  );
}

