"use client";

import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";

type User = { role: string };
type Session = {
  id: string;
  ip_address?: string;
  user_agent?: string;
  created_at: string;
  last_seen_at: string;
  current: boolean;
  mfa_completed: boolean;
};
type Login = {
  id: string;
  success: boolean;
  failure_reason?: string;
  ip_address?: string;
  user_agent?: string;
  created_at: string;
};

export default function SessionsPage() {
  const [user, setUser] = useState<User | null>(null);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [history, setHistory] = useState<Login[]>([]);

  async function load() {
    const [me, active, logins] = await Promise.all([
      api<User>("/api/v1/auth/me"),
      api<Session[]>("/api/v1/account/sessions"),
      api<Login[]>("/api/v1/account/login-history"),
    ]);
    setUser(me);
    setSessions(active);
    setHistory(logins);
  }

  useEffect(() => {
    Promise.all([
      api<User>("/api/v1/auth/me"),
      api<Session[]>("/api/v1/account/sessions"),
      api<Login[]>("/api/v1/account/login-history"),
    ])
      .then(([me, active, logins]) => {
        setUser(me);
        setSessions(active);
        setHistory(logins);
      })
      .catch(() => window.location.assign("/login"));
  }, []);

  async function revoke(id: string) {
    await api(`/api/v1/account/sessions/${id}`, { method: "DELETE" });
    const current = sessions.find((item) => item.id === id)?.current;
    if (current) {
      window.location.assign("/login");
    } else {
      await load();
    }
  }

  return (
    <Shell
      title="Сессии и история входов"
      subtitle="Устройства, IP-адреса и результаты входа"
      admin={user?.role === "admin" || user?.role === "security_admin"}
    >
      <section className="panel">
        <h2>Активные сессии</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Устройство</th>
                <th>IP</th>
                <th>Последняя активность</th>
                <th>MFA</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {sessions.map((item) => (
                <tr key={item.id}>
                  <td>{item.user_agent || "Неизвестно"} {item.current && <b>(текущая)</b>}</td>
                  <td>{item.ip_address || "—"}</td>
                  <td>{new Date(item.last_seen_at).toLocaleString()}</td>
                  <td>{item.mfa_completed ? "Да" : "Нет"}</td>
                  <td>
                    <button className="text-button danger" onClick={() => revoke(item.id)}>
                      Завершить
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="panel">
        <h2>История входов</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Время</th>
                <th>Результат</th>
                <th>IP</th>
                <th>Устройство</th>
              </tr>
            </thead>
            <tbody>
              {history.map((item) => (
                <tr key={item.id}>
                  <td>{new Date(item.created_at).toLocaleString()}</td>
                  <td>
                    <span className={`badge ${item.success ? "success" : "danger"}`}>
                      {item.success ? "Успешно" : item.failure_reason || "Ошибка"}
                    </span>
                  </td>
                  <td>{item.ip_address || "—"}</td>
                  <td>{item.user_agent || "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </Shell>
  );
}
