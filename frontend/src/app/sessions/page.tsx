"use client";

import { useCallback, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiRequestError } from "@/lib/api";
import { loginUrl } from "@/lib/domains";

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
  const [sessions, setSessions] = useState<Session[]>([]);
  const [history, setHistory] = useState<Login[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      await api("/api/v1/auth/me");
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 401) {
        window.location.assign(loginUrl(window.location.href));
        return;
      }
      setError(cause instanceof Error ? cause.message : "Не удалось проверить сессию");
      setLoading(false);
      return;
    }

    const [activeResult, historyResult] = await Promise.allSettled([
      api<Session[]>("/api/v1/account/sessions"),
      api<Login[]>("/api/v1/account/login-history"),
    ]);
    if (activeResult.status === "fulfilled") {
      setSessions(activeResult.value);
    }
    if (historyResult.status === "fulfilled") {
      setHistory(historyResult.value);
    }
    const failures = [activeResult, historyResult].filter(
      (result) => result.status === "rejected",
    );
    if (failures.length > 0) {
      setError(
        "Сессия активна, но часть данных об устройствах временно не загрузилась. Повторите запрос.",
      );
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    return () => window.clearTimeout(initial);
  }, [load]);

  async function revoke(id: string) {
    setError("");
    try {
      await api(`/api/v1/account/sessions/${id}`, { method: "DELETE" });
      const current = sessions.find((item) => item.id === id)?.current;
      if (current) {
        window.location.assign(loginUrl(window.location.href));
      } else {
        await load();
      }
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 401) {
        window.location.assign(loginUrl(window.location.href));
        return;
      }
      setError(cause instanceof Error ? cause.message : "Не удалось завершить сессию");
    }
  }

  return (
    <Shell
      title="Сессии и история входов"
      subtitle="Устройства, IP-адреса и результаты входа"
    >
      {error && (
        <div className="alert error">
          {error}
          <button className="text-button" type="button" onClick={() => void load()}>
            Повторить
          </button>
        </div>
      )}
      {loading && <section className="panel">Загрузка устройств и истории…</section>}
      <section className="panel">
        <h2>Активные сессии</h2>
        <p className="muted">
          Это устройства и браузеры, где сейчас действует вход. Завершение сессии сразу закрывает доступ на выбранном устройстве.
        </p>
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
                      {item.current ? "Выйти здесь" : "Завершить"}
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && sessions.length === 0 && (
                <tr>
                  <td colSpan={5} className="muted">Активные сессии не найдены.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      <section className="panel">
        <h2>История входов</h2>
        <p className="muted">
          Это журнал успешных и неуспешных попыток входа. Запись в истории не означает, что сессия еще активна.
        </p>
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
              {!loading && history.length === 0 && (
                <tr>
                  <td colSpan={4} className="muted">История входов пока пуста.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </Shell>
  );
}
