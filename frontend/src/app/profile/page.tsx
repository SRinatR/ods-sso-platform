"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiRequestError } from "@/lib/api";
import { ACCOUNTS_URL, loginUrl } from "@/lib/domains";
import { transliterateCyrillicName } from "@/lib/full-name";

type User = {
  id: string;
  email: string;
  name?: string;
  first_name_cyrillic?: string;
  last_name_cyrillic?: string;
  patronymic_cyrillic?: string;
  first_name_latin?: string;
  last_name_latin?: string;
  patronymic_latin?: string;
  full_name_cyrillic?: string;
  full_name_latin?: string;
  phone?: string;
  email_verified: boolean;
  status: string;
  role: string;
  mfa_enabled: boolean;
  authentication_method?: string;
  created_at?: string;
};

type ConnectedApp = {
  consent_id: string;
  client_id: string;
  name: string;
  scopes: string[];
  granted_at: string;
};

type ProfileForm = {
  cyrillicFirst: string;
  cyrillicLast: string;
  cyrillicPatronymic: string;
  latinFirst: string;
  latinLast: string;
  latinPatronymic: string;
  phone: string;
};

type ProfileIconName =
  | "apps"
  | "arrow"
  | "calendar"
  | "check"
  | "clock"
  | "contact"
  | "document"
  | "edit"
  | "lock"
  | "mail"
  | "phone"
  | "profile"
  | "security"
  | "shield";

const emptyForm: ProfileForm = {
  cyrillicFirst: "",
  cyrillicLast: "",
  cyrillicPatronymic: "",
  latinFirst: "",
  latinLast: "",
  latinPatronymic: "",
  phone: "",
};

export default function ProfilePage() {
  const [user, setUser] = useState<User | null>(null);
  const [apps, setApps] = useState<ConnectedApp[]>([]);
  const [form, setForm] = useState<ProfileForm>(emptyForm);
  const [latinTouched, setLatinTouched] = useState(false);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const currentUser = await api<User>("/api/v1/auth/me");
      setUser(currentUser);
      setForm(formFromUser(currentUser));
      setLatinTouched(false);

      const appsResult = await api<ConnectedApp[]>("/api/v1/account/connected-apps").catch((cause) => {
        if (cause instanceof ApiRequestError && cause.status === 401) throw cause;
        setError(cause instanceof Error ? cause.message : "Не удалось загрузить согласия");
        return [];
      });
      setApps(appsResult);
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 401) {
        window.location.href = loginUrl(window.location.href);
        return;
      }
      setError(cause instanceof Error ? cause.message : "Не удалось загрузить профиль");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    return () => window.clearTimeout(initial);
  }, [load]);

  const completion = useMemo(() => profileCompletion(form), [form]);
  const completedRequired = useMemo(() => requiredProfileFields(form).filter(Boolean).length, [form]);
  const totalRequired = requiredProfileFields(form).length;

  async function save(event: FormEvent) {
    event.preventDefault();
    setError("");
    setMessage("");
    const validation = validateRequired(form);
    if (validation) {
      setError(validation);
      return;
    }
    try {
      const updated = await api<User>("/api/v1/account/profile", {
        method: "PATCH",
        body: JSON.stringify({
          first_name_cyrillic: form.cyrillicFirst.trim(),
          last_name_cyrillic: form.cyrillicLast.trim(),
          patronymic_cyrillic: form.cyrillicPatronymic.trim() || null,
          first_name_latin: form.latinFirst.trim(),
          last_name_latin: form.latinLast.trim(),
          patronymic_latin: form.latinPatronymic.trim() || null,
          phone: form.phone.trim(),
        }),
      });
      setUser(updated);
      setForm(formFromUser(updated));
      setLatinTouched(false);
      setMessage("Профиль сохранён");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось сохранить профиль");
    }
  }

  function updateCyrillic(next: Partial<ProfileForm>) {
    setForm((current) => {
      const merged = { ...current, ...next };
      if (latinTouched) return merged;
      return {
        ...merged,
        latinFirst: transliterateCyrillicName(merged.cyrillicFirst),
        latinLast: transliterateCyrillicName(merged.cyrillicLast),
        latinPatronymic: transliterateCyrillicName(merged.cyrillicPatronymic),
      };
    });
  }

  function updateLatin(next: Partial<ProfileForm>) {
    setLatinTouched(true);
    setForm((current) => ({ ...current, ...next }));
  }

  return (
    <Shell title="Профиль">
      {error && <div className="alert error account-alert">{error}</div>}
      {message && <div className="alert success account-alert">{message}</div>}
      {loading && !user ? <section className="account-card">Загрузка профиля...</section> : null}

      {user ? (
        <div className="profile-overview-page">
          <div className="profile-overview-main">
            <ProfileHero user={user} />
            <ProfileCompletionCard
              appsCount={apps.length}
              completion={completion}
              form={form}
              user={user}
            />
            <ProfileDataSections appsCount={apps.length} form={form} user={user} />
            <ProfileEditor
              form={form}
              onFieldChange={(next) => setForm((current) => ({ ...current, ...next }))}
              onLatinChange={updateLatin}
              onReset={() => setForm(formFromUser(user))}
              onSubmit={save}
              onUpdateCyrillic={updateCyrillic}
              user={user}
            />
          </div>

          <aside className="profile-overview-side">
            <ProfileStatusCard
              completedRequired={completedRequired}
              completion={completion}
              totalRequired={totalRequired}
              user={user}
            />
            <ProfileQuickActions />
            <ProfileProtectionCard />
          </aside>
        </div>
      ) : null}
    </Shell>
  );
}

