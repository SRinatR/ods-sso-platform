"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";

type CurrentUser = {
  role: string;
  mfa_enabled: boolean;
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

  useEffect(() => {
    api<CurrentUser>("/api/v1/auth/me")
      .then((user) => {
        setCurrentUser(user);
        setAccessChecked(true);
      })
      .catch(() => {
        window.location.href = "/login?return_to=/admin";
      });
  }, []);

  async function load() {
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
  }

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

  if (!accessChecked) {
    return (
      <Shell title="Администрирование" subtitle="Проверяем права доступа">
        <section className="panel narrow">Загрузка…</section>
      </Shell>
    );
  }

  if (!currentUser || !["admin", "security_admin"].includes(currentUser.role)) {
    return (
      <Shell title="Доступ запрещён" subtitle="Административная роль отсутствует">
        <section className="panel narrow">
          <p>Эта учётная запись не имеет доступа к административной консоли.</p>
          <Link href="/dashboard" className="button">
            Вернуться в личный кабинет
          </Link>
        </section>
      </Shell>
    );
  }

  if (!currentUser.mfa_enabled) {
    return (
      <Shell title="Настройте MFA" subtitle="Для админки обязательна двухфакторная защита" admin>
        <section className="panel narrow">
          <p>Сначала подключите TOTP в разделе безопасности, затем вернитесь в админку.</p>
          <Link href="/security" className="button">
            Настроить MFA
          </Link>
        </section>
      </Shell>
    );
  }

  if (!ready) {
    return (
      <Shell title="Администрирование" subtitle="Требуется свежая MFA step-up проверка" admin>
        <section className="panel narrow">
          {error && <div className="alert error">{error}</div>}
          <form className="stack" onSubmit={stepUp}>
            <label>
              Пароль
              <input
                type="password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            <label>
              TOTP-код
              <input
                inputMode="numeric"
                required
                value={code}
                onChange={(event) => setCode(event.target.value)}
              />
            </label>
            <button className="button">Подтвердить доступ</button>
          </form>
        </section>
      </Shell>
    );
  }

  return (
    <Shell title="Admin Console" subtitle="Users, clients, sessions, audit and policies" admin>
      {dashboard && (
        <div className="metric-grid">
          {Object.entries(dashboard).map(([key, value]) => (
            <section className="metric" key={key}>
              <strong>{value}</strong>
              <span>{key.replaceAll("_", " ")}</span>
            </section>
          ))}
        </div>
      )}

      <section className="panel">
        <h2>Users</h2>
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

      <section className="panel">
        <h2>OAuth Clients</h2>
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

      <section className="panel">
        <h2>Sessions</h2>
        <p className="muted">{sessions.length} recent session records</p>
        <pre className="data-preview">{JSON.stringify(sessions.slice(0, 20), null, 2)}</pre>
      </section>

      <section className="panel">
        <h2>Audit Logs</h2>
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

      <section className="panel">
        <h2>Security Policies</h2>
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
