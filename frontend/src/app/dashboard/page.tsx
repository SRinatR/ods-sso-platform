"use client";

import Link from "next/link";
import type { CSSProperties } from "react";
import { useCallback, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiRequestError } from "@/lib/api";
import { ACCOUNTS_URL, ROOT_URL, loginUrl } from "@/lib/domains";

type User = {
  id: string;
  email: string;
  name?: string;
  first_name_cyrillic?: string;
  last_name_cyrillic?: string;
  patronymic_cyrillic?: string;
  phone?: string;
  email_verified: boolean;
  status: string;
  role: string;
  mfa_enabled: boolean;
  authentication_method?: string;
  created_at?: string;
};

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

type ConnectedApp = {
  consent_id: string;
  client_id: string;
  name: string;
  scopes: string[];
  granted_at: string;
};

type LoadState = {
  apps: ConnectedApp[];
  history: Login[];
  sessions: Session[];
};

const emptyState: LoadState = {
  apps: [],
  history: [],
  sessions: [],
};

const primaryScopes = new Set(["openid", "profile", "email", "full_name_cyrillic", "full_name_latin"]);

type AccountIconName =
  | "apps"
  | "calendar"
  | "check"
  | "chevron"
  | "clock"
  | "copy"
  | "desktop"
  | "download"
  | "edit"
  | "globe"
  | "key"
  | "lock"
  | "mail"
  | "phone"
  | "profile"
  | "security";

type IconTone = "blue" | "green" | "amber" | "violet" | "slate";

export default function DashboardPage() {
  const [user, setUser] = useState<User | null>(null);
  const [data, setData] = useState<LoadState>(emptyState);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const currentUser = await api<User>("/api/v1/auth/me");
      setUser(currentUser);

      const [sessionsResult, historyResult, appsResult] =
        await Promise.allSettled([
          api<Session[]>("/api/v1/account/sessions"),
          api<Login[]>("/api/v1/account/login-history"),
          api<ConnectedApp[]>("/api/v1/account/connected-apps"),
        ]);

      setData({
        sessions: fulfilledOrEmpty(sessionsResult),
        history: fulfilledOrEmpty(historyResult),
        apps: fulfilledOrEmpty(appsResult),
      });

      if ([sessionsResult, historyResult, appsResult].some((result) => result.status === "rejected")) {
        setError("Аккаунт загружен, но часть данных кабинета временно недоступна.");
      }
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 401) {
        window.location.href = loginUrl(window.location.href);
        return;
      }
      setError(cause instanceof Error ? cause.message : "Не удалось загрузить кабинет");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    return () => window.clearTimeout(initial);
  }, [load]);

  return (
    <Shell title={user ? "Главная" : "Загрузка кабинета"}>
      {error && (
        <div className="alert error account-alert">
          {error}
          <button className="text-button" onClick={() => void load()} type="button">
            Повторить
          </button>
        </div>
      )}

      {loading && !user ? <section className="account-card">Загрузка данных аккаунта...</section> : null}

      {user ? (
        <div className="account-dashboard account-dashboard-v2">
          <section className="account-dashboard-hero">
            <ProfileSummary user={user} />
            <SecuritySummary user={user} history={data.history} />
            <LastConsentSummary apps={data.apps} />
          </section>

          <section className="account-stat-grid" aria-label="Сводка аккаунта">
            <MetricCard
              action="Открыть сессии"
              href={`${ACCOUNTS_URL}/sessions`}
              icon="desktop"
              label="Активные сессии"
              tone="blue"
              value={String(data.sessions.length)}
            />
            <MetricCard
              action="Управлять согласиями"
              href={`${ACCOUNTS_URL}/apps`}
              icon="security"
              label="Предоставленные согласия"
              tone="green"
              value={String(data.apps.length)}
            />
            <MetricCard
              action="Смотреть все"
              href={`${ACCOUNTS_URL}/apps`}
              icon="apps"
              label="Подключенные приложения"
              tone="violet"
              value={String(uniqueClientCount(data.apps))}
            />
          </section>

          <section className="account-work-grid">
            <TransferredDataCard apps={data.apps} />
            <ConnectedAppsCard apps={data.apps} />
            <RecentLoginsCard history={data.history} />
            <QuickActions />
          </section>

          <section className="account-dashboard-bottom">
            <PrivacyCenter />
            <SsoIdentityNote />
          </section>
        </div>
      ) : null}
    </Shell>
  );
}

function fulfilledOrEmpty<T>(result: PromiseSettledResult<T[]>): T[] {
  return result.status === "fulfilled" ? result.value : [];
}

