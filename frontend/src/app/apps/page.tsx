"use client";

import { useCallback, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiRequestError } from "@/lib/api";
import { loginUrl } from "@/lib/domains";

type App = {
  consent_id: string;
  client_id: string;
  name: string;
  scopes: string[];
  granted_at: string;
};

export default function AppsPage() {
  const [apps, setApps] = useState<App[]>([]);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      setApps(await api<App[]>("/api/v1/account/connected-apps"));
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 401) {
        window.location.assign(loginUrl(window.location.href));
        return;
      }
      setError(cause instanceof Error ? cause.message : "Не удалось загрузить приложения");
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    return () => window.clearTimeout(initial);
  }, [load]);

  async function revoke(id: string) {
    await api(`/api/v1/account/connected-apps/${id}`, { method: "DELETE" });
    await load();
  }

  return (
    <Shell
      title="Подключенные приложения"
      subtitle="Полностью отзывайте ранее выданный доступ"
    >
      {error && <div className="alert error">{error}</div>}
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
