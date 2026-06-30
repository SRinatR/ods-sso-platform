"use client";

import Link from "next/link";
import type { CSSProperties } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiRequestError } from "@/lib/api";
import { ACCOUNTS_URL, loginUrl } from "@/lib/domains";

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

type AccountIconName =
  | "apps"
  | "calendar"
  | "check"
  | "chevron"
  | "clock"
  | "desktop"
  | "globe"
  | "mail"
  | "phone"
  | "security";

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

  const displayName = useMemo(() => (user ? greetingName(user) : "пользователь"), [user]);

  return (
    <Shell
      title={user ? `Здравствуйте, ${displayName}` : "Загрузка кабинета"}
      subtitle="Управляйте аккаунтом, безопасностью и доступом к приложениям."
    >
      {error && (
        <div className="alert error account-alert">
          {error}
          <button className="text-button" onClick={() => void load()} type="button">
            Повторить
          </button>
        </div>
      )}

      {loading && !user ? <section className="account-card">Загрузка данных аккаунта…</section> : null}

      {user ? (
        <div className="account-dashboard">
          <section className="account-dashboard-grid account-dashboard-grid-top">
            <ProfileSummary user={user} />
            <SecuritySummary user={user} />
          </section>

          <section className="account-dashboard-grid">
            <SessionsSummary sessions={data.sessions} />
            <LoginHistorySummary history={data.history} />
          </section>

          <section className="account-dashboard-single">
            <ConsentsSummary apps={data.apps} />
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
    <article className="account-card account-profile-card">
      <div className="account-avatar" aria-hidden="true">
        {initials(user)}
      </div>
      <div className="account-profile-main">
        <div className="account-card-heading">
          <div>
            <h2>{userName(user)}</h2>
            <p>{user.email}</p>
          </div>
          <Link className="account-link-button" href={`${ACCOUNTS_URL}/profile`}>
            Редактировать профиль
          </Link>
        </div>
        <div className="account-profile-list">
          <InfoLine
            icon="mail"
            label={user.email_verified ? "Email подтвержден" : "Email ожидает подтверждения"}
            tone={user.email_verified ? "success" : "warning"}
          />
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
          <InfoLine icon="globe" label="Язык интерфейса: Русский" tone="accent" />
        </div>
      </div>
    </article>
  );
}

function SecuritySummary({ user }: { user: User }) {
  const checks = [
    { label: "Двухфакторная аутентификация", value: user.mfa_enabled ? "Включена" : "Не настроена", ok: user.mfa_enabled },
    { label: "Привязанный email", value: user.email_verified ? "Подтвержден" : "Ожидает", ok: user.email_verified },
    { label: "Привязанный телефон", value: user.phone ? "Подтвержден" : "Не указан", ok: Boolean(user.phone) },
    { label: "Способ входа", value: loginMethod(user), ok: true },
    { label: "Статус аккаунта", value: user.status, ok: user.status === "active" },
  ];
  const score = Math.round((checks.filter((item) => item.ok).length / checks.length) * 100);

  return (
    <article className="account-card account-security-card">
      <div className="account-section-title">
        <h2>Уровень безопасности</h2>
        <Link href={`${ACCOUNTS_URL}/security`}>
          Проверить
          <AccountDashboardIcon name="chevron" />
        </Link>
      </div>
      <div className="account-security-content">
        <div className="account-security-ring" style={{ "--score": `${score}%` } as CSSProperties}>
          <span>
            <AccountDashboardIcon name="security" />
          </span>
          <strong>{score >= 80 ? "Высокий" : score >= 60 ? "Средний" : "Низкий"}</strong>
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
        </div>
      </div>
      <Link className="account-inline-link" href={`${ACCOUNTS_URL}/security`}>
        Перейти в настройки безопасности
        <AccountDashboardIcon name="chevron" />
      </Link>
    </article>
  );
}

function SessionsSummary({ sessions }: { sessions: Session[] }) {
  return (
    <article className="account-card">
      <div className="account-section-title">
        <div>
          <h2>Активные сессии</h2>
          <p>Устройства, где сейчас действует вход в аккаунт. Лишний доступ можно завершить.</p>
        </div>
        <Link href={`${ACCOUNTS_URL}/sessions`}>Все сессии</Link>
      </div>
      <div className="account-list">
        {sessions.slice(0, 4).map((session) => (
          <div className="account-list-row" key={session.id}>
            <span className="account-list-icon" data-tone={session.current ? "blue" : "violet"}>
              <AccountDashboardIcon name="desktop" />
            </span>
            <div>
              <strong>{deviceName(session.user_agent)}</strong>
              <p>{session.ip_address ? `IP ${session.ip_address}` : "IP не указан"}</p>
            </div>
            <time>{session.current ? "Сейчас" : formatDateTime(session.last_seen_at)}</time>
          </div>
        ))}
        {sessions.length === 0 ? <EmptyLine text="Активные сессии не найдены." /> : null}
      </div>
      <Link className="account-inline-link" href={`${ACCOUNTS_URL}/sessions`}>
        Управление сессиями
        <AccountDashboardIcon name="chevron" />
      </Link>
    </article>
  );
}

function ConsentsSummary({ apps }: { apps: ConnectedApp[] }) {
  const latest = apps
    .map((app) => app.granted_at)
    .sort((left, right) => new Date(right).getTime() - new Date(left).getTime())[0];

  return (
    <article className="account-card account-consents-card">
      <div className="account-consent-total">
        <span>
          <AccountDashboardIcon name="security" />
        </span>
        <strong>{apps.length}</strong>
        <p>Активных согласий</p>
      </div>
      <div className="account-consent-details">
        <InfoLine
          icon="clock"
          label={latest ? `Последнее обновление: ${formatDateTime(latest)}` : "Согласия еще не выдавались"}
          tone="muted"
        />
        <InfoLine icon="calendar" label="Доступы действуют до отзыва пользователем" tone="accent" />
        <Link className="account-inline-link" href={`${ACCOUNTS_URL}/apps`}>
          Управление передачей данных и доступами
          <AccountDashboardIcon name="chevron" />
        </Link>
      </div>
    </article>
  );
}

function LoginHistorySummary({ history }: { history: Login[] }) {
  return (
    <article className="account-card">
      <div className="account-section-title">
        <div>
          <h2>История входов</h2>
          <p>Журнал успешных и неуспешных попыток входа. Это не список активных устройств.</p>
        </div>
        <Link href={`${ACCOUNTS_URL}/sessions#history`}>Вся история</Link>
      </div>
      <div className="account-history-list">
        {history.slice(0, 3).map((item) => (
          <div className="account-history-row" data-success={item.success} key={item.id}>
            <span>
              <AccountDashboardIcon name="check" />
            </span>
            <div>
              <strong>{item.success ? "Успешный вход" : item.failure_reason || "Ошибка входа"}</strong>
              <p>{item.ip_address ? `IP ${item.ip_address}` : deviceName(item.user_agent)}</p>
            </div>
            <time>{formatDateTime(item.created_at)}</time>
          </div>
        ))}
        {history.length === 0 ? <EmptyLine text="История входов пока пуста." /> : null}
      </div>
      <Link className="account-inline-link" href={`${ACCOUNTS_URL}/sessions#history`}>
        Посмотреть всю историю
        <AccountDashboardIcon name="chevron" />
      </Link>
    </article>
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

function greetingName(user: User): string {
  return user.first_name_cyrillic || user.name || user.email.split("@")[0];
}

function initials(user: User): string {
  const first = user.first_name_cyrillic || user.name || "";
  const last = user.last_name_cyrillic || "";
  const explicit = [first, last].map((part) => part.trim()[0]).filter(Boolean).join("");
  if (explicit) return explicit.toUpperCase();
  return (user.email.trim()[0] || "A").toUpperCase();
}

function explicitName(user: User): string {
  return [user.first_name_cyrillic || user.name, user.last_name_cyrillic, user.patronymic_cyrillic]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(" ");
}

function loginMethod(user: User): string {
  if (user.authentication_method === "passkey") return "Passkey";
  return user.mfa_enabled ? "Пароль и OTP" : "Пароль";
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
  return os ? `${browser} на ${os}` : browser;
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
      {name === "desktop" ? (
        <>
          <rect x="4" y="5" width="16" height="11" rx="2" />
          <path d="M9 20h6M12 16v4" />
        </>
      ) : null}
      {name === "globe" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M4 12h16M12 4a12 12 0 0 1 0 16M12 4a12 12 0 0 0 0 16" />
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
      {name === "security" ? (
        <>
          <path d="M12 3 19 6v5c0 5-3.5 8-7 10-3.5-2-7-5-7-10V6l7-3Z" />
          <path d="m9.5 12.5 1.8 1.8 3.7-4.3" />
        </>
      ) : null}
    </svg>
  );
}
