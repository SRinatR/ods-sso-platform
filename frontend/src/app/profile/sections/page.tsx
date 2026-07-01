"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { CSSProperties } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiRequestError } from "@/lib/api";
import { ACCOUNTS_URL, loginUrl } from "@/lib/domains";

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

type ProfileSection = {
  href: string;
  icon: ProfileSectionIconName;
  id: string;
  keywords: string;
  progress: number;
  score: string;
  status: string;
  text: string;
  title: string;
  tone: "amber" | "blue" | "green" | "slate";
};

type ProfileSectionIconName =
  | "apps"
  | "arrow"
  | "back"
  | "check"
  | "clock"
  | "edit"
  | "lock"
  | "phone"
  | "profile"
  | "search"
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

export default function ProfileSectionsPage() {
  const [user, setUser] = useState<User | null>(null);
  const [apps, setApps] = useState<ConnectedApp[]>([]);
  const [form, setForm] = useState<ProfileForm>(emptyForm);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<"all" | "complete" | "attention">("all");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const currentUser = await api<User>("/api/v1/auth/me");
      setUser(currentUser);
      setForm(formFromUser(currentUser));

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
  const sections = useMemo(
    () => (user ? buildProfileSections(form, user, apps.length) : []),
    [apps.length, form, user],
  );
  const visibleSections = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return sections.filter((section) => {
      const matchesQuery = normalized
        ? `${section.title} ${section.text} ${section.keywords}`.toLowerCase().includes(normalized)
        : true;
      const matchesFilter =
        filter === "all" ||
        (filter === "complete" && section.progress === 100) ||
        (filter === "attention" && section.progress < 100);
      return matchesQuery && matchesFilter;
    });
  }, [filter, query, sections]);

  return (
    <Shell title="Разделы профиля" subtitle="Выберите раздел, чтобы заполнить или обновить данные профиля.">
      {error && <div className="alert error account-alert">{error}</div>}
      {loading && !user ? <section className="account-card">Загрузка разделов профиля...</section> : null}

      {user ? (
        <div className="profile-sections-page">
          <ProfileSectionsHero user={user} />

          <section className="profile-sections-toolbar" aria-label="Фильтры разделов профиля">
            <Link className="profile-sections-back" href={`${ACCOUNTS_URL}/profile`}>
              <ProfileSectionIcon name="back" />
              Назад в профиль
            </Link>
            <label className="profile-sections-search">
              <ProfileSectionIcon name="search" />
              <input
                placeholder="Поиск по разделам"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <select
              aria-label="Фильтр разделов"
              className="profile-sections-filter"
              value={filter}
              onChange={(event) => setFilter(event.target.value as "all" | "complete" | "attention")}
            >
              <option value="all">Все разделы</option>
              <option value="attention">Требуют внимания</option>
              <option value="complete">Заполненные</option>
            </select>
            <div className="profile-sections-total">
              <span>Заполнено {completion}%</span>
              <i style={{ "--profile-section-total": `${completion}%` } as CSSProperties} />
            </div>
          </section>

          <section className="profile-sections-grid" aria-label="Список разделов профиля">
            {visibleSections.map((section) => (
              <ProfileSectionCard key={section.id} section={section} />
            ))}
            {visibleSections.length === 0 ? (
              <div className="account-card profile-sections-empty">
                По этому запросу разделы не найдены.
              </div>
            ) : null}
          </section>

          <section className="account-card profile-sections-privacy">
            <span aria-hidden="true">
              <ProfileSectionIcon name="shield" />
            </span>
            <div>
              <h2>Ваши данные под вашим контролем</h2>
              <p>
                Вы сами решаете, какие данные хранить в профиле и кому предоставлять доступ.
                Управляйте согласиями и проверяйте активность в разделе «Согласия и доступы».
              </p>
            </div>
            <Link className="account-link-button" href={`${ACCOUNTS_URL}/apps`}>
              Подробнее о защите данных
              <ProfileSectionIcon name="arrow" />
            </Link>
          </section>
        </div>
      ) : null}
    </Shell>
  );
}

function ProfileSectionsHero({ user }: { user: User }) {
  return (
    <section className="account-card profile-sections-hero">
      <div className="profile-avatar-preview profile-sections-avatar" aria-hidden="true">
        {initials(user)}
      </div>
      <div>
        <h2>{userName(user)}</h2>
        <p>{user.email}</p>
        <span>
          {user.created_at ? `Пользователь с ${formatDate(user.created_at)}` : "Дата регистрации недоступна"}
        </span>
      </div>
      <Link className="profile-sections-edit-callout" href={`${ACCOUNTS_URL}/profile/edit`}>
        <ProfileSectionIcon name="edit" />
        <span>
          <strong>Редактировать профиль</strong>
          <small>Ваша цифровая идентичность</small>
        </span>
        <ProfileSectionIcon name="arrow" />
      </Link>
    </section>
  );
}

