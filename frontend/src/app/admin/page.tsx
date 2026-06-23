"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { ACCOUNTS_URL, ADMIN_URL, loginUrl } from "@/lib/domains";
import { authenticateWithPasskey, passkeysSupported } from "@/lib/passkeys";

type CurrentUser = {
  role: string;
  mfa_enabled: boolean;
  authentication_method?: string;
};
type Dashboard = {
  users_total: number;
  users_active: number;
  active_sessions: number;
  oauth_clients: number;
  failed_logins_24h: number;
  audit_events_24h: number;
};
type User = {
  id: string;
  email: string;
  name?: string;
  status: string;
  role: string;
  mfa_enabled: boolean;
};
type Client = {
  client_id: string;
  name: string;
  redirect_uris: string[];
  allowed_scopes: string[];
  enabled: boolean;
};
type Audit = {
  id: string;
  event_type: string;
  actor_id?: string;
  subject_id?: string;
  request_id: string;
  created_at: string;
  details: Record<string, unknown>;
};
type Policy = {
  key: string;
  value: Record<string, unknown>;
  updated_at: string;
};

export default function AdminPage() {
  const [accessChecked, setAccessChecked] = useState(false);
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [ready, setReady] = useState(false);
  const [checkingAssurance, setCheckingAssurance] = useState(true);
  const [authenticating, setAuthenticating] = useState(false);
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [users, setUsers] = useState<User[]>([]);
  const [clients, setClients] = useState<Client[]>([]);
  const [sessions, setSessions] = useState<Array<Record<string, unknown>>>([]);
  const [audit, setAudit] = useState<Audit[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [secret, setSecret] = useState("");
  const [clientForm, setClientForm] = useState({
    name: "",
    redirectUri: "",
    isPublic: false,
  });

  const load = useCallback(async () => {
    const [summary, userList, clientList, sessionList, auditList, policyList] =
      await Promise.all([
        api<Dashboard>("/api/v1/admin/dashboard"),
        api<User[]>("/api/v1/admin/users"),
        api<Client[]>("/api/v1/admin/oauth-clients"),
        api<Array<Record<string, unknown>>>("/api/v1/admin/sessions"),
        api<Audit[]>("/api/v1/admin/audit"),
        api<Policy[]>("/api/v1/admin/security-policies"),
      ]);
    setDashboard(summary);
    setUsers(userList);
    setClients(clientList);
    setSessions(sessionList);
    setAudit(auditList);
    setPolicies(policyList);
  }, []);

  useEffect(() => {
    api<CurrentUser>("/api/v1/auth/me")
      .then(async (user) => {
        setCurrentUser(user);
        if (
          ["admin", "security_admin"].includes(user.role) &&
          (user.mfa_enabled || user.authentication_method === "passkey")
        ) {
          try {
            await load();
            setReady(true);
          } catch {
            setReady(false);
          }
        }
        setAccessChecked(true);
        setCheckingAssurance(false);
      })
      .catch(() => {
        window.location.href = loginUrl(`${ADMIN_URL}/admin`);
      });
  }, [load]);

  async function stepUp(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await api("/api/v1/auth/step-up", {
        method: "POST",
        body: JSON.stringify({ password, code }),
      });
      await load();
      setReady(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Проверка не выполнена");
    }
  }

  async function confirmPasskey() {
    setError("");
    setAuthenticating(true);
    try {
      await authenticateWithPasskey();
      const user = await api<CurrentUser>("/api/v1/auth/me");
      setCurrentUser(user);
      await load();
      setReady(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Passkey не принят");
    } finally {
      setAuthenticating(false);
    }
  }

  async function updateUser(user: User, changes: Partial<User>) {
    await api(`/api/v1/admin/users/${user.id}`, {
      method: "PATCH",
      body: JSON.stringify(changes),
    });
    await load();
  }

  async function createClient(event: FormEvent) {
    event.preventDefault();
    const created = await api<Client & { client_secret?: string }>("/api/v1/admin/oauth-clients", {
      method: "POST",
      body: JSON.stringify({
        name: clientForm.name,
        redirect_uris: [clientForm.redirectUri],
        allowed_scopes: ["openid", "profile", "email", "offline_access"],
        is_public: clientForm.isPublic,
        token_endpoint_auth_method: clientForm.isPublic ? "none" : "client_secret_basic",
      }),
    });
    setSecret(created.client_secret || "");
    setClientForm({ name: "", redirectUri: "", isPublic: false });
    await load();
  }

  if (!accessChecked || checkingAssurance) {
    return (
      <Shell title="Администрирование" subtitle="Проверяем права доступа" product="admin">
        <section className="panel narrow">Загрузка…</section>
      </Shell>
    );
  }

  if (!currentUser || !["admin", "security_admin"].includes(currentUser.role)) {
    return (
      <Shell title="Доступ запрещён" subtitle="Административная роль отсутствует" product="admin">
        <section className="panel narrow">
          <p>Эта учётная запись не имеет доступа к административной консоли.</p>
          <Link href="/dashboard" className="button">
            Вернуться в личный кабинет
          </Link>
        </section>
      </Shell>
    );
  }

  const passkeySession = currentUser.authentication_method === "passkey";

  if (!currentUser.mfa_enabled && !passkeySession) {
    return (
      <Shell
        title="Защитите административный доступ"
        subtitle="Сессия активна, но для админки требуется passkey или одноразовые коды"
        product="admin"
      >
        <section className="panel narrow">
          <p>
            Добавьте passkey ноутбука или телефона — можно использовать Windows Hello,
            биометрию или PIN устройства. Альтернативный вариант — TOTP из приложения.
          </p>
          <Link href={`${ACCOUNTS_URL}/security`} className="button">
            Открыть настройки безопасности
          </Link>
        </section>
      </Shell>
    );
  }

  if (!ready) {
    return (
      <Shell title="Администрирование" subtitle="Требуется свежая step-up проверка" product="admin">
        <section className="panel narrow">
          {error && <div className="alert error">{error}</div>}
          {passkeysSupported() && (
            <div className="stack">
              <p className="muted">
                Подтвердите действие отпечатком, Face ID или PIN устройства. Текущая
                сессия будет усилена без создания дубликата.
              </p>
              <button
                className="button passkey-button"
                type="button"
                onClick={confirmPasskey}
                disabled={authenticating}
              >
                {authenticating ? "Проверка passkey…" : "Подтвердить passkey"}
              </button>
              {currentUser.mfa_enabled && (
                <div className="auth-divider"><span>или пароль и TOTP</span></div>
              )}
            </div>
          )}
          {currentUser.mfa_enabled && (
            <form className="stack" onSubmit={stepUp}>
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
              <label>
                TOTP-код
                <input
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="\d{6}"
                  maxLength={6}
                  required
                  value={code}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                />
              </label>
              <button className="button">Подтвердить доступ</button>
            </form>
          )}
          {!currentUser.mfa_enabled && !passkeysSupported() && (
            <p className="muted">
              Этот браузер не поддерживает passkey. Войдите с устройства с WebAuthn или
              предварительно подключите TOTP.
            </p>
          )}
        </section>
      </Shell>
    );
  }

  return (
    <Shell
      title="Административная консоль"
      subtitle="Пользователи, OAuth-клиенты, сессии, аудит и политики"
      product="admin"
    >
      {dashboard && (
        <div className="metric-grid" id="overview">
          {Object.entries(dashboard).map(([key, value]) => (
            <section className="metric" key={key}>
              <strong>{value}</strong>
              <span>{key.replaceAll("_", " ")}</span>
            </section>
          ))}
        </div>
      )}

      <section className="panel" id="users">
        <h2>Пользователи</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Email</th>
                <th>Status</th>
                <th>Role</th>
                <th>MFA</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id}>
                  <td>{user.email}</td>
                  <td>{user.status}</td>
                  <td>{user.role}</td>
                  <td>{user.mfa_enabled ? "on" : "off"}</td>
                  <td className="actions">
                    <button
                      className="text-button"
                      onClick={() =>
                        updateUser(user, {
                          status: user.status === "active" ? "suspended" : "active",
                        })
                      }
                    >
                      {user.status === "active" ? "Suspend" : "Activate"}
                    </button>
                    <button
                      className="text-button"
                      onClick={() =>
                        updateUser(user, { role: user.role === "admin" ? "user" : "admin" })
                      }
                    >
                      Toggle admin
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" id="oauth-clients">
        <h2>OAuth-клиенты</h2>
        {secret && (
          <div className="alert warning">
            Сохраните client secret сейчас: <code>{secret}</code>
          </div>
        )}
        <form className="inline-form" onSubmit={createClient}>
          <label>
            Application name
            <input
              required
              value={clientForm.name}
              onChange={(event) => setClientForm({ ...clientForm, name: event.target.value })}
            />
          </label>
          <label>
            Redirect URI
            <input
              required
              type="url"
              value={clientForm.redirectUri}
              onChange={(event) =>
                setClientForm({ ...clientForm, redirectUri: event.target.value })
              }
            />
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={clientForm.isPublic}
              onChange={(event) =>
                setClientForm({ ...clientForm, isPublic: event.target.checked })
              }
            />
            Public
          </label>
          <button className="button">Create</button>
        </form>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Client</th>
                <th>Redirect URIs</th>
                <th>Scopes</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {clients.map((client) => (
                <tr key={client.client_id}>
                  <td>
                    <b>{client.name}</b>
                    <br />
                    <code>{client.client_id}</code>
                  </td>
                  <td>{client.redirect_uris.join(", ")}</td>
                  <td>{client.allowed_scopes.join(" ")}</td>
                  <td>{client.enabled ? "enabled" : "disabled"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" id="sessions">
        <h2>Сессии</h2>
        <p className="muted">{sessions.length} recent session records</p>
        <pre className="data-preview">{JSON.stringify(sessions.slice(0, 20), null, 2)}</pre>
      </section>

      <section className="panel" id="audit">
        <h2>Журнал аудита</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Event</th>
                <th>Actor</th>
                <th>Request</th>
              </tr>
            </thead>
            <tbody>
              {audit.slice(0, 100).map((item) => (
                <tr key={item.id}>
                  <td>{new Date(item.created_at).toLocaleString()}</td>
                  <td>{item.event_type}</td>
                  <td>{item.actor_id || "system"}</td>
                  <td>
                    <code>{item.request_id}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" id="policies">
        <h2>Политики безопасности</h2>
        <div className="grid two">
          {policies.map((policy) => (
            <article className="policy" key={policy.key}>
              <h3>{policy.key}</h3>
              <pre>{JSON.stringify(policy.value, null, 2)}</pre>
            </article>
          ))}
        </div>
      </section>
    </Shell>
  );
}
