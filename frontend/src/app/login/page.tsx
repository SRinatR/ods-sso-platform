"use client";

import Link from "next/link";
import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";

type LoginResult = {
  user_id?: string;
  email?: string;
  mfa_required: boolean;
  challenge_token?: string;
};

function LoginForm() {
  const params = useSearchParams();
  const returnTo = params.get("return_to") || "/dashboard";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [challenge, setChallenge] = useState("");
  const [code, setCode] = useState("");
  const [method, setMethod] = useState<"totp" | "backup">("totp");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function submitCredentials(event: FormEvent) {
    event.preventDefault();
    setError("");
    setLoading(true);
    try {
      const result = await api<LoginResult>("/api/v1/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      if (result.mfa_required && result.challenge_token) {
        setChallenge(result.challenge_token);
      } else {
        window.location.href = returnTo;
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось войти");
    } finally {
      setLoading(false);
    }
  }

  async function submitMfa(event: FormEvent) {
    event.preventDefault();
    setError("");
    setLoading(true);
    try {
      await api("/api/v1/auth/mfa/verify", {
        method: "POST",
        body: JSON.stringify({ challenge_token: challenge, code, method }),
      });
      window.location.href = returnTo;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Второй фактор отклонен");
    } finally {
      setLoading(false);
    }
  }

  return (
    <AuthCard title="Вход" subtitle="Безопасный доступ ко всем сервисам ODS">
      {error && <div className="alert error">{error}</div>}
      {!challenge ? (
        <form onSubmit={submitCredentials} className="stack">
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
          <label>
            Пароль
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="button" disabled={loading}>
            {loading ? "Проверка…" : "Войти"}
          </button>
        </form>
      ) : (
        <form onSubmit={submitMfa} className="stack">
          <div className="segmented">
            <button type="button" onClick={() => setMethod("totp")} data-active={method === "totp"}>
              TOTP
            </button>
            <button
              type="button"
              onClick={() => setMethod("backup")}
              data-active={method === "backup"}
            >
              Backup code
            </button>
          </div>
          <label>
            {method === "totp" ? "Код из приложения" : "Резервный код"}
            <input
              autoComplete="one-time-code"
              required
              value={code}
              onChange={(event) => setCode(event.target.value)}
            />
          </label>
          <button className="button" disabled={loading}>
            Подтвердить
          </button>
        </form>
      )}
      <div className="auth-links">
        <Link href="/register">Создать аккаунт</Link>
        <Link href="/forgot-password">Забыли пароль?</Link>
      </div>
    </AuthCard>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}