function ProfileSectionCard({ section }: { section: ProfileSection }) {
  return (
    <Link className="profile-section-card" href={section.href}>
      <span aria-hidden="true">
        <ProfileSectionIcon name={section.icon} />
      </span>
      <div className="profile-section-card-main">
        <div className="profile-section-card-title">
          <h2>{section.title}</h2>
          <ProfileSectionIcon name="arrow" />
        </div>
        <p>{section.text}</p>
        <div className="profile-section-progress-row">
          <i style={{ "--profile-section-progress": `${section.progress}%` } as CSSProperties} />
          <em>{section.score}</em>
          <strong data-tone={section.tone}>{section.status}</strong>
        </div>
      </div>
    </Link>
  );
}

function buildProfileSections(form: ProfileForm, user: User, appsCount: number): ProfileSection[] {
  const nameScore = progressFraction([
    form.cyrillicFirst,
    form.cyrillicLast,
    form.cyrillicPatronymic,
    form.latinFirst,
    form.latinLast,
    form.latinPatronymic,
  ]);
  const contactScore = progressFraction([user.email, form.phone]);
  const securityScore = progressFraction([user.email_verified, user.mfa_enabled, user.authentication_method]);
  const consentsProgress = appsCount > 0 ? 100 : 0;

  return [
    {
      href: `${ACCOUNTS_URL}/profile/edit`,
      icon: "profile",
      id: "identity",
      keywords: "фио имя фамилия отчество латиница кириллица профиль",
      progress: nameScore.percent,
      score: nameScore.label,
      status: nameScore.percent === 100 ? "Заполнено" : "Почти заполнено",
      text: "ФИО на кириллице и латинице",
      title: "Основная информация",
      tone: nameScore.percent === 100 ? "green" : "amber",
    },
    {
      href: `${ACCOUNTS_URL}/profile/edit?section=contacts`,
      icon: "phone",
      id: "contacts",
      keywords: "контакты email телефон связь",
      progress: contactScore.percent,
      score: contactScore.label,
      status: contactScore.percent === 100 ? "Заполнено" : "Требует внимания",
      text: "Email и номер телефона",
      title: "Контакты",
      tone: contactScore.percent === 100 ? "green" : "amber",
    },
    {
      href: `${ACCOUNTS_URL}/security`,
      icon: "security",
      id: "security",
      keywords: "безопасность email mfa passkey пароль способ входа",
      progress: securityScore.percent,
      score: securityScore.label,
      status: securityScore.percent === 100 ? "Защищено" : "Настроить",
      text: "Email, MFA и способ входа",
      title: "Безопасность",
      tone: securityScore.percent === 100 ? "green" : "blue",
    },
    {
      href: `${ACCOUNTS_URL}/apps`,
      icon: "apps",
      id: "consents",
      keywords: "согласия доступы приложения передача данных",
      progress: consentsProgress,
      score: appsCount > 0 ? `${appsCount} активных` : "Нет активных",
      status: appsCount > 0 ? "Активно" : "Не заполнено",
      text: "Приложения, которым вы дали доступ",
      title: "Согласия и доступы",
      tone: appsCount > 0 ? "green" : "slate",
    },
    {
      href: `${ACCOUNTS_URL}/sessions`,
      icon: "clock",
      id: "activity",
      keywords: "активность сессии входы журнал устройства",
      progress: 100,
      score: "Журнал",
      status: "Доступно",
      text: "Активные сессии и история входов",
      title: "Активность",
      tone: "blue",
    },
  ];
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
    label: `${complete}/${total}`,
    percent: Math.round((complete / total) * 100),
  };
}

function userName(user: User): string {
  return explicitName(user) || "Аккаунт";
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

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString("ru-RU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

function ProfileSectionIcon({ name }: { name: ProfileSectionIconName }) {
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
      {name === "arrow" ? <path d="M9 6l6 6-6 6" /> : null}
      {name === "back" ? <path d="M15 18 9 12l6-6M10 12h10" /> : null}
      {name === "check" ? <path d="m5 12 4 4L19 6" /> : null}
      {name === "clock" ? (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 8v5l3 2" />
        </>
      ) : null}
      {name === "edit" ? (
        <>
          <path d="M4 20h4l10.5-10.5a2 2 0 0 0-4-4L4 16v4Z" />
          <path d="m13.5 6.5 4 4" />
        </>
      ) : null}
      {name === "lock" ? (
        <>
          <rect x="5" y="10" width="14" height="10" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" />
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
      {name === "search" ? (
        <>
          <circle cx="11" cy="11" r="6" />
          <path d="m16 16 4 4" />
        </>
      ) : null}
      {name === "security" || name === "shield" ? (
        <>
          <path d="M12 3 19 6v5c0 5-3.5 8-7 10-3.5-2-7-5-7-10V6l7-3Z" />
          {name === "security" ? <path d="m9.5 12.5 1.8 1.8 3.7-4.3" /> : null}
        </>
      ) : null}
    </svg>
  );
}
