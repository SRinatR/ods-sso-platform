"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { API_URL, api } from "@/lib/api";

type Consent = {
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
  const params = useSearchParams();
  const clientId = params.get("client_id") || "";
  const state = params.get("state") || "";
  const scope = params.get("scope") || "";
  const [consent, setConsent] = useState<Consent | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const query = new URLSearchParams({ client_id: clientId, scope });
    api<Consent>(`/api/v1/oauth/consent?${query}`)
      .then(setConsent)
      .catch((cause) => setError(cause instanceof Error ? cause.message : "Запрос недействителен"));
  }, [clientId, scope]);

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
            <form method="post" action={`${API_URL}/authorize`}>
              <input type="hidden" name="client_id" value={clientId} />
              <input type="hidden" name="state" value={state} />
              <button className="button secondary">Отказать</button>
            </form>
            <form method="post" action={`${API_URL}/authorize`}>
              <input type="hidden" name="client_id" value={clientId} />
              <input type="hidden" name="state" value={state} />
              {consent.requested_scopes.map((requestedScope) => (
                <input
                  key={requestedScope}
                  type="hidden"
                  name="scope"
                  value={requestedScope}
                />
              ))}
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
