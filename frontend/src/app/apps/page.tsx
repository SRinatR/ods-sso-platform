"use client";

import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";

type User = { role: string };
type App = {
  consent_id: string;
  client_id: string;
  name: string;
  scopes: string[];
  granted_at: string;
};

export default function AppsPage() {
  const [user, setUser] = useState<User | null>(null);
  const [apps, setApps] = useState<App[]>([]);

  async function load() {
    const [me, connected] = await Promise.all([
      api<User>("/api/v1/auth/me"),
      api<App[]>("/api/v1/account/connected-apps"),
    ]);
    setUser(me);
    setApps(connected);
  }

  useEffect(() => {
    Promise.all([
      api<User>("/api/v1/auth/me"),
      api<App[]>("/api/v1/account/connected-apps"),
    ])
      .then(([me, connected]) => {
        setUser(me);
        setApps(connected);
      })
      .catch(() => window.location.assign(loginUrl(window.location.href)));
  }, []);

  async function revoke(id: string) {
    await api(`/api/v1/account/connected-apps/${id}`, { method: "DELETE" });
    await load();
  }

  return (
    <Shell
      title="Подключенные приложения"
      subtitle="Полностью отзывайте ранее выданный доступ"
      admin={user?.role === "admin" || user?.role === "security_admin"}
    >
      <div className="grid two">
        {apps.map((item) => (
          <section className="panel" key={item.consent_id}>
            <p className="eyebrow">{item.client_id}</p>
            <h2>{item.name}</h2>
            <div className="chips">
              {item.scopes.map((scope) => (
                <span className="badge" key={scope}>
                  {scope}
                </span>
              ))}
            </div>
            <p className="muted">Доступ выдан {new Date(item.granted_at).toLocaleString()}</p>
            <button className="button danger" onClick={() => revoke(item.consent_id)}>
              Отозвать доступ
            </button>
          </section>
        ))}
        {apps.length === 0 && <section className="panel">Подключенных приложений нет.</section>}
      </div>
    </Shell>
  );
}
