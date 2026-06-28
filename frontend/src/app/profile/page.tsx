"use client";

import { FormEvent, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";
import { transliterateCyrillicName } from "@/lib/full-name";

type User = {
  email: string;
  name?: string;
  full_name_cyrillic?: string;
  full_name_latin?: string;
  phone?: string;
  role: string;
};

export default function ProfilePage() {
  const [user, setUser] = useState<User | null>(null);
  const [form, setForm] = useState({ fullNameCyrillic: "", fullNameLatin: "", phone: "" });
  const [latinTouched, setLatinTouched] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<User>("/api/v1/auth/me")
      .then((value) => {
        setUser(value);
        const fullNameCyrillic = value.full_name_cyrillic || value.name || "";
        setForm({
          fullNameCyrillic,
          fullNameLatin:
            value.full_name_latin || (fullNameCyrillic ? transliterateCyrillicName(fullNameCyrillic) : ""),
          phone: value.phone || "",
        });
      })
      .catch(() => (window.location.href = loginUrl(window.location.href)));
  }, []);

  async function save(event: FormEvent) {
    event.preventDefault();
    setError("");
    setMessage("");
    try {
      const updated = await api<User>("/api/v1/account/profile", {
        method: "PATCH",
        body: JSON.stringify({
          full_name_cyrillic: form.fullNameCyrillic,
          full_name_latin: form.fullNameLatin || transliterateCyrillicName(form.fullNameCyrillic),
          phone: form.phone,
        }),
      });
      setUser(updated);
      setMessage("Профиль сохранён");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось сохранить профиль");
    }
  }

  return (
    <Shell
      title="Личный профиль"
      subtitle="Данные профиля заполняются после подтверждения email"
    >
      {error && <div className="alert error">{error}</div>}
      {message && <div className="alert success">{message}</div>}
      <section className="panel narrow">
        <form className="stack" onSubmit={save}>
          <label>
            Email
            <input value={user?.email || ""} disabled />
          </label>
          <label>
            Полное ФИО на кириллице
            <input
              maxLength={255}
              value={form.fullNameCyrillic}
              onChange={(event) => {
                const fullNameCyrillic = event.target.value;
                setForm({
                  ...form,
                  fullNameCyrillic,
                  fullNameLatin: latinTouched
                    ? form.fullNameLatin
                    : transliterateCyrillicName(fullNameCyrillic),
                });
              }}
            />
          </label>
          <label>
            Полное ФИО на латинице
            <input
              maxLength={255}
              pattern="[A-Za-z\\s'’\\-]+"
              value={form.fullNameLatin}
              onChange={(event) => {
                setLatinTouched(true);
                setForm({ ...form, fullNameLatin: event.target.value });
              }}
            />
            <span className="field-hint">
              Заполняется автоматически; вручную можно изменить до 3 символов.
            </span>
          </label>
          <label>
            Телефон
            <input
              type="tel"
              maxLength={32}
              value={form.phone}
              onChange={(event) => setForm({ ...form, phone: event.target.value })}
            />
          </label>
          <button className="button">Сохранить профиль</button>
        </form>
      </section>
    </Shell>
  );
}
