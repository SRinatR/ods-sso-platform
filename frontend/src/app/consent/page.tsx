"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { API_URL, api } from "@/lib/api";

type Consent = {
  client_id: string;
  client_name: string;
  client_description?: string;
  logo_uri?: string;
  hide_ods_branding: boolean;
  requested_scopes: string[];
  new_scopes: string[];
  data_fields: Array<{
    scope: string;
    label: string;
    fields: string[];
  }>;
};

type CurrentUser = {
  email: string;
  name?: string | null;
};

type ConsentTheme = "dark" | "light";
type ScopeTone = "blue" | "green" | "purple" | "amber" | "custom";

const scopeMeta: Record<
  string,
  { title: string; subtitle: string; icon: string; tone: ScopeTone }
> = {
  openid: {
    title: "Уникальный идентификатор",
    subtitle: "ID пользователя в корпоративной системе",
    icon: "ID",
    tone: "amber",
  },
  profile: {
    title: "Основная информация",
    subtitle: "Имя, фамилия, профиль и роль в организации",
    icon: "👤",
    tone: "blue",
  },
  email: {
    title: "Контактные данные",
    subtitle: "Рабочий email и статус подтверждения",
    icon: "✉",
    tone: "purple",
  },
  phone: {
    title: "Контактные данные",
    subtitle: "Телефон, если он заполнен в профиле",
    icon: "☎",
    tone: "purple",
  },
  offline_access: {
    title: "Долгая сессия",
    subtitle: "Обновление доступа без повторного входа",
    icon: "↻",
    tone: "green",
  },
};

