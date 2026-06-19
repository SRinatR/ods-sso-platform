"use client";

import { FormEvent, useState } from "react";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    await api("/api/v1/auth/forgot-password", {
      method: "POST",
      body: JSON.stringify({ email }),
    });
    setMessage("Если аккаунт существует, письмо уже отправлено.");
  }

  return (
    <AuthCard title="Сброс пароля" subtitle="Отправим безопасную одноразовую ссылку">
      {message ? (
        <div className="alert success">{message}</div>
      ) : (
        <form onSubmit={submit} className="stack">
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
    </AuthCard>
  );
}