function ProfileSummary({ user }: { user: User }) {
  return (
    <article className="account-card account-id-card">
      <div className="account-section-title account-section-title-tight">
        <h2>ID-профиль</h2>
        <Link
          aria-label="Редактировать профиль"
          className="account-link-button account-icon-button"
          href={`${ACCOUNTS_URL}/profile`}
          title="Редактировать профиль"
        >
          <AccountDashboardIcon name="edit" />
        </Link>
      </div>

      <div className="account-profile-hero">
        <div className="account-avatar account-profile-avatar" aria-hidden="true">
          {initials(user)}
        </div>
        <div className="account-profile-identity">
          <h3>{userName(user)}</h3>
          <p>{user.email}</p>
          <span className="account-pill" data-tone={user.email_verified ? "green" : "amber"}>
            <AccountDashboardIcon name={user.email_verified ? "check" : "clock"} />
            {user.email_verified ? "Email подтвержден" : "Email ожидает подтверждения"}
          </span>
        </div>
      </div>

      <div className="account-profile-facts">
        <InfoLine icon="key" label={`Вход выполнен через ${loginMethod(user)}`} tone="accent" />
        <InfoLine
          icon="phone"
          label={user.phone ? user.phone : "Телефон не указан"}
          tone={user.phone ? "success" : "muted"}
        />
        <InfoLine
          icon="calendar"
          label={user.created_at ? `Дата регистрации: ${formatDate(user.created_at)}` : "Дата регистрации недоступна"}
          tone="muted"
        />
      </div>
    </article>
  );
}

function SecuritySummary({ user, history }: { user: User; history: Login[] }) {
  const lastLogin = history.find((item) => item.success);
  const checks = [
    { label: "Email подтвержден", value: user.email_verified ? "Да" : "Нет", ok: user.email_verified },
    { label: "MFA включена", value: user.mfa_enabled ? "Да" : "Не настроена", ok: user.mfa_enabled },
    { label: "Способ входа", value: loginMethod(user), ok: true },
    { label: "Статус аккаунта", value: user.status, ok: user.status === "active" },
  ];
  const score = Math.round((checks.filter((item) => item.ok).length / checks.length) * 100);
  const level = score >= 80 ? "Высокий" : score >= 60 ? "Средний" : "Низкий";

  return (
    <article className="account-card account-security-card">
      <div className="account-section-title account-section-title-tight">
        <h2>Статус безопасности</h2>
        <Link href={`${ACCOUNTS_URL}/security`}>
          Проверить
          <AccountDashboardIcon name="chevron" />
        </Link>
      </div>
      <div className="account-security-content">
        <div
          aria-label={`Уровень безопасности ${score} процентов`}
          className="account-security-ring account-security-ring-score"
          style={{ "--score": `${score}%` } as CSSProperties}
        >
          <strong>{score}%</strong>
          <small>{level}</small>
        </div>
        <div className="account-check-list">
          {checks.map((item) => (
            <div className="account-check-row" data-ok={item.ok} key={item.label}>
              <span>
                <AccountDashboardIcon name="check" />
              </span>
              <p>{item.label}</p>
              <strong>{item.value}</strong>
            </div>
          ))}
          <InfoLine
            icon="clock"
            label={lastLogin ? `Последний вход: ${formatDateTime(lastLogin.created_at)}` : "Успешных входов в журнале нет"}
            tone="muted"
          />
        </div>
      </div>
      <Link className="account-inline-link" href={`${ACCOUNTS_URL}/security`}>
        Перейти в безопасность
        <AccountDashboardIcon name="chevron" />
      </Link>
    </article>
  );
}

function LastConsentSummary({ apps }: { apps: ConnectedApp[] }) {
  const latest = latestConsent(apps);

  return (
    <article className="account-card account-last-consent-card">
      <div className="account-section-title account-section-title-tight">
        <h2>Последняя передача данных</h2>
        <Link href={`${ACCOUNTS_URL}/apps`}>
          Подробнее
          <AccountDashboardIcon name="chevron" />
        </Link>
      </div>
      {latest ? (
        <>
          <div className="account-app-highlight">
            <AccountIconBox icon="apps" tone="blue" />
            <div>
              <h3>{latest.name}</h3>
              <p>Данные переданы по вашему согласию через SSO.</p>
            </div>
          </div>
          <ScopeChips scopes={latest.scopes} limit={4} />
          <div className="account-card-footer">
            <time>{formatDateTime(latest.granted_at)}</time>
            <Link className="account-inline-link" href={`${ACCOUNTS_URL}/apps`}>
              Открыть журнал
              <AccountDashboardIcon name="chevron" />
            </Link>
          </div>
        </>
      ) : (
        <EmptyLine text="Передач данных по SSO пока не было." />
      )}
    </article>
  );
}

