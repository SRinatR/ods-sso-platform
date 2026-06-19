"use client";

import { FormEvent, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";

type User = { role: string; mfa_enabled: boolean };
type Setup = { secret: string; provisioning_uri: string; expires_in: number };

export default function SecurityPage() {
  const [user, setUser] = useState<User | null>(null);
  const [setup, setSetup] = useState<Setup | null>(null);
  const [code, setCode] = useState("");
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<User>("/api/v1/auth/me").then(setUser).catch(() => (window.location.href = "/login"));
  }, []);

  async function beginSetup() {
    setError("");
    try {
      setSetup(await api<Setup>("/api/v1/auth/mfa/totp/setup", { method: "POST" }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Настройка не запущена");
    }
  }

  async function enable(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      const result = await api<{ backup_codes: string[] }>("/api/v1/auth/mfa/totp/enable", {
        method: "POST",
        body: JSON.stringify({ code }),
      });
      setBackupCodes(result.backup_codes);
      setSetup(null);
      setMessage("MFA включена. Сохраните резервные коды: повторно они не показываются.");
      setUser((current) => (current ? { ...current, mfa_enabled: true } : current));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Код отклонен");
    }
  }

  return (
    <Shell
      title="Безопасность и MFA"
      subtitle="TOTP и одноразовые резервные коды"
      admin={user?.role === "admin" || user?.role === "security_admin"}
    >
      {error && <div className="alert error">{error}</div>}
      {message && <div className="alert success">{message}</div>}
      <section className="panel narrow">
        <div className="row between">
          <div>
            <p className="eyebrow">Второй фактор</p>
            <h2>{user?.mfa_enabled ? "TOTP включен" : "TOTP не настроен"}</h2>
          </div>
          {!user?.mfa_enabled && (
            <button className="button" onClick={beginSetup}>
              Настроить
            </button>
          )}
        </div>
        {setup && (
          <form className="stack top-gap" onSubmit={enable}>
            <p>
              Добавьте ключ в приложение-аутентификатор:
              <code className="secret">{setup.secret}</code>
            </p>
            <label>
              Проверочный код
              <input
                inputMode="numeric"
                pattern="\d{6}"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                required
              />
            </label>
            <button className="button">Подтвердить и включить</button>
          </form>
        )}
        {backupCodes.length > 0 && (
          <div className="backup-grid">
            {backupCodes.map((item) => (
              <code key={item}>{item}</code>
            ))}
          </div>
        )}
      </section>
    </Shell>
  );
}

