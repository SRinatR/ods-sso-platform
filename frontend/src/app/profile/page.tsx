"use client";

import { FormEvent, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";

type User = {
  email: string;
  name?: string;
  phone?: string;
  role: string;
};

export default function ProfilePage() {
  const [user, setUser] = useState<User | null>(null);
  const [form, setForm] = useState({ name: "", phone: "" });
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<User>("/api/v1/auth/me")
      .then((value) => {
        setUser(value);
        setForm({ name: value.name || "", phone: value.phone || "" });
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
        body: JSON.stringify(form),
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
      admin={user?.role === "admin" || user?.role === "security_admin"}
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
            Имя или название контактного лица
            <input
              maxLength={255}
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
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