function MetricCard({
  action,
  href,
  icon,
  label,
  tone,
  value,
}: {
  action: string;
  href: string;
  icon: AccountIconName;
  label: string;
  tone: IconTone;
  value: string;
}) {
  return (
    <article className="account-card account-metric-card">
      <AccountIconBox icon={icon} tone={tone} />
      <div>
        <p>{label}</p>
        <strong>{value}</strong>
        <Link className="account-inline-link" href={href}>
          {action}
          <AccountDashboardIcon name="chevron" />
        </Link>
      </div>
    </article>
  );
}

function TransferredDataCard({ apps }: { apps: ConnectedApp[] }) {
  const scopes = uniqueScopes(apps);
  const required = scopes.filter((scope) => primaryScopes.has(scope));
  const optional = scopes.filter((scope) => !primaryScopes.has(scope));

  return (
    <article className="account-card account-data-card">
      <div className="account-section-title account-section-title-tight">
        <h2>Передаваемые данные</h2>
        <span className="account-help-badge" title="Список построен по активным согласиям пользователя.">
          i
        </span>
      </div>
      {scopes.length > 0 ? (
        <div className="account-data-groups">
          <div>
            <p>Основные</p>
            <ScopeChips scopes={required.length ? required : scopes.slice(0, 3)} />
          </div>
          <div>
            <p>Дополнительные</p>
            {optional.length ? <ScopeChips scopes={optional} /> : <span className="account-muted-note">Не выбраны</span>}
          </div>
          <InfoLine icon="lock" label="Передаются только приложениям с активным согласием." tone="muted" />
        </div>
      ) : (
        <EmptyLine text="Нет активных согласий на передачу данных." />
      )}
    </article>
  );
}

function ConnectedAppsCard({ apps }: { apps: ConnectedApp[] }) {
  const visibleApps = latestUniqueApps(apps).slice(0, 3);

  return (
    <article className="account-card account-connected-card">
      <div className="account-section-title account-section-title-tight">
        <h2>Подключенные приложения</h2>
        <Link href={`${ACCOUNTS_URL}/apps`}>Смотреть все</Link>
      </div>
      <div className="account-app-list">
        {visibleApps.map((app) => (
          <div className="account-app-row" key={app.consent_id}>
            <AccountIconBox icon="apps" tone="blue" />
            <div>
              <strong>{app.name}</strong>
              <p>{scopeCountLabel(app.scopes.length)}</p>
            </div>
            <time>{formatDateTime(app.granted_at)}</time>
          </div>
        ))}
        {visibleApps.length === 0 ? <EmptyLine text="Подключенных приложений пока нет." /> : null}
      </div>
    </article>
  );
}

function RecentLoginsCard({ history }: { history: Login[] }) {
  return (
    <article className="account-card account-logins-card">
      <div className="account-section-title account-section-title-tight">
        <h2>Последние входы</h2>
        <Link href={`${ACCOUNTS_URL}/sessions#history`}>Смотреть все</Link>
      </div>
      <div className="account-login-table">
        {history.slice(0, 4).map((item) => (
          <div className="account-login-row" data-success={item.success} key={item.id}>
            <span>
              <AccountDashboardIcon name={item.success ? "check" : "clock"} />
            </span>
            <div>
              <strong>{deviceName(item.user_agent)}</strong>
              <p>{item.ip_address || "IP не указан"}</p>
            </div>
            <time>{formatDateTime(item.created_at)}</time>
          </div>
        ))}
        {history.length === 0 ? <EmptyLine text="История входов пока пуста." /> : null}
      </div>
    </article>
  );
}

function QuickActions() {
  const actions = [
    { href: `${ACCOUNTS_URL}/apps`, icon: "security" as AccountIconName, label: "Управлять согласиями" },
    { href: `${ACCOUNTS_URL}/sessions`, icon: "desktop" as AccountIconName, label: "Открыть сессии" },
    { href: `${ACCOUNTS_URL}/security`, icon: "lock" as AccountIconName, label: "Настроить MFA" },
    { href: `${ACCOUNTS_URL}/profile`, icon: "edit" as AccountIconName, label: "Изменить профиль" },
  ];

  return (
    <article className="account-card account-actions-card">
      <h2>Быстрые действия</h2>
      <div className="account-action-list">
        {actions.map((action) => (
          <Link href={action.href} key={action.label}>
            <AccountDashboardIcon name={action.icon} />
            <span>{action.label}</span>
            <AccountDashboardIcon name="chevron" />
          </Link>
        ))}
      </div>
    </article>
  );
}