function ProfileHero({ user }: { user: User }) {
  return (
    <section className="account-card profile-hero-card">
      <div className="profile-avatar-preview profile-hero-avatar" aria-hidden="true">
        {initials(user)}
      </div>
      <div className="profile-hero-main">
        <div className="profile-hero-title">
          <div>
            <h2>{userName(user)}</h2>
            <p>{user.email}</p>
          </div>
          <span className="profile-status-chip" data-tone={profileIsComplete(user) ? "green" : "amber"}>
            <ProfileIcon name={profileIsComplete(user) ? "check" : "clock"} />
            {profileIsComplete(user) ? "Заполнен" : "Нужно заполнить"}
          </span>
        </div>
        <div className="profile-meta-row">
          <ProfileInfo icon="calendar" text={user.created_at ? `Пользователь с ${formatDate(user.created_at)}` : "Дата регистрации недоступна"} />
          <ProfileInfo icon="security" text={user.status === "active" ? "Аккаунт активен" : `Статус: ${user.status}`} />
        </div>
      </div>
      <a className="account-link-button profile-edit-button" href="#profile-editor">
        <ProfileIcon name="edit" />
        Редактировать профиль
      </a>
    </section>
  );
}

function ProfileCompletionCard({
  appsCount,
  completion,
  form,
  user,
}: {
  appsCount: number;
  completion: number;
  form: ProfileForm;
  user: User;
}) {
  const phoneReady = Boolean(form.phone.trim());

  return (
    <section className="account-card profile-completion-card">
      <h2>Заполнение профиля</h2>
      <div className="profile-completion-content">
        <div
          className="profile-progress-ring profile-progress-ring-large"
          style={{ "--profile-progress": `${completion}%` } as CSSProperties}
        >
          <strong>{completion}%</strong>
          <span>заполнено</span>
        </div>
        <div className="profile-task-list">
          <ProfileTask
            href="#profile-editor"
            icon="document"
            label="Заполнить обязательные поля"
            status={completion === 100 ? "Готово" : "Нужно"}
            tone={completion === 100 ? "green" : "amber"}
          />
          <ProfileTask
            href="#profile-editor"
            icon="phone"
            label={phoneReady ? "Телефон указан" : "Указать телефон"}
            status={phoneReady ? "Готово" : "Нужно"}
            tone={phoneReady ? "green" : "amber"}
          />
          <ProfileTask
            href={`${ACCOUNTS_URL}/security`}
            icon="shield"
            label={user.mfa_enabled ? "MFA включена" : "Настроить MFA"}
            status={user.mfa_enabled ? "Готово" : "Рекомендуется"}
            tone={user.mfa_enabled ? "green" : "blue"}
          />
          <ProfileTask
            href={`${ACCOUNTS_URL}/apps`}
            icon="apps"
            label="Согласия и доступы"
            status={appsCount > 0 ? `${appsCount} активных` : "Нет активных"}
            tone={appsCount > 0 ? "green" : "slate"}
          />
        </div>
      </div>
    </section>
  );
}

