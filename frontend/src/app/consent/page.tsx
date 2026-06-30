"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { API_URL, api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";

type Consent = {
  client_id: string;
  client_name: string;
  client_description?: string;
  logo_uri?: string;
  hide_ods_branding: boolean;
  layout: "classic" | "granular";
  requested_scopes: string[];
  new_scopes: string[];
  data_fields: Array<{
    scope: string;
    label: string;
    fields: string[];
    required: boolean;
    enabled_by_default: boolean;
  }>;
};

type CurrentUser = {
  email: string;
  name?: string | null;
  first_name_cyrillic?: string | null;
  last_name_cyrillic?: string | null;
};

type ConsentTheme = "dark" | "light";
type ScopeTone = "blue" | "green" | "purple" | "amber" | "custom";
type ConsentIconName =
  | "brand"
  | "building"
  | "chevron"
  | "clock"
  | "database"
  | "globe"
  | "headset"
  | "help"
  | "id"
  | "lock"
  | "mail"
  | "phone"
  | "refresh"
  | "shield"
  | "type"
  | "user"
  | "users";

const scopeMeta: Record<
  string,
  { title: string; subtitle: string; icon: string; tone: ScopeTone }
> = {
  openid: {
    title: "Уникальный идентификатор",
    subtitle: "ID аккаунта ODS",
    icon: "ID",
    tone: "amber",
  },
  profile: {
    title: "Имя профиля",
    subtitle: "Имя пользователя",
    icon: "👤",
    tone: "blue",
  },
  email: {
    title: "Email",
    subtitle: "Адрес и статус",
    icon: "✉",
    tone: "purple",
  },
  full_name_cyrillic: {
    title: "ФИО на кириллице",
    subtitle: "Полное имя для локального профиля",
    icon: "Ф",
    tone: "blue",
  },
  full_name_latin: {
    title: "ФИО на латинице",
    subtitle: "Транслитерация полного имени",
    icon: "Aa",
    tone: "green",
  },
  phone: {
    title: "Телефон",
    subtitle: "Номер из профиля",
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
  const explicit = [user.first_name_cyrillic || user.name || "", user.last_name_cyrillic || ""]
    .map((part) => part.trim()[0])
    .filter(Boolean)
    .join("");
  if (explicit) return explicit.toUpperCase();
  return (user.email.trim()[0] || "").toUpperCase();
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

function scopeIconName(item: Consent["data_fields"][number]): ConsentIconName {
  const scopeName = item.scope.toLowerCase();
  if (scopeName.includes("org") || scopeName.includes("company")) return "building";
  if (scopeName.includes("role") || scopeName.includes("group")) return "users";
  if (scopeName.includes("locale") || scopeName.includes("language")) return "globe";
  if (scopeName.includes("phone")) return "phone";
  if (scopeName.includes("email")) return "mail";
  if (scopeName.includes("offline") || scopeName.includes("refresh")) return "refresh";
  if (scopeName.includes("name")) return "type";
  if (scopeName === "openid") return "id";
  if (scopeName === "profile") return "user";
  return "database";
}

function scopeTone(item: Consent["data_fields"][number]): ScopeTone {
  return scopeMeta[item.scope]?.tone ?? "custom";
}

function readStoredTheme(): ConsentTheme {
  return window.localStorage.getItem("ods-consent-theme") === "light" ? "light" : "dark";
}

function initialSelectedScopes(consent: Consent) {
  return new Set(
    consent.data_fields
      .filter((item) => item.required || item.enabled_by_default)
      .map((item) => item.scope),
  );
}

function ConsentContent() {
  const params = useSearchParams();
  const clientId = params.get("client_id");
  const state = params.get("state");
  const scope = params.get("scope");
  const [consent, setConsent] = useState<Consent | null>(null);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [error, setError] = useState("");
  const [selectedScopes, setSelectedScopes] = useState<Set<string>>(new Set());
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
      .then((value) => {
        setConsent(value);
        setSelectedScopes(initialSelectedScopes(value));
      })
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

  function toggleScope(scopeName: string) {
    const item = consent?.data_fields.find((field) => field.scope === scopeName);
    if (!item || item.required) {
      return;
    }
    setSelectedScopes((current) => {
      const next = new Set(current);
      if (next.has(scopeName)) {
        next.delete(scopeName);
      } else {
        next.add(scopeName);
      }
      return next;
    });
  }

  async function switchAccount() {
    setError("");
    try {
      await api("/api/v1/auth/logout", { method: "POST" });
    } finally {
      window.location.href = loginUrl(window.location.href);
    }
  }

  return (
    <main className="consent-screen" data-layout={consent?.layout ?? "loading"} data-theme={theme}>
      <section className="consent-shell" data-layout={consent?.layout ?? "loading"}>
        <header className="consent-topbar">
          <div className="consent-brand">
            <span className="consent-shield" aria-hidden="true">
              <ConsentIcon name="brand" />
            </span>
            <div>
              <strong>ODS SSO</strong>
              <span>Secure Identity Platform</span>
            </div>
          </div>
          <div className="consent-topbar-actions">
            <div className="consent-language" aria-label="Язык интерфейса">
              <ConsentIcon name="globe" />
              <span>Русский</span>
              <ConsentIcon name="chevron" />
            </div>
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
          consent.layout === "granular" && clientId && state ? (
            <GranularConsent
              clientId={clientId}
              consent={consent}
              selectedScopes={selectedScopes}
              state={state}
              switchAccount={switchAccount}
              toggleScope={toggleScope}
              user={user}
            />
          ) : (
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
          )
        ) : (
          <div className="consent-loading">Загрузка запроса доступа…</div>
        )}

        {consent && clientId && state && consent.layout !== "granular" ? (
          <ConsentActions
            allowLabel="Разрешить доступ"
            clientId={clientId}
            scopes={consent.requested_scopes}
            state={state}
          />
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

function GranularConsent({
  clientId,
  consent,
  selectedScopes,
  state,
  switchAccount,
  toggleScope,
  user,
}: {
  clientId: string;
  consent: Consent;
  selectedScopes: Set<string>;
  state: string;
  switchAccount: () => void;
  toggleScope: (scope: string) => void;
  user: CurrentUser | null;
}) {
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [toast, setToast] = useState("");
  const submitScopes = consent.requested_scopes.filter((scopeName) => selectedScopes.has(scopeName));

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => setToast(""), 2800);
    return () => window.clearTimeout(timer);
  }, [toast]);

  function handleScopeAction(item: Consent["data_fields"][number], checked: boolean) {
    if (item.required) {
      setToast("Это обязательное поле для входа, его нельзя отключить.");
      return;
    }
    toggleScope(item.scope);
    setToast(`${scopeTitle(item)} ${checked ? "отключено" : "включено"}.`);
  }

  return (
    <section className="consent-granular-wrap" aria-labelledby="consent-granular-heading">
      <div className="consent-granular-grid">
        <section className="consent-granular-card consent-granular-main">
          <div className="consent-granular-copy-block">
            <h1 id="consent-granular-heading">Вход и передача данных</h1>
            <p>{consent.client_name} запросил данные для входа через ODS SSO.</p>
          </div>

          <div className="consent-requester-card" aria-label={`Доступ запрашивает ${consent.client_name}`}>
            <span className="consent-requester-icon" aria-hidden="true">
              {consent.logo_uri ? (
                <>
                  {/* eslint-disable-next-line @next/next/no-img-element -- partner-provided logo URL */}
                  <img src={consent.logo_uri} alt="" />
                </>
              ) : (
                <ConsentIcon name="building" />
              )}
            </span>
            <span className="consent-requester-copy">
              <span>Доступ запрашивает</span>
              <strong>{consent.client_name}</strong>
              {consent.client_description ? <small>{consent.client_description}</small> : null}
            </span>
          </div>

          <div className="consent-account-card">
            <span className="consent-account-icon" aria-hidden="true">
              <ConsentIcon name="user" />
            </span>
            <div>
              <span>Вы входите как</span>
              <strong>{user?.email ?? "Аккаунт ODS"}</strong>
            </div>
            <button className="consent-account-switch" onClick={switchAccount} type="button">
              Сменить аккаунт
              <ConsentIcon name="chevron" />
            </button>
          </div>

          <div className="consent-granular-heading-row">
            <h2>Передаваемые данные</h2>
            <button className="consent-info-button" onClick={() => setDetailsOpen(true)} type="button">
              Подробнее
            </button>
          </div>
          <div className="consent-granular-list">
            {consent.data_fields.map((item) => {
              const checked = selectedScopes.has(item.scope);
              return (
                <button
                  aria-checked={checked}
                  aria-label={`${scopeTitle(item)}: ${item.required ? "обязательное поле" : checked ? "передавать" : "не передавать"}`}
                  className="consent-granular-item"
                  data-required={item.required}
                  key={item.scope}
                  onClick={() => handleScopeAction(item, checked)}
                  role="switch"
                  title={item.required ? "Обязательное поле для входа" : "Нажмите, чтобы изменить передачу поля"}
                  type="button"
                >
                  <span className={`consent-scope-icon ${scopeTone(item)}`} aria-hidden="true">
                    <ConsentIcon name={scopeIconName(item)} />
                  </span>
                  <span className="consent-granular-copy">
                    <strong>{scopeTitle(item)}</strong>
                    <small>{scopeSubtitle(item)}</small>
                  </span>
                  <span
                    className="consent-toggle"
                    data-checked={checked}
                    data-required={item.required}
                    aria-hidden="true"
                  >
                    <span />
                  </span>
                  {item.required ? (
                    <span
                      aria-label="Обязательное поле"
                      className="consent-required-lock consent-tooltip"
                      title="Обязательное поле"
                    >
                      <ConsentIcon name="lock" />
                      <span className="consent-tooltip-bubble">Обязательное поле для входа</span>
                    </span>
                  ) : (
                    <span className="consent-optional-chip consent-tooltip">
                      Необязательное
                      <span className="consent-tooltip-bubble">Можно отключить перед входом</span>
                    </span>
                  )}
                </button>
              );
            })}
          </div>

          <div className="consent-granular-note">
            <ConsentIcon name="help" />
            <span>Доступ можно отозвать позже в настройках аккаунта.</span>
          </div>

          <ConsentActions
            allowLabel="Разрешить и войти"
            clientId={clientId}
            scopes={submitScopes}
            state={state}
          />
        </section>
      </div>

      {toast ? (
        <div aria-live="polite" className="consent-toast" role="status">
          {toast}
        </div>
      ) : null}

      {detailsOpen ? (
        <div className="consent-help-backdrop" role="presentation">
          <section
            aria-labelledby="consent-help-title"
            aria-modal="true"
            className="consent-help-dialog"
            role="dialog"
          >
            <h2 id="consent-help-title">О передаче данных</h2>
            <p>Доступ действует до отзыва в настройках аккаунта ODS.</p>
            <p>Передаются только выбранные данные; обязательные поля отмечены голубым замком.</p>
            <p>
              Нажимая «Разрешить и войти», вы принимаете передачу данных согласно{" "}
              <a href="/privacy">Политике конфиденциальности</a>.
            </p>
            <button className="consent-button primary" onClick={() => setDetailsOpen(false)} type="button">
              Понятно
            </button>
          </section>
        </div>
      ) : null}
    </section>
  );
}

function ConsentIcon({ name }: { name: ConsentIconName }) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      focusable="false"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.8"
      viewBox="0 0 24 24"
    >
      {name === "brand" ? (
        <>
          <path d="M5 4h7a3 3 0 0 1 0 6H9v10H5V4Z" />
          <path d="M13 4h6v4h-6" />
          <path d="M9 10h6a3 3 0 0 1 0 6H9" />
        </>
      ) : null}
      {name === "building" ? (
        <>
          <path d="M4 20h16" />
          <path d="M6 20V5h9v15" />
          <path d="M15 9h3v11" />
          <path d="M9 8h2M9 12h2M9 16h2" />
        </>
      ) : null}
      {name === "chevron" ? <path d="m9 6 6 6-6 6" /> : null}
      {name === "clock" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M12 8v5l3 2" />
        </>
      ) : null}
      {name === "database" ? (
        <>
          <ellipse cx="12" cy="6" rx="7" ry="3" />
          <path d="M5 6v6c0 1.7 3.1 3 7 3s7-1.3 7-3V6" />
          <path d="M5 12v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
        </>
      ) : null}
      {name === "globe" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M4 12h16" />
          <path d="M12 4a12 12 0 0 1 0 16" />
          <path d="M12 4a12 12 0 0 0 0 16" />
        </>
      ) : null}
      {name === "headset" ? (
        <>
          <path d="M5 13v-1a7 7 0 0 1 14 0v1" />
          <path d="M5 13h3v5H5zM16 13h3v5h-3z" />
          <path d="M19 18c0 2-2 3-5 3h-2" />
        </>
      ) : null}
      {name === "help" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M9.8 9a2.4 2.4 0 0 1 4.6 1.1c0 1.8-2.4 2-2.4 3.8" />
          <path d="M12 17h.01" />
        </>
      ) : null}
      {name === "id" ? (
        <>
          <rect x="4" y="5" width="16" height="14" rx="2" />
          <path d="M8 10h4M8 14h8" />
        </>
      ) : null}
      {name === "lock" ? (
        <>
          <rect x="6" y="10" width="12" height="9" rx="2" />
          <path d="M9 10V8a3 3 0 0 1 6 0v2" />
          <path d="M12 14v2" />
        </>
      ) : null}
      {name === "mail" ? (
        <>
          <rect x="4" y="6" width="16" height="12" rx="2" />
          <path d="m5 8 7 5 7-5" />
        </>
      ) : null}
      {name === "phone" ? (
        <path d="M8 5 6 7c-.6.6-.8 1.4-.5 2.2a18 18 0 0 0 9.3 9.3c.8.3 1.6.1 2.2-.5l2-2-3-3-1.6 1.6a10 10 0 0 1-5-5L11 8 8 5Z" />
      ) : null}
      {name === "refresh" ? (
        <>
          <path d="M19 8a7 7 0 0 0-12-3L5 7" />
          <path d="M5 4v3h3" />
          <path d="M5 16a7 7 0 0 0 12 3l2-2" />
          <path d="M19 20v-3h-3" />
        </>
      ) : null}
      {name === "shield" ? (
        <>
          <path d="M12 3 19 6v5c0 5-3.5 8-7 10-3.5-2-7-5-7-10V6l7-3Z" />
          <path d="M9.5 12.5 11 14l3.5-4" />
        </>
      ) : null}
      {name === "type" ? (
        <>
          <path d="M5 6h14M8 6v12M16 6v12" />
          <path d="M7 18h3M14 18h3" />
        </>
      ) : null}
      {name === "user" ? (
        <>
          <circle cx="12" cy="8" r="3" />
          <path d="M5 20a7 7 0 0 1 14 0" />
        </>
      ) : null}
      {name === "users" ? (
        <>
          <circle cx="9" cy="8" r="3" />
          <path d="M3 20a6 6 0 0 1 12 0" />
          <path d="M16 11a3 3 0 0 0 0-6" />
          <path d="M18 20a5 5 0 0 0-4-5" />
        </>
      ) : null}
    </svg>
  );
}

function ConsentActions({
  allowLabel,
  clientId,
  scopes,
  state,
}: {
  allowLabel: string;
  clientId: string;
  scopes: string[];
  state: string;
}) {
  return (
    <footer className="consent-actions-v2">
      <form method="post" action={`${API_URL}/authorize`}>
        <input type="hidden" name="client_id" value={clientId} />
        <input type="hidden" name="state" value={state} />
        <button className="consent-button secondary">Отклонить</button>
      </form>
      <form method="post" action={`${API_URL}/authorize`}>
        <input type="hidden" name="client_id" value={clientId} />
        <input type="hidden" name="state" value={state} />
        {scopes.map((requestedScope) => (
          <input key={requestedScope} type="hidden" name="scope" value={requestedScope} />
        ))}
        <button className="consent-button primary">{allowLabel}</button>
      </form>
    </footer>
  );
}

export default function ConsentPage() {
  return (
    <Suspense>
      <ConsentContent />
    </Suspense>
  );
}
