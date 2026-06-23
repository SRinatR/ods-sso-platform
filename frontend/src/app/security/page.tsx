"use client";

import QRCode from "qrcode";
import Image from "next/image";
import { FormEvent, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";
import {
  authenticateWithPasskey,
  PasskeyAttachment,
  passkeysSupported,
  registerPasskey,
} from "@/lib/passkeys";

type User = {
  role: string;
  mfa_enabled: boolean;
  authentication_method?: string;
};
type Setup = { secret: string; provisioning_uri: string; expires_in: number };
type Passkey = {
  id: string;
  label: string;
  created_at?: string;
  last_used_at?: string;
  backup_eligible: boolean;
  backup_state: boolean;
};

export default function SecurityPage() {
  const [user, setUser] = useState<User | null>(null);
  const [setup, setSetup] = useState<Setup | null>(null);
  const [qrCode, setQrCode] = useState("");
  const [code, setCode] = useState("");
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [passkeys, setPasskeys] = useState<Passkey[]>([]);
  const [passkeyLabel, setPasskeyLabel] = useState("Основное устройство");
  const [password, setPassword] = useState("");
  const [stepUpCode, setStepUpCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<User>("/api/v1/auth/me")
      .then(async (currentUser) => {
        setUser(currentUser);
        try {
          setPasskeys(await api<Passkey[]>("/api/v1/passkeys"));
        } catch (cause) {
          setError(cause instanceof Error ? cause.message : "Не удалось загрузить passkey");
        }
      })
      .catch(() => (window.location.href = loginUrl(window.location.href)));
  }, []);

  async function beginSetup() {
    setError("");
    setMessage("");
    try {
      const nextSetup = await api<Setup>("/api/v1/auth/mfa/totp/setup", { method: "POST" });
      const nextQrCode = await QRCode.toDataURL(nextSetup.provisioning_uri, {
        width: 260,
        margin: 2,
        color: { dark: "#08111f", light: "#ffffff" },
        errorCorrectionLevel: "M",
      });
      setSetup(nextSetup);
      setQrCode(nextQrCode);
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
      setCode("");
      setMessage("OTP включён. Сохраните резервные коды: повторно они не показываются.");
      setUser((current) => (current ? { ...current, mfa_enabled: true } : current));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Код отклонен");
    }
  }

  async function addPasskey(attachment: PasskeyAttachment) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const fallbackLabel =
        attachment === "platform" ? "Это устройство" : "Телефон или ключ безопасности";
      await registerPasskey(passkeyLabel.trim() || fallbackLabel, attachment);
      setPasskeys(await api<Passkey[]>("/api/v1/passkeys"));
      setMessage(
        "Passkey добавлен. Для входа можно использовать Windows Hello, Touch ID, Face ID, PIN устройства или подтверждение на телефоне.",
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Passkey не добавлен");
    } finally {
      setBusy(false);
    }
  }

  async function deletePasskey(id: string) {
    setError("");
    try {
      await api(`/api/v1/passkeys/${encodeURIComponent(id)}`, { method: "DELETE" });
      setPasskeys((current) => current.filter((item) => item.id !== id));
      setMessage("Passkey удалён");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Passkey не удалён");
    }
  }

  async function sensitiveRequest<T>(path: string): Promise<T> {
    let body: { password?: string; code?: string } = { password, code: stepUpCode };
    if (user?.authentication_method === "passkey" && passkeysSupported()) {
      await authenticateWithPasskey();
      body = {};
      setUser(await api<User>("/api/v1/auth/me"));
    } else if (!password || !/^\d{6}$/.test(stepUpCode)) {
      throw new Error("Введите пароль и текущий шестизначный TOTP-код");
    }
    return api<T>(path, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  async function regenerateBackupCodes() {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const result = await sensitiveRequest<{ backup_codes: string[] }>(
        "/api/v1/auth/mfa/backup/regenerate",
      );
      setBackupCodes(result.backup_codes);
      setPassword("");
      setStepUpCode("");
      setMessage("Новые резервные коды созданы. Старые коды больше не действуют.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Коды не обновлены");
    } finally {
      setBusy(false);
    }
  }

  async function disableOtp() {
    if (!window.confirm("Отключить OTP и отозвать остальные активные сессии?")) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await sensitiveRequest("/api/v1/auth/mfa/totp/disable");
      setUser((current) =>
        current
          ? {
              ...current,
              mfa_enabled: false,
              authentication_method:
                current.authentication_method === "passkey" ? "passkey" : "password",
            }
          : current,
      );
      setBackupCodes([]);
      setPassword("");
      setStepUpCode("");
      setMessage("OTP отключён. Остальные активные сессии отозваны.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "OTP не отключён");
    } finally {
      setBusy(false);
    }
  }

  async function copy(value: string, success: string) {
    await navigator.clipboard.writeText(value);
    setMessage(success);
  }

  function downloadBackupCodes() {
    const blob = new Blob([backupCodes.join("\n") + "\n"], { type: "text/plain;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "ods-backup-codes.txt";
    link.click();
    URL.revokeObjectURL(link.href);
  }

  return (
    <Shell
      title="Безопасность"
      subtitle="Passkey, одноразовые коды и резервный доступ"
    >
      {error && <div className="alert error">{error}</div>}
      {message && <div className="alert success">{message}</div>}

      <section className="panel narrow">
        <div className="row between">
          <div>
            <p className="eyebrow">Passkey</p>
            <h2>Вход паролем устройства, биометрией или телефоном</h2>
          </div>
          <span className={`badge ${passkeysSupported() ? "success" : "warning"}`}>
            {passkeysSupported() ? "Поддерживается" : "Браузер не поддерживает"}
          </span>
        </div>
        {passkeysSupported() && (
          <div className="stack top-gap">
            <p className="muted">
              Passkey использует защиту вашего устройства: Windows Hello, пароль или PIN
              ноутбука, Touch ID, Face ID либо подтверждение на другом телефоне. Отдельный
              пароль ODS при таком входе не передаётся.
            </p>
            <label>
              Название устройства
              <input
                maxLength={100}
                value={passkeyLabel}
                onChange={(event) => setPasskeyLabel(event.target.value)}
              />
            </label>
            <div className="grid two">
              <button
                className="button"
                onClick={() => addPasskey("platform")}
                disabled={busy}
              >
                {busy ? "Ожидаем подтверждение…" : "Этот ноутбук или телефон"}
              </button>
              <button
                className="button secondary"
                onClick={() => addPasskey("cross-platform")}
                disabled={busy}
              >
                Другой телефон или ключ
              </button>
            </div>
          </div>
        )}
        <div className="credential-list top-gap">
          {passkeys.map((passkey) => (
            <div className="credential-item" key={passkey.id}>
              <div>
                <strong>{passkey.label}</strong>
                <span>
                  Добавлен: {passkey.created_at ? new Date(passkey.created_at).toLocaleString("ru-RU") : "—"}
                </span>
              </div>
              <button className="text-button danger" onClick={() => deletePasskey(passkey.id)}>
                Удалить
              </button>
            </div>
          ))}
          {passkeys.length === 0 && <p className="muted">Passkey пока не добавлены.</p>}
        </div>
      </section>

      <section className="panel narrow">
        <div className="row between">
          <div>
            <p className="eyebrow">OTP</p>
            <h2>{user?.mfa_enabled ? "Одноразовые коды включены" : "Одноразовые коды не настроены"}</h2>
          </div>
          {!user?.mfa_enabled && !setup && (
            <button className="button secondary" onClick={beginSetup}>
              Настроить OTP
            </button>
          )}
        </div>
        {setup && (
          <form className="stack top-gap" onSubmit={enable}>
            <div className="qr-setup">
              <div className="qr-frame">
                {qrCode ? (
                  <Image
                    src={qrCode}
                    alt="QR-код для приложения-аутентификатора"
                    width={260}
                    height={260}
                    unoptimized
                  />
                ) : "QR…"}
              </div>
              <div className="stack">
                <p className="muted">
                  Отсканируйте QR-код в Google Authenticator, 1Password, Microsoft Authenticator
                  или другом TOTP-приложении.
                </p>
                <div>
                  <span className="muted">Ключ для ручного ввода</span>
                  <code className="secret">{setup.secret}</code>
                </div>
                <button
                  className="button secondary"
                  type="button"
                  onClick={() => copy(setup.secret, "Ключ скопирован")}
                >
                  Скопировать ключ
                </button>
              </div>
            </div>
            <label>
              Введите шестизначный код
              <input
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="\d{6}"
                maxLength={6}
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                required
              />
            </label>
            <button className="button">Проверить код и включить OTP</button>
          </form>
        )}
        {backupCodes.length > 0 && (
          <div className="top-gap">
            <div className="row between">
              <strong>Резервные коды</strong>
              <div className="actions">
                <button
                  className="button secondary"
                  onClick={() => copy(backupCodes.join("\n"), "Резервные коды скопированы")}
                >
                  Копировать
                </button>
                <button className="button secondary" onClick={downloadBackupCodes}>
                  Скачать
                </button>
              </div>
            </div>
            <div className="backup-grid">
              {backupCodes.map((item) => (
                <code key={item}>{item}</code>
              ))}
            </div>
          </div>
        )}
        {user?.mfa_enabled && (
          <div className="stack top-gap">
            <p className="muted">
              Для изменения OTP подтвердите личность. В passkey-сессии откроется системное
              подтверждение устройства; в обычной сессии нужны пароль и текущий TOTP-код.
            </p>
            {(user.authentication_method !== "passkey" || !passkeysSupported()) && (
              <div className="grid two">
                <label>
                  Пароль
                  <input
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                  />
                </label>
                <label>
                  Текущий TOTP-код
                  <input
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="\d{6}"
                    maxLength={6}
                    value={stepUpCode}
                    onChange={(event) =>
                      setStepUpCode(event.target.value.replace(/\D/g, "").slice(0, 6))
                    }
                  />
                </label>
              </div>
            )}
            <div className="actions">
              <button
                className="button secondary"
                type="button"
                onClick={regenerateBackupCodes}
                disabled={busy}
              >
                Обновить резервные коды
              </button>
              <button
                className="button danger"
                type="button"
                onClick={disableOtp}
                disabled={busy}
              >
                Отключить OTP
              </button>
            </div>
          </div>
        )}
      </section>
    </Shell>
  );
}