function PrivacyCenter() {
  const items = [
    {
      href: `${ACCOUNTS_URL}/apps`,
      icon: "security" as AccountIconName,
      label: "Журнал согласий",
      text: "История предоставленных доступов",
    },
    {
      href: `${ACCOUNTS_URL}/sessions#history`,
      icon: "clock" as AccountIconName,
      label: "История входов",
      text: "Успешные и неуспешные попытки",
    },
    {
      href: `${ACCOUNTS_URL}/security`,
      icon: "lock" as AccountIconName,
      label: "Безопасность",
      text: "MFA и способ входа",
    },
    {
      href: `${ROOT_URL}/privacy`,
      icon: "profile" as AccountIconName,
      label: "Политика приватности",
      text: "Как защищаются данные",
    },
  ];

  return (
    <article className="account-card account-privacy-card">
      <h2>Центр приватности</h2>
      <div className="account-privacy-grid">
        {items.map((item) => (
          <Link href={item.href} key={item.label}>
            <AccountDashboardIcon name={item.icon} />
            <span>
              <strong>{item.label}</strong>
              <small>{item.text}</small>
            </span>
          </Link>
        ))}
      </div>
    </article>
  );
}

function SsoIdentityNote() {
  return (
    <article className="account-card account-trust-card">
      <AccountIconBox icon="security" tone="blue" />
      <div>
        <h2>SSO подтверждает личность.</h2>
        <p>Приложения получают только те данные, на которые вы дали согласие.</p>
        <Link className="account-inline-link" href={`${ACCOUNTS_URL}/apps`}>
          Подробнее о согласиях
          <AccountDashboardIcon name="chevron" />
        </Link>
      </div>
    </article>
  );
}

function AccountIconBox({ icon, tone }: { icon: AccountIconName; tone: IconTone }) {
  return (
    <span className="account-dashboard-icon-box" data-tone={tone}>
      <AccountDashboardIcon name={icon} />
    </span>
  );
}

function ScopeChips({ limit, scopes }: { limit?: number; scopes: string[] }) {
  const visible = limit ? scopes.slice(0, limit) : scopes;
  const hidden = limit ? scopes.length - visible.length : 0;

  return (
    <div className="account-chip-row">
      {visible.map((scope) => (
        <span className="account-chip" key={scope}>
          {scopeLabel(scope)}
        </span>
      ))}
      {hidden > 0 ? <span className="account-chip">+{hidden}</span> : null}
    </div>
  );
}

function InfoLine({
  icon,
  label,
  tone,
}: {
  icon: AccountIconName;
  label: string;
  tone: "accent" | "muted" | "success" | "warning";
}) {
  return (
    <p className="account-info-line" data-tone={tone}>
      <AccountDashboardIcon name={icon} />
      <span>{label}</span>
    </p>
  );
}

function EmptyLine({ text }: { text: string }) {
  return <p className="account-empty-line">{text}</p>;
}

function userName(user: User): string {
  return explicitName(user) || user.email.split("@")[0];
}

function initials(user: User): string {
  const explicit = [user.first_name_cyrillic, user.last_name_cyrillic]
    .map((part) => part?.trim()[0])
    .filter(Boolean)
    .join("");
  if (explicit) return explicit.toUpperCase();
  return (user.email.trim()[0] || "A").toUpperCase();
}

function explicitName(user: User): string {
  return [user.first_name_cyrillic, user.last_name_cyrillic, user.patronymic_cyrillic]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(" ");
}

function loginMethod(user: User): string {
  if (user.authentication_method === "passkey") return "Passkey";
  return user.mfa_enabled ? "Пароль и OTP" : "Пароль";
}

function latestConsent(apps: ConnectedApp[]): ConnectedApp | undefined {
  return [...apps].sort((left, right) => new Date(right.granted_at).getTime() - new Date(left.granted_at).getTime())[0];
}

function latestUniqueApps(apps: ConnectedApp[]): ConnectedApp[] {
  const seen = new Set<string>();
  return [...apps]
    .sort((left, right) => new Date(right.granted_at).getTime() - new Date(left.granted_at).getTime())
    .filter((app) => {
      if (seen.has(app.client_id)) return false;
      seen.add(app.client_id);
      return true;
    });
}

