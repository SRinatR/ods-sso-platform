"use client";

import Link from "next/link";
import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";
import { onAuth } from "@/lib/domains";

function ResetForm() {
  const token = useSearchParams().get("token") || "";
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await api("/api/v1/auth/reset-password", {
        method: "POST",
        body: JSON.stringify({ token, new_password: password }),
      });
      setMessage("Пароль изменен. Все старые сессии завершены.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Сброс не выполнен");
    }
  }

  return (
    <AuthCard title="Новый пароль" subtitle="После изменения потребуется войти заново">
      {message ? (
        <>
          <div className="alert success">{message}</div>
          <Link className="button link-button" href={onAuth("/login")}>
            Войти с новым паролем
          </Link>
        </>
      ) : (
        <form onSubmit={submit} className="stack">
          {error && <div className="alert error">{error}</div>}
          <label>
            Новый пароль
            <input
              type="password"
              minLength={12}
              maxLength={128}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="button">Изменить пароль</button>
        </form>
      )}
    </AuthCard>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <ResetForm />
    </Suspense>
  );
}