function ProfileDataSections({
  appsCount,
  form,
  user,
}: {
  appsCount: number;
  form: ProfileForm;
  user: User;
}) {
  const rows = [
    {
      href: "#profile-editor",
      icon: "profile" as ProfileIconName,
      score: fraction([
        form.cyrillicFirst,
        form.cyrillicLast,
        form.cyrillicPatronymic,
        form.latinFirst,
        form.latinLast,
        form.latinPatronymic,
      ]),
      text: "ФИО на кириллице и латинице",
      title: "Основная информация",
    },
    {
      href: "#profile-editor",
      icon: "phone" as ProfileIconName,
      score: fraction([user.email, form.phone]),
      text: "Email и номер телефона",
      title: "Контакты",
    },
    {
      href: `${ACCOUNTS_URL}/security`,
      icon: "security" as ProfileIconName,
      score: fraction([user.email_verified, user.mfa_enabled, user.authentication_method]),
      text: "Email, MFA и способ входа",
      title: "Безопасность",
    },
    {
      href: `${ACCOUNTS_URL}/apps`,
      icon: "apps" as ProfileIconName,
      score: appsCount > 0 ? `${appsCount} активных` : "Нет активных",
      text: "Приложения, которым вы дали доступ",
      title: "Согласия и доступы",
    },
    {
      href: `${ACCOUNTS_URL}/sessions`,
      icon: "clock" as ProfileIconName,
      score: "Журнал",
      text: "Активные сессии и история входов",
      title: "Активность",
    },
  ];

  return (
    <section className="account-card profile-data-card">
      <h2>Данные профиля</h2>
      <div className="profile-data-list">
        {rows.map((row) => (
          <Link className="profile-data-row" href={row.href} key={row.title}>
            <span aria-hidden="true">
              <ProfileIcon name={row.icon} />
            </span>
            <div>
              <strong>{row.title}</strong>
              <p>{row.text}</p>
            </div>
            <em>{row.score}</em>
            <ProfileIcon name="arrow" />
          </Link>
        ))}
      </div>
    </section>
  );
}

function ProfileEditor({
  form,
  onFieldChange,
  onLatinChange,
  onReset,
  onSubmit,
  onUpdateCyrillic,
  user,
}: {
  form: ProfileForm;
  onFieldChange: (next: Partial<ProfileForm>) => void;
  onLatinChange: (next: Partial<ProfileForm>) => void;
  onReset: () => void;
  onSubmit: (event: FormEvent) => void;
  onUpdateCyrillic: (next: Partial<ProfileForm>) => void;
  user: User;
}) {
  return (
    <form className="account-card profile-editor-card" id="profile-editor" onSubmit={onSubmit}>
      <div className="profile-card-title">
        <span aria-hidden="true">
          <ProfileIcon name="edit" />
        </span>
        <div>
          <h2>Редактирование профиля</h2>
          <p>Обязательные поля нужны для выпуска корректного ID-профиля.</p>
        </div>
      </div>

      <div className="profile-field-groups">
        <fieldset>
          <legend>ФИО (кириллица)</legend>
          <div className="profile-field-grid three">
            <RequiredLabel label="Имя">
              <input
                maxLength={80}
                required
                value={form.cyrillicFirst}
                onChange={(event) => onUpdateCyrillic({ cyrillicFirst: event.target.value })}
              />
            </RequiredLabel>
            <RequiredLabel label="Фамилия">
              <input
                maxLength={80}
                required
                value={form.cyrillicLast}
                onChange={(event) => onUpdateCyrillic({ cyrillicLast: event.target.value })}
              />
            </RequiredLabel>
            <label>
              Отчество
              <input
                maxLength={80}
                value={form.cyrillicPatronymic}
                onChange={(event) => onUpdateCyrillic({ cyrillicPatronymic: event.target.value })}
              />
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend>Full name (Latin)</legend>
          <div className="profile-field-grid three">
            <RequiredLabel label="First name">
              <input
                maxLength={80}
                pattern="[A-Za-z\\s'’\\-]+"
                required
                value={form.latinFirst}
                onChange={(event) => onLatinChange({ latinFirst: event.target.value })}
              />
            </RequiredLabel>
            <RequiredLabel label="Last name">
              <input
                maxLength={80}
                pattern="[A-Za-z\\s'’\\-]+"
                required
                value={form.latinLast}
                onChange={(event) => onLatinChange({ latinLast: event.target.value })}
              />
            </RequiredLabel>
            <label>
              Patronymic
              <input
                maxLength={80}
                pattern="[A-Za-z\\s'’\\-]+"
                value={form.latinPatronymic}
                onChange={(event) => onLatinChange({ latinPatronymic: event.target.value })}
              />
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend>Контакты</legend>
          <div className="profile-field-grid">
            <RequiredLabel label="Телефон">
              <input
                maxLength={32}
                placeholder="+998 90 123 45 67"
                required
                type="tel"
                value={form.phone}
                onChange={(event) => onFieldChange({ phone: event.target.value })}
              />
            </RequiredLabel>
            <label>
              Email
              <input value={user.email} disabled />
            </label>
          </div>
        </fieldset>

        <p className="profile-required-note">
          <span>*</span> обязательные поля
        </p>
      </div>

      <footer className="profile-form-actions profile-editor-actions">
        <button className="button secondary" type="button" onClick={onReset}>
          Вернуть сохранённые данные
        </button>
        <button className="button" type="submit">
          Сохранить профиль
          <ProfileIcon name="arrow" />
        </button>
      </footer>
    </form>
  );
}