function uniqueClientCount(apps: ConnectedApp[]): number {
  return new Set(apps.map((app) => app.client_id)).size;
}

function uniqueScopes(apps: ConnectedApp[]): string[] {
  return Array.from(new Set(apps.flatMap((app) => app.scopes))).sort((left, right) => scopeLabel(left).localeCompare(scopeLabel(right), "ru"));
}

function scopeLabel(scope: string): string {
  const labels: Record<string, string> = {
    email: "Email",
    full_name_cyrillic: "ФИО на кириллице",
    full_name_latin: "ФИО на латинице",
    locale: "Язык интерфейса",
    offline_access: "Долгий доступ",
    openid: "Уникальный ID",
    phone: "Телефон",
    profile: "Профиль",
  };
  return labels[scope] || scope;
}

function scopeCountLabel(count: number): string {
  const remainder10 = count % 10;
  const remainder100 = count % 100;
  if (remainder10 === 1 && remainder100 !== 11) return `${count} тип данных`;
  if (remainder10 >= 2 && remainder10 <= 4 && (remainder100 < 12 || remainder100 > 14)) return `${count} типа данных`;
  return `${count} типов данных`;
}

function deviceName(userAgent?: string): string {
  if (!userAgent) return "Неизвестное устройство";
  const browser = userAgent.includes("Edg/")
    ? "Edge"
    : userAgent.includes("Chrome/")
      ? "Chrome"
      : userAgent.includes("Safari/")
        ? "Safari"
        : userAgent.includes("Firefox/")
          ? "Firefox"
          : "Браузер";
  const os = userAgent.includes("Windows")
    ? "Windows"
    : userAgent.includes("iPhone")
      ? "iPhone"
      : userAgent.includes("Android")
        ? "Android"
        : userAgent.includes("Mac OS")
          ? "macOS"
          : "";
  return os ? `${os} / ${browser}` : browser;
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString("ru-RU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString("ru-RU", {
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    month: "short",
  });
}

function AccountDashboardIcon({ name }: { name: AccountIconName }) {
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
      {name === "apps" ? (
        <>
          <rect x="4" y="5" width="16" height="14" rx="2" />
          <path d="M8 9h3v3H8zM13 9h3v3h-3zM8 14h8" />
        </>
      ) : null}
      {name === "calendar" ? (
        <>
          <rect x="4" y="5" width="16" height="15" rx="2" />
          <path d="M8 3v4M16 3v4M4 10h16" />
        </>
      ) : null}
      {name === "check" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="m8.8 12.3 2.1 2.1 4.3-5" />
        </>
      ) : null}
      {name === "chevron" ? <path d="m9 6 6 6-6 6" /> : null}
      {name === "clock" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M12 8v5l3 2" />
        </>
      ) : null}
      {name === "copy" ? (
        <>
          <rect x="8" y="8" width="11" height="11" rx="2" />
          <path d="M5 15V6a1 1 0 0 1 1-1h9" />
        </>
      ) : null}
      {name === "desktop" ? (
        <>
          <rect x="4" y="5" width="16" height="11" rx="2" />
          <path d="M9 20h6M12 16v4" />
        </>
      ) : null}
      {name === "download" ? (
        <>
          <path d="M12 4v10" />
          <path d="m8 10 4 4 4-4" />
          <path d="M5 19h14" />
        </>
      ) : null}
      {name === "edit" ? (
        <>
          <path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3Z" />
          <path d="m14 8 2 2" />
        </>
      ) : null}
      {name === "globe" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M4 12h16M12 4a12 12 0 0 1 0 16M12 4a12 12 0 0 0 0 16" />
        </>
      ) : null}
      {name === "key" ? (
        <>
          <circle cx="8" cy="15" r="3" />
          <path d="m10.5 12.5 6-6M15 8l2 2M13 10l2 2" />
        </>
      ) : null}
      {name === "lock" ? (
        <>
          <rect x="5" y="10" width="14" height="10" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" />
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
      {name === "profile" ? (
        <>
          <circle cx="12" cy="8" r="3" />
          <path d="M5 20a7 7 0 0 1 14 0" />
        </>
      ) : null}
      {name === "security" ? (
        <>
          <path d="M12 3 19 6v5c0 5-3.5 8-7 10-3.5-2-7-5-7-10V6l7-3Z" />
          <path d="m9.5 12.5 1.8 1.8 3.7-4.3" />
        </>
      ) : null}
    </svg>
  );
}
