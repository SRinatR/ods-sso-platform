"use client";

import { useCallback, useEffect, useState } from "react";
import { API_URL, ApiRequestError } from "@/lib/api";

type PlatformStatus = {
  status: "operational" | "degraded";
  checked_at: string;
  components: Record<string, "operational" | "unavailable">;
};

const labels: Record<string, string> = {
  identity: "Авторизация и аккаунты",
  database: "Хранилище данных",
  sessions: "Сессии и одноразовые коды",
};

export default function StatusPage() {
  const [status, setStatus] = useState<PlatformStatus | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch(`${API_URL}/api/v1/public/status`, {
        cache: "no-store",
        credentials: "omit",
      });
      const payload = (await response.json()) as PlatformStatus;
      if (!response.ok && response.status !== 503) {
        throw new ApiRequestError("Не удалось получить состояние сервисов", response.status);
      }
      setStatus(payload);
    } catch {
      setStatus({
        status: "degraded",
        checked_at: new Date().toISOString(),
        components: {
          identity: "unavailable",
          database: "unavailable",
          sessions: "unavailable",
        },
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(refresh, 0);
    const timer = window.setInterval(refresh, 30_000);
    return () => {
      window.clearTimeout(initial);
      window.clearInterval(timer);
    };
  }, [refresh]);

  const checking = status === null && loading;
  const operational = status?.status === "operational";

  return (
    <main className="status-page">
      <header className="status-header">
        <div>
          <p className="eyebrow">ODS Identity</p>
          <h1>Состояние сервисов</h1>
          <p className="muted">Автоматическая проверка авторизации, базы данных и сессий.</p>
        </div>
        <button className="button secondary" onClick={refresh} disabled={loading}>
          {loading ? "Проверяем…" : "Проверить сейчас"}
        </button>
      </header>

      <section
        className={`status-summary ${checking ? "checking" : operational ? "operational" : "degraded"}`}
      >
        <span className="status-pulse" />
        <div>
          <h2>
            {checking
              ? "Проверяем состояние"
              : operational
                ? "Все системы работают"
                : "Есть проблемы с доступностью"}
          </h2>
          <p>
            {checking
              ? "Получаем актуальные данные о компонентах платформы."
              : operational
              ? "Регистрация, вход и личные кабинеты доступны."
              : "Некоторые функции могут быть временно недоступны."}
          </p>
        </div>
      </section>

      <section className="status-components">
        {Object.entries(status?.components || {}).map(([name, value]) => (
          <article className="status-component" key={name}>
            <div>
              <strong>{labels[name] || name}</strong>
              <span>Проверяется автоматически</span>
            </div>
            <span className={`badge ${value === "operational" ? "success" : "danger"}`}>
              {value === "operational" ? "Работает" : "Недоступно"}
            </span>
          </article>
        ))}
      </section>

      <footer className="status-footer">
        <span>
          Последняя проверка:{" "}
          {status ? new Date(status.checked_at).toLocaleString("ru-RU") : "—"}
        </span>
        <a href="mailto:support@ods.uz">support@ods.uz</a>
      </footer>
    </main>
  );
}
