"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { API_URL, api } from "@/lib/api";

type Consent = {
  request_id: string;
  client_id: string;
  client_name: string;
  client_description?: string;
  requested_scopes: string[];
  new_scopes: string[];
};

const scopeLabels: Record<string, string> = {
  openid: "Подтвердить вашу личность",
  profile: "Получить имя профиля",
  email: "Получить email и статус его подтверждения",
  offline_access: "Обновлять доступ без повторного входа",
};

function ConsentContent() {
  const requestId = useSearchParams().get("request_id") || "";
  const [consent, setConsent] = useState<Consent | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<Consent>(`/api/v1/oauth/consent/${encodeURIComponent(requestId)}`)
      .then(setConsent)
      .catch((cause) => setError(cause instanceof Error ? cause.message : "Запрос недействителен"));
  }, [requestId]);

  return (
    <AuthCard
      title={consent ? `${consent.client_name} запрашивает доступ` : "Запрос доступа"}
      subtitle={consent?.client_description || "Проверьте запрашиваемые разрешения"}
    >
      {error && <div className="alert error">{error}</div>}
      {consent && (
        <>
          {consent.new_scopes.length < consent.requested_scopes.length && (
            <div className="alert warning">Это дополнительный запрос доступа.</div>
          )}
          <ul className="scope-list">
            {consent.new_scopes.map((scope) => (
              <li key={scope}>
                <strong>{scopeLabels[scope] || scope}</strong>
                <span className="badge warning">Новое</span>
              </li>
            ))}
          </ul>
          <p className="muted">
            Доступ можно отозвать в любой момент. Приложение не получит данные вне этого списка.
          </p>
          <div className="consent-actions">
            <form method="post" action={`${API_URL}/api/v1/oauth/consent/deny`}>
              <input type="hidden" name="request_id" value={consent.request_id} />
              <button className="button secondary">Отказать</button>
            </form>
            <form method="post" action={`${API_URL}/api/v1/oauth/consent/approve`}>
              <input type="hidden" name="request_id" value={consent.request_id} />
              <button className="button">Разрешить</button>
            </form>
          </div>
        </>
      )}
    </AuthCard>
  );
}

export default function ConsentPage() {
  return (
    <Suspense>
      <ConsentContent />
    </Suspense>
  );
}