function ProfileStatusCard({
  completedRequired,
  completion,
  totalRequired,
  user,
}: {
  completedRequired: number;
  completion: number;
  totalRequired: number;
  user: User;
}) {
  const rows = [
    { ok: completion === 100, text: completion === 100 ? "Профиль заполнен" : "Профиль требует заполнения" },
    { ok: user.email_verified, text: user.email_verified ? "Email подтверждён" : "Email не подтверждён" },
    { ok: Boolean(user.phone), text: user.phone ? "Телефон указан" : "Телефон не указан" },
    { ok: user.status === "active", text: user.status === "active" ? "Аккаунт активен" : `Статус: ${user.status}` },
  ];

  return (
    <section className="account-card profile-status-card">
      <h2>Статус профиля</h2>
      <div className="profile-status-list">
        {rows.map((row) => (
          <div className="profile-status-row" data-ok={row.ok} key={row.text}>
            <span>
              <ProfileIcon name={row.ok ? "check" : "clock"} />
            </span>
            <p>{row.text}</p>
          </div>
        ))}
      </div>
      <dl className="profile-status-meta">
        <div>
          <dt>Обязательные поля</dt>
          <dd>
            {completedRequired} / {totalRequired}
          </dd>
        </div>
        <div>
          <dt>Дата регистрации</dt>
          <dd>{user.created_at ? formatDate(user.created_at) : "Недоступна"}</dd>
        </div>
      </dl>
    </section>
  );
}

function ProfileQuickActions() {
  const actions = [
    { href: "#profile-editor", icon: "edit" as ProfileIconName, label: "Редактировать профиль" },
    { href: `${ACCOUNTS_URL}/security`, icon: "lock" as ProfileIconName, label: "Безопасность" },
    { href: `${ACCOUNTS_URL}/apps`, icon: "apps" as ProfileIconName, label: "Согласия и доступы" },
    { href: `${ACCOUNTS_URL}/sessions`, icon: "clock" as ProfileIconName, label: "Сессии и входы" },
  ];

  return (
    <section className="account-card profile-actions-card">
      <h2>Быстрые действия</h2>
      <div className="profile-action-list">
        {actions.map((action) => (
          <Link href={action.href} key={action.label}>
            <ProfileIcon name={action.icon} />
            <span>{action.label}</span>
            <ProfileIcon name="arrow" />
          </Link>
        ))}
      </div>
    </section>
  );
}

function ProfileProtectionCard() {
  return (
    <section className="account-card profile-protection-card">
      <span aria-hidden="true">
        <ProfileIcon name="shield" />
      </span>
      <div>
        <h2>Ваши данные защищены</h2>
        <p>Передача данных выполняется только по активным согласиям.</p>
        <Link className="account-inline-link" href={`${ACCOUNTS_URL}/apps`}>
          Подробнее
          <ProfileIcon name="arrow" />
        </Link>
      </div>
    </section>
  );
}

