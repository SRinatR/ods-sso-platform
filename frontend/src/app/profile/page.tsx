"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";
import { transliterateCyrillicName } from "@/lib/full-name";

type User = {
  email: string;
  name?: string;
  first_name_cyrillic?: string;
  last_name_cyrillic?: string;
  patronymic_cyrillic?: string;
  first_name_latin?: string;
  last_name_latin?: string;
  patronymic_latin?: string;
  phone?: string;
  role: string;
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
  | "arrow"
  | "camera"
  | "check"
  | "contact"
  | "document"
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

const steps = [
  "Основная информация",
  "Контакты",
  "Идентификация",
  "Безопасность",
  "Согласия",
  "Дополнительно",
];

export default function ProfilePage() {
  const [user, setUser] = useState<User | null>(null);
  const [form, setForm] = useState<ProfileForm>(emptyForm);
  const [latinTouched, setLatinTouched] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<User>("/api/v1/auth/me")
      .then((value) => {
        setUser(value);
        setForm(formFromUser(value));
      })
      .catch(() => (window.location.href = loginUrl(window.location.href)));
  }, []);

  const completion = useMemo(() => {
    const required = [
      form.cyrillicFirst,
      form.cyrillicLast,
      form.latinFirst,
      form.latinLast,
      form.phone,
    ];
    return Math.round((required.filter((value) => value.trim()).length / required.length) * 100);
  }, [form]);

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
      if (latinTouched) {
        return merged;
      }
      return {
        ...merged,
        latinFirst: transliterateCyrillicName(merged.cyrillicFirst),
        latinLast: transliterateCyrillicName(merged.cyrillicLast),
        latinPatronymic: transliterateCyrillicName(merged.cyrillicPatronymic),
      };
    });
  }

  return (
    <Shell
      title="Заполнение профиля"
      subtitle="Заполните обязательные данные, чтобы пользоваться всеми возможностями сервиса."
    >
      {error && <div className="alert error">{error}</div>}
      {message && <div className="alert success">{message}</div>}

      <div className="profile-completion-page">
        <div className="profile-steps" aria-label="Этапы заполнения профиля">
          {steps.map((step, index) => (
            <span className={index === 0 ? "active" : undefined} key={step}>
              <strong>{index + 1}</strong>
              {step}
            </span>
          ))}
        </div>

        <form className="profile-completion-layout" onSubmit={save}>
          <section className="profile-form-card">
            <header className="profile-card-title">
              <span aria-hidden="true">
                <ProfileIcon name="profile" />
              </span>
              <div>
                <h2>Основная информация</h2>
                <p>Укажите базовые данные о себе</p>
              </div>
            </header>

            <div className="profile-main-form">
              <aside className="profile-avatar-panel">
                <span>Аватар</span>
                <div className="profile-avatar-preview" aria-hidden="true">
                  {user ? initials(user) : "ID"}
                  <button aria-label="Изменить аватар" type="button">
                    <ProfileIcon name="camera" />
                  </button>
                </div>
                <p>JPG, PNG до 5 МБ</p>
                <p>Сохранение аватара будет добавлено после появления поля в API.</p>
              </aside>

              <div className="profile-field-groups">
                <fieldset>
                  <legend>ФИО (кириллица)</legend>
                  <div className="profile-field-grid three">
                    <RequiredLabel label="Имя">
                      <input
                        maxLength={80}
                        required
                        value={form.cyrillicFirst}
                        onChange={(event) => updateCyrillic({ cyrillicFirst: event.target.value })}
                      />
                    </RequiredLabel>
                    <RequiredLabel label="Фамилия">
                      <input
                        maxLength={80}
                        required
                        value={form.cyrillicLast}
                        onChange={(event) => updateCyrillic({ cyrillicLast: event.target.value })}
                      />
                    </RequiredLabel>
                    <label>
                      Отчество
                      <input
                        maxLength={80}
                        value={form.cyrillicPatronymic}
                        onChange={(event) =>
                          updateCyrillic({ cyrillicPatronymic: event.target.value })
                        }
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
                        onChange={(event) => {
                          setLatinTouched(true);
                          setForm({ ...form, latinFirst: event.target.value });
                        }}
                      />
                    </RequiredLabel>
                    <RequiredLabel label="Last name">
                      <input
                        maxLength={80}
                        pattern="[A-Za-z\\s'’\\-]+"
                        required
                        value={form.latinLast}
                        onChange={(event) => {
                          setLatinTouched(true);
                          setForm({ ...form, latinLast: event.target.value });
                        }}
                      />
                    </RequiredLabel>
                    <label>
                      Patronymic
                      <input
                        maxLength={80}
                        pattern="[A-Za-z\\s'’\\-]+"
                        value={form.latinPatronymic}
                        onChange={(event) => {
                          setLatinTouched(true);
                          setForm({ ...form, latinPatronymic: event.target.value });
                        }}
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
                        onChange={(event) => setForm({ ...form, phone: event.target.value })}
                      />
                    </RequiredLabel>
                    <label>
                      Email
                      <input value={user?.email || ""} disabled />
                    </label>
                  </div>
                </fieldset>

                <p className="profile-required-note">
                  <span>*</span> обязательные поля
                </p>
              </div>
            </div>

            <footer className="profile-form-actions">
              <button className="button secondary" type="button">
                Сохранить как черновик
              </button>
              <button className="button" type="submit">
                Продолжить
                <ProfileIcon name="arrow" />
              </button>
            </footer>
          </section>

          <aside className="profile-aside">
            <section className="profile-progress-card">
              <h2>Заполнение профиля</h2>
              <div className="profile-progress-content">
                <div
                  className="profile-progress-ring"
                  style={{ "--profile-progress": `${completion}%` } as CSSProperties}
                >
                  <strong>{completion}%</strong>
                </div>
                <p>
                  Вы заполнили обязательную основную информацию.
                  <br />
                  Заполните профиль на 100%, чтобы разблокировать все возможности.
                </p>
              </div>
            </section>

            <section className="profile-next-card">
              <h2>Что дальше?</h2>
              <NextStep icon="phone" title="Укажите контакты" text="Телефон и email для связи" />
              <NextStep icon="document" title="Пройдите идентификацию" text="Паспортные данные" />
              <NextStep icon="shield" title="Настройте безопасность" text="Пароль и двухфакторная аутентификация" />
              <NextStep icon="check" title="Предоставьте согласия" text="Управляйте доступом к данным" />
            </section>
          </aside>
        </form>
      </div>
    </Shell>
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

function NextStep({
  icon,
  text,
  title,
}: {
  icon: ProfileIconName;
  text: string;
  title: string;
}) {
  return (
    <article>
      <span aria-hidden="true">
        <ProfileIcon name={icon} />
      </span>
      <div>
        <strong>{title}</strong>
        <p>{text}</p>
      </div>
    </article>
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

function initials(user: User): string {
  const first = user.first_name_cyrillic || "";
  const last = user.last_name_cyrillic || "";
  const explicit = [first, last].map((part) => part.trim()[0]).filter(Boolean).join("");
  if (explicit) return explicit.toUpperCase();
  return (user.email.trim()[0] || "I").toUpperCase();
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
      {name === "arrow" ? <path d="M5 12h14M13 6l6 6-6 6" /> : null}
      {name === "camera" ? (
        <>
          <path d="M8 7h1.5L11 5h2l1.5 2H16a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3Z" />
          <circle cx="12" cy="13" r="3" />
        </>
      ) : null}
      {name === "check" ? (
        <>
          <rect x="5" y="4" width="14" height="16" rx="2" />
          <path d="m9 12 2 2 4-5" />
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
