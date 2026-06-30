"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { api, ApiRequestError } from "@/lib/api";
import { ACCOUNTS_URL, ROOT_URL, loginUrl } from "@/lib/domains";
import { transliterateCyrillicName } from "@/lib/full-name";

type User = {
  id: string;
  email: string;
  first_name_cyrillic?: string;
  last_name_cyrillic?: string;
  patronymic_cyrillic?: string;
  first_name_latin?: string;
  last_name_latin?: string;
  patronymic_latin?: string;
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

type EditSection = "name" | "contacts";

type EditIconName =
  | "apps"
  | "arrow"
  | "back"
  | "calendar"
  | "check"
  | "clock"
  | "document"
  | "edit"
  | "help"
  | "lock"
  | "mail"
  | "phone"
  | "profile"
  | "security";

const emptyForm: ProfileForm = {
  cyrillicFirst: "",
  cyrillicLast: "",
  cyrillicPatronymic: "",
  latinFirst: "",
  latinLast: "",
  latinPatronymic: "",
  phone: "",
};

export default function ProfileEditPage() {
  const [user, setUser] = useState<User | null>(null);
  const [apps, setApps] = useState<ConnectedApp[]>([]);
  const [form, setForm] = useState<ProfileForm>(emptyForm);
  const [section, setSection] = useState<EditSection>(() => {
    if (typeof window === "undefined") return "name";
    return new URLSearchParams(window.location.search).get("section") === "contacts" ? "contacts" : "name";
  });
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

  const savedForm = useMemo(() => (user ? formFromUser(user) : emptyForm), [user]);
  const dirty = user ? !sameForm(form, savedForm) : false;
  const completion = useMemo(() => profileCompletion(form), [form]);

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
      setMessage("Изменения сохранены");
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

  function reset() {
    setForm(savedForm);
    setLatinTouched(false);
    setError("");
    setMessage("");
  }

  return (
    <main className="profile-edit-screen">
      <header className="profile-edit-topbar">
        <Link className="profile-edit-brand" href={ACCOUNTS_URL}>
          <span aria-hidden="true">
            <EditIcon name="security" />
          </span>
          <strong>ODS SSO</strong>
        </Link>
        <div className="profile-edit-top-actions">
          <Link href={`${ROOT_URL}/privacy`}>
            <EditIcon name="help" />
            Помощь
          </Link>
          {user ? (
            <span className="profile-edit-account-chip">
              <span aria-hidden="true">{initials(user)}</span>
              <strong>{shortName(user)}</strong>
            </span>
          ) : null}
        </div>
      </header>

      <div className="profile-edit-container">
        <Link className="profile-edit-back" href={`${ACCOUNTS_URL}/profile`}>
          <EditIcon name="back" />
          Вернуться в профиль
        </Link>

        {error && <div className="alert error">{error}</div>}
        {message && <div className="alert success">{message}</div>}
        {loading && !user ? <section className="profile-edit-card">Загрузка профиля...</section> : null}

        {user ? (
          <div className="profile-edit-grid">
            <div className="profile-edit-main">
              <ProfileEditHero user={user} />

              <form className="profile-edit-form-card" onSubmit={save}>
                <div className="profile-edit-form-header">
                  <div>
                    <h1>Основная информация</h1>
                    <p>Редактируются только поля, которые уже хранятся отдельно в профиле.</p>
                  </div>
                  <span>
                    <EditIcon name="edit" />
                    Редактирование профиля
                  </span>
                </div>

                <div className="profile-edit-tabs" role="tablist" aria-label="Разделы редактирования профиля">
                  <button
                    aria-selected={section === "name"}
                    className={section === "name" ? "active" : undefined}
                    onClick={() => setSection("name")}
                    role="tab"
                    type="button"
                  >
                    <EditIcon name="profile" />
                    ФИО
                  </button>
                  <button
                    aria-selected={section === "contacts"}
                    className={section === "contacts" ? "active" : undefined}
                    onClick={() => setSection("contacts")}
                    role="tab"
                    type="button"
                  >
                    <EditIcon name="phone" />
                    Контакты
                  </button>
                  <Link href={`${ACCOUNTS_URL}/security`}>
                    <EditIcon name="lock" />
                    Безопасность
                  </Link>
                  <Link href={`${ACCOUNTS_URL}/apps`}>
                    <EditIcon name="apps" />
                    Согласия
                  </Link>
                </div>

                <div className="profile-edit-panel">
                  {section === "name" ? (
                    <NameFields
                      form={form}
                      onLatinChange={updateLatin}
                      onUpdateCyrillic={updateCyrillic}
                    />
                  ) : (
                    <ContactFields
                      email={user.email}
                      form={form}
                      onChange={(next) => setForm((current) => ({ ...current, ...next }))}
                    />
                  )}
                  <p className="profile-edit-note">
                    <EditIcon name="help" />
                    Убедитесь, что данные написаны так же, как в документе, удостоверяющем личность.
                  </p>
                </div>

                <div className="profile-edit-savebar">
                  <p data-dirty={dirty}>
                    <EditIcon name={dirty ? "clock" : "check"} />
                    {dirty ? "Есть несохранённые изменения" : "Все изменения сохранены"}
                  </p>
                  <div>
                    <button className="button secondary" disabled={!dirty} onClick={reset} type="button">
                      Отмена
                    </button>
                    <button className="button" disabled={!dirty} type="submit">
                      Сохранить изменения
                    </button>
                  </div>
                </div>
              </form>

              <div className="profile-edit-bottom-nav">
                {section === "contacts" ? (
                  <button type="button" onClick={() => setSection("name")}>
                    <EditIcon name="back" />
                    Назад: ФИО
                  </button>
                ) : (
                  <Link href={`${ACCOUNTS_URL}/profile`}>
                    <EditIcon name="back" />
                    Назад к профилю
                  </Link>
                )}
                {section === "name" ? (
                  <button type="button" onClick={() => setSection("contacts")}>
                    Далее: Контакты
                    <EditIcon name="arrow" />
                  </button>
                ) : (
                  <Link href={`${ACCOUNTS_URL}/profile`}>
                    Готово
                    <EditIcon name="arrow" />
                  </Link>
                )}
              </div>
            </div>

            <aside className="profile-edit-side">
              <ProfileEditSections
                appsCount={apps.length}
                completion={completion}
                form={form}
                section={section}
                setSection={setSection}
                user={user}
              />
            </aside>
          </div>
        ) : null}
      </div>
    </main>
  );
}

function ProfileEditHero({ user }: { user: User }) {
  return (
    <section className="profile-edit-card profile-edit-hero">
      <div className="profile-avatar-preview profile-edit-avatar" aria-hidden="true">
        {initials(user)}
      </div>
      <div>
        <h2>{userName(user)}</h2>
        <p>{user.email}</p>
      </div>
      <span className="profile-status-chip" data-tone={user.email_verified ? "green" : "amber"}>
        <EditIcon name={user.email_verified ? "check" : "clock"} />
        {user.email_verified ? "Подтверждён" : "Email не подтверждён"}
      </span>
    </section>
  );
}

function NameFields({
  form,
  onLatinChange,
  onUpdateCyrillic,
}: {
  form: ProfileForm;
  onLatinChange: (next: Partial<ProfileForm>) => void;
  onUpdateCyrillic: (next: Partial<ProfileForm>) => void;
}) {
  return (
    <fieldset className="profile-edit-fieldset">
      <legend>ФИО</legend>
      <div className="profile-field-grid three">
        <RequiredLabel label="Имя">
          <input
            autoComplete="given-name"
            maxLength={80}
            name="cyrillicFirst"
            required
            value={form.cyrillicFirst}
            onChange={(event) => onUpdateCyrillic({ cyrillicFirst: event.target.value })}
          />
        </RequiredLabel>
        <RequiredLabel label="Фамилия">
          <input
            autoComplete="family-name"
            maxLength={80}
            name="cyrillicLast"
            required
            value={form.cyrillicLast}
            onChange={(event) => onUpdateCyrillic({ cyrillicLast: event.target.value })}
          />
        </RequiredLabel>
        <label>
          Отчество
          <input
            autoComplete="additional-name"
            maxLength={80}
            name="cyrillicPatronymic"
            value={form.cyrillicPatronymic}
            onChange={(event) => onUpdateCyrillic({ cyrillicPatronymic: event.target.value })}
          />
        </label>
        <RequiredLabel label="Имя (латиница)">
          <input
            autoComplete="given-name"
            maxLength={80}
            name="latinFirst"
            pattern="[A-Za-z\\s'’\\-]+"
            required
            value={form.latinFirst}
            onChange={(event) => onLatinChange({ latinFirst: event.target.value })}
          />
        </RequiredLabel>
        <RequiredLabel label="Фамилия (латиница)">
          <input
            autoComplete="family-name"
            maxLength={80}
            name="latinLast"
            pattern="[A-Za-z\\s'’\\-]+"
            required
            value={form.latinLast}
            onChange={(event) => onLatinChange({ latinLast: event.target.value })}
          />
        </RequiredLabel>
        <label>
          Отчество (латиница)
          <input
            autoComplete="additional-name"
            maxLength={80}
            name="latinPatronymic"
            pattern="[A-Za-z\\s'’\\-]+"
            value={form.latinPatronymic}
            onChange={(event) => onLatinChange({ latinPatronymic: event.target.value })}
          />
        </label>
      </div>
    </fieldset>
  );
}

function ContactFields({
  email,
  form,
  onChange,
}: {
  email: string;
  form: ProfileForm;
  onChange: (next: Partial<ProfileForm>) => void;
}) {
  return (
    <fieldset className="profile-edit-fieldset">
      <legend>Контакты</legend>
      <div className="profile-field-grid">
        <RequiredLabel label="Телефон">
          <input
            autoComplete="tel"
            maxLength={32}
            name="phone"
            placeholder="+998 90 123 45 67"
            required
            type="tel"
            value={form.phone}
            onChange={(event) => onChange({ phone: event.target.value })}
          />
        </RequiredLabel>
        <label>
          Email
          <input autoComplete="email" name="email" value={email} disabled />
        </label>
      </div>
    </fieldset>
  );
}

function ProfileEditSections({
  appsCount,
  completion,
  form,
  section,
  setSection,
  user,
}: {
  appsCount: number;
  completion: number;
  form: ProfileForm;
  section: EditSection;
  setSection: (section: EditSection) => void;
  user: User;
}) {
  const nameProgress = progressFraction([
    form.cyrillicFirst,
    form.cyrillicLast,
    form.cyrillicPatronymic,
    form.latinFirst,
    form.latinLast,
    form.latinPatronymic,
  ]);
  const contactProgress = progressFraction([user.email, form.phone]);
  const securityProgress = progressFraction([user.email_verified, user.mfa_enabled, user.authentication_method]);

  return (
    <section className="profile-edit-card profile-edit-sections-card">
      <h2>Разделы профиля</h2>
      <div className="profile-edit-section-list">
        <ProfileEditSectionRow
          active={section === "name"}
          icon="profile"
          label="Основная информация"
          onClick={() => setSection("name")}
          progress={nameProgress.percent}
          score={nameProgress.label}
        />
        <ProfileEditSectionRow
          active={section === "contacts"}
          icon="phone"
          label="Контакты"
          onClick={() => setSection("contacts")}
          progress={contactProgress.percent}
          score={contactProgress.label}
        />
        <ProfileEditSectionRow
          href={`${ACCOUNTS_URL}/security`}
          icon="lock"
          label="Безопасность"
          progress={securityProgress.percent}
          score={securityProgress.label}
        />
        <ProfileEditSectionRow
          href={`${ACCOUNTS_URL}/apps`}
          icon="apps"
          label="Согласия и доступы"
          progress={appsCount > 0 ? 100 : 0}
          score={appsCount > 0 ? `${appsCount} активных` : "Нет"}
        />
        <ProfileEditSectionRow
          href={`${ACCOUNTS_URL}/sessions`}
          icon="clock"
          label="Активность"
          progress={100}
          score="Журнал"
        />
      </div>
      <div className="profile-edit-completion-mini">
        <span>{completion}%</span>
        <p>обязательные поля профиля</p>
      </div>
    </section>
  );
}

function ProfileEditSectionRow({
  active,
  href,
  icon,
  label,
  onClick,
  progress,
  score,
}: {
  active?: boolean;
  href?: string;
  icon: EditIconName;
  label: string;
  onClick?: () => void;
  progress: number;
  score: string;
}) {
  const content = (
    <>
      <span aria-hidden="true">
        <EditIcon name={icon} />
      </span>
      <strong>{label}</strong>
      <i>
        <b style={{ "--profile-edit-row-progress": `${progress}%` } as CSSProperties} />
      </i>
      <em>{score}</em>
      <EditIcon name="arrow" />
    </>
  );

  if (href) {
    return (
      <Link className="profile-edit-section-row" href={href}>
        {content}
      </Link>
    );
  }
  return (
    <button className="profile-edit-section-row" data-active={active} onClick={onClick} type="button">
      {content}
    </button>
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
    return "Заполните имя и фамилию латиницей";
  }
  if (!form.phone.trim()) {
    return "Укажите телефон";
  }
  return "";
}

function sameForm(left: ProfileForm, right: ProfileForm): boolean {
  return (
    left.cyrillicFirst === right.cyrillicFirst &&
    left.cyrillicLast === right.cyrillicLast &&
    left.cyrillicPatronymic === right.cyrillicPatronymic &&
    left.latinFirst === right.latinFirst &&
    left.latinLast === right.latinLast &&
    left.latinPatronymic === right.latinPatronymic &&
    left.phone === right.phone
  );
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

function progressFraction(values: Array<string | boolean | undefined | null>): { label: string; percent: number } {
  const total = values.length;
  const complete = values.filter((value) => Boolean(typeof value === "string" ? value.trim() : value)).length;
  return {
    label: `${complete} / ${total}`,
    percent: Math.round((complete / total) * 100),
  };
}

function userName(user: User): string {
  return explicitName(user) || "Аккаунт";
}

function shortName(user: User): string {
  return user.first_name_cyrillic || "Аккаунт";
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
  return "ID";
}

function EditIcon({ name }: { name: EditIconName }) {
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
      {name === "back" ? <path d="M19 12H5M11 6l-6 6 6 6" /> : null}
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
      {name === "help" ? (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M9.8 9a2.4 2.4 0 1 1 4 1.8c-.9.6-1.5 1.1-1.5 2.2" />
          <path d="M12 16h.01" />
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