function initials(user: CurrentUser | null) {
  if (!user) {
    return "";
  }
  const source = user.name ? user.name : user.email;
  return source
    .split(/[\s@._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");
}

function scopeTitle(item: Consent["data_fields"][number]) {
  return scopeMeta[item.scope]?.title ?? item.label;
}

function scopeSubtitle(item: Consent["data_fields"][number]) {
  return scopeMeta[item.scope]?.subtitle ?? item.fields.join(", ");
}

function scopeIcon(item: Consent["data_fields"][number]) {
  return scopeMeta[item.scope]?.icon ?? item.scope;
}

function scopeTone(item: Consent["data_fields"][number]): ScopeTone {
  return scopeMeta[item.scope]?.tone ?? "custom";
}

function readStoredTheme(): ConsentTheme {
  return window.localStorage.getItem("ods-consent-theme") === "light" ? "light" : "dark";
}

function ConsentContent() {
  const params = useSearchParams();
  const clientId = params.get("client_id");
  const state = params.get("state");
  const scope = params.get("scope");
  const [consent, setConsent] = useState<Consent | null>(null);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [error, setError] = useState("");
  const [theme, setTheme] = useState<ConsentTheme>(() =>
    typeof window === "undefined" ? "dark" : readStoredTheme(),
  );
  const requestError =
    !clientId || !state || !scope
      ? "OAuth consent request is missing client_id, state, or scope."
      : "";

  useEffect(() => {
    if (!clientId || !state || !scope) {
      return;
    }
    const query = new URLSearchParams({ client_id: clientId, scope });
    api<Consent>(`/api/v1/oauth/consent?${query}`)
      .then(setConsent)
      .catch((cause) => setError(cause instanceof Error ? cause.message : String(cause)));
    api<CurrentUser>("/api/v1/auth/me")
      .then(setUser)
      .catch((cause) => setError(cause instanceof Error ? cause.message : String(cause)));
  }, [clientId, scope, state]);

  function toggleTheme() {
    const nextTheme: ConsentTheme = theme === "dark" ? "light" : "dark";
    window.localStorage.setItem("ods-consent-theme", nextTheme);
    setTheme(nextTheme);
  }

  return (
    <main className="consent-screen" data-theme={theme}>
      <section className="consent-shell">
        <header className="consent-topbar">
          <div className="consent-brand">
            <span className="consent-shield" aria-hidden="true">
              ◇
            </span>
            <div>
              <strong>ODS SSO</strong>
              <span>Secure Identity Platform</span>
            </div>
          </div>
          <div className="consent-topbar-actions">
            <button className="consent-theme-toggle" onClick={toggleTheme} type="button">
              {theme === "dark" ? "Светлая тема" : "Тёмная тема"}
            </button>
            {user ? (
              <div className="consent-user">
                <span className="consent-avatar">{initials(user)}</span>
                <div>
                  {user.name ? <strong>{user.name}</strong> : null}
                  <span>{user.email}</span>
                </div>
                <span aria-hidden="true">⌄</span>
              </div>
            ) : null}
          </div>
        </header>

        {requestError || error ? <div className="consent-error">{requestError || error}</div> : null}

        {consent ? (
          <div className="consent-content">
            <section className="consent-main">
              <h1>Приложение запрашивает доступ к вашим данным</h1>
              <div className="consent-app">
                {consent.logo_uri ? (
                  <div className="consent-app-icon">
                    {/* eslint-disable-next-line @next/next/no-img-element -- partner-provided logo URL */}
                    <img alt="" src={consent.logo_uri} />
                  </div>
                ) : null}
                <div>
                  <div className="consent-app-title">
                    <strong>{consent.client_name}</strong>
                    {!consent.hide_ods_branding ? <span>Проверено</span> : null}
                  </div>
                  {consent.client_description ? (
                    <p>Разработчик: {consent.client_description}</p>
                  ) : null}
                  <code>{consent.client_id}</code>
                </div>
              </div>

              {!consent.logo_uri || !consent.client_description ? (
                <div className="consent-warning">
                  {!consent.logo_uri ? (
                    <span>В ODS-приложении не настроен logo URI.</span>
                  ) : null}
                  {!consent.client_description ? (
                    <span>В ODS-приложении не заполнено описание разработчика.</span>
                  ) : null}
                </div>
              ) : null}

              <p className="consent-description">
                Приложение запрашивает доступ к вашим данным для аутентификации, управления
                доступом и персонализации вашего опыта.
              </p>

              {consent.new_scopes.length < consent.requested_scopes.length ? (
                <div className="consent-warning">Это дополнительный запрос доступа.</div>
              ) : null}

              <div className="consent-data-header">
                <strong>Запрашиваемые данные</strong>
                <span>⚙ Настроить доступ</span>
              </div>

              <div className="consent-data-list">
                {consent.data_fields.map((item) => (
                  <details className="consent-data-item" key={item.scope}>
                    <summary>
                      <span className={`consent-scope-icon ${scopeTone(item)}`}>
                        {scopeIcon(item)}
                      </span>
                      <span>
                        <strong>{scopeTitle(item)}</strong>
                        <small>{scopeSubtitle(item)}</small>
                      </span>
                      {consent.new_scopes.includes(item.scope) ? <em>Новое</em> : null}
                      <span className="consent-check">✓</span>
                      <span className="consent-chevron">⌄</span>
                    </summary>
                    <ul>
                      {item.fields.map((field) => (
                        <li key={field}>{field}</li>
                      ))}
                    </ul>
                  </details>
                ))}
              </div>

              <div className="consent-note">
                <span>♢</span>
                <p>
                  Вы всегда можете отозвать доступ в настройках безопасности вашей учетной записи.{" "}
                  <a href="/apps">Подробнее ↗</a>
                </p>
              </div>
            </section>

            <aside className="consent-side">
              <InfoCard
                icon="▣"
                tone="green"
                title="Безопасность"
                text="Передача данных защищена по протоколу TLS 1.3. Используется шифрование и подпись токенов (OIDC)."
              />
              <InfoCard
                icon="◎"
                tone="blue"
                title="Цель доступа"
                text="Аутентификация, управление доступом и персонализация внутри приложения."
              />
              <InfoCard
                icon="◴"
                tone="purple"
                title="Срок хранения"
                text="Данные используются только в течение вашей сессии и согласно политике приложения."
              />
              <InfoCard
                icon="▤"
                tone="blue"
                title="Политика конфиденциальности"
                text="Ознакомьтесь с тем, как приложение обрабатывает ваши данные."
                link="/privacy"
              />
            </aside>
          </div>
        ) : (
          <div className="consent-loading">Загрузка запроса доступа…</div>
        )}

        {consent && clientId && state ? (
          <footer className="consent-actions-v2">
            <form method="post" action={`${API_URL}/authorize`}>
              <input type="hidden" name="client_id" value={clientId} />
              <input type="hidden" name="state" value={state} />
              <button className="consent-button secondary">Отклонить</button>
            </form>
            <form method="post" action={`${API_URL}/authorize`}>
              <input type="hidden" name="client_id" value={clientId} />
              <input type="hidden" name="state" value={state} />
              {consent.requested_scopes.map((requestedScope) => (
                <input key={requestedScope} type="hidden" name="scope" value={requestedScope} />
              ))}
              <button className="consent-button primary">Разрешить доступ</button>
            </form>
          </footer>
        ) : null}
      </section>
    </main>
  );
}

function InfoCard({
  icon,
  title,
  text,
  link,
  tone,
}: {
  icon: string;
  title: string;
  text: string;
  link?: string;
  tone: ScopeTone;
}) {
  return (
    <article>
      <span className={`consent-scope-icon ${tone}`}>{icon}</span>
      <div>
        <strong>{title}</strong>
        <p>{text}</p>
        {link ? <a href={link}>Перейти к политике ↗</a> : null}
      </div>
    </article>
  );
}

export default function ConsentPage() {
  return (
    <Suspense>
      <ConsentContent />
    </Suspense>
  );
}
