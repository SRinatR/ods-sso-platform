"use client";

import Link from "next/link";
import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";
import { ACCOUNTS_URL, isTrustedReturnUrl, onAuth } from "@/lib/domains";
import { authenticateWithPasskey, passkeysSupported } from "@/lib/passkeys";

type LoginResult = {
  user_id?: string;
  email?: string;
  mfa_required: boolean;
  challenge_token?: string;
};

function LoginForm() {
  const params = useSearchParams();
  const returnTo = safeReturnTo(params.get("return_to"));
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

  async function submitPasskey() {
    setError("");
    setLoading(true);
    try {
      await authenticateWithPasskey();
      window.location.href = returnTo;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось войти по passkey");
    } finally {
      setLoading(false);
    }
  }

  return (
    <AuthCard title="Вход" subtitle="Безопасный доступ ко всем сервисам ODS">
      {error && <div className="alert error">{error}</div>}
      {!challenge ? (
        <div className="stack">
          {passkeysSupported() && (
            <>
              <button className="button passkey-button" onClick={submitPasskey} disabled={loading}>
                Войти по passkey
              </button>
              <div className="auth-divider"><span>или email и пароль</span></div>
            </>
          )}
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
        </div>
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
              inputMode={method === "totp" ? "numeric" : "text"}
              pattern={method === "totp" ? "\\d{6}" : undefined}
              maxLength={method === "totp" ? 6 : 64}
              autoFocus
              required
              value={code}
              onChange={(event) =>
                setCode(
                  method === "totp"
                    ? event.target.value.replace(/\D/g, "").slice(0, 6)
                    : event.target.value.trim(),
                )
              }
            />
          </label>
          <button className="button" disabled={loading}>
            Подтвердить
          </button>
          <button
            className="button secondary"
            type="button"
            onClick={() => {
              setChallenge("");
              setCode("");
              setError("");
            }}
          >
            Начать вход заново
          </button>
        </form>
      )}
      <div className="auth-links">
        <Link href={onAuth("/register")}>Создать аккаунт</Link>
        <Link href={onAuth("/forgot-password")}>Забыли пароль?</Link>
      </div>
    </AuthCard>
  );
}

function safeReturnTo(value: string | null): string {
  if (!value) return `${ACCOUNTS_URL}/dashboard`;
  return isTrustedReturnUrl(value) ? value : `${ACCOUNTS_URL}/dashboard`;
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