function ProfileTask({
  href,
  icon,
  label,
  status,
  tone,
}: {
  href: string;
  icon: ProfileIconName;
  label: string;
  status: string;
  tone: "amber" | "blue" | "green" | "slate";
}) {
  return (
    <Link className="profile-task-row" href={href}>
      <span aria-hidden="true">
        <ProfileIcon name={icon} />
      </span>
      <strong>{label}</strong>
      <em data-tone={tone}>{status}</em>
      <ProfileIcon name="arrow" />
    </Link>
  );
}

function ProfileInfo({ icon, text }: { icon: ProfileIconName; text: string }) {
  return (
    <span>
      <ProfileIcon name={icon} />
      {text}
    </span>
  );
}

function RequiredLabel({
  children,
  label,
}: {
  children: ReactNode;
  label: string;
}) {
  return (
    <label>
      <span>
        {label} <em>*</em>
      </span>
      {children}
    </label>
  );
}

function formFromUser(user: User): ProfileForm {
  return {
    cyrillicFirst: user.first_name_cyrillic || "",
    cyrillicLast: user.last_name_cyrillic || "",
    cyrillicPatronymic: user.patronymic_cyrillic || "",
    latinFirst: user.first_name_latin || "",
    latinLast: user.last_name_latin || "",
    latinPatronymic: user.patronymic_latin || "",
    phone: user.phone || "",
  };
}

function validateRequired(form: ProfileForm): string {
  if (!form.cyrillicFirst.trim() || !form.cyrillicLast.trim()) {
    return "Заполните имя и фамилию на кириллице";
  }
  if (!form.latinFirst.trim() || !form.latinLast.trim()) {
    return "Заполните first name и last name латиницей";
  }
  if (!form.phone.trim()) {
    return "Укажите телефон";
  }
  return "";
}

function requiredProfileFields(form: ProfileForm): boolean[] {
  return [
    Boolean(form.cyrillicFirst.trim()),
    Boolean(form.cyrillicLast.trim()),
    Boolean(form.latinFirst.trim()),
    Boolean(form.latinLast.trim()),
    Boolean(form.phone.trim()),
  ];
}

function profileCompletion(form: ProfileForm): number {
  const required = requiredProfileFields(form);
  return Math.round((required.filter(Boolean).length / required.length) * 100);
}

function profileIsComplete(user: User): boolean {
  return requiredProfileFields(formFromUser(user)).every(Boolean);
}

function fraction(values: Array<string | boolean | undefined | null>): string {
  const total = values.length;
  const complete = values.filter((value) => Boolean(typeof value === "string" ? value.trim() : value)).length;
  return `${complete}/${total}`;
}

function userName(user: User): string {
  return explicitName(user) || user.email.split("@")[0];
}

function explicitName(user: User): string {
  return [user.first_name_cyrillic, user.last_name_cyrillic, user.patronymic_cyrillic]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(" ");
}

function initials(user: User): string {
  const explicit = [user.first_name_cyrillic, user.last_name_cyrillic]
    .map((part) => part?.trim()[0])
    .filter(Boolean)
    .join("");
  if (explicit) return explicit.toUpperCase();
  return (user.email.trim()[0] || "I").toUpperCase();
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString("ru-RU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

function ProfileIcon({ name }: { name: ProfileIconName }) {
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
      {name === "arrow" ? <path d="M5 12h14M13 6l6 6-6 6" /> : null}
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
      {name === "clock" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M12 8v5l3 2" />
        </>
      ) : null}
      {name === "contact" ? (
        <>
          <rect x="4" y="5" width="16" height="14" rx="2" />
          <path d="M8 10h4M8 14h8" />
        </>
      ) : null}
      {name === "document" ? (
        <>
          <rect x="5" y="4" width="14" height="16" rx="2" />
          <path d="M9 9h6M9 13h6M9 17h3" />
        </>
      ) : null}
      {name === "edit" ? (
        <>
          <path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3Z" />
          <path d="m14 8 2 2" />
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
      {name === "security" || name === "shield" ? (
        <>
          <path d="M12 3 19 6v5c0 5-3.5 8-7 10-3.5-2-7-5-7-10V6l7-3Z" />
          <path d="m9.5 12.5 1.8 1.8 3.7-4.3" />
        </>
      ) : null}
    </svg>
  );
}
