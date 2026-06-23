"use client";

import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { loginUrl } from "@/lib/domains";

type User = {
  id: string;
  email: string;
  name?: string;
  email_verified: boolean;
  status: string;
  role: string;
  mfa_enabled: boolean;
};

export default function DashboardPage() {
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<User>("/api/v1/auth/me")
      .then(setUser)
      .catch(() => {
        setError("Сессия недействительна");
        window.location.href = loginUrl(window.location.href);
      });
  }, []);

  return (
    <Shell
      title={user ? `Здравствуйте, ${user.name || user.email}` : "Загрузка…"}
      subtitle="Контролируйте безопасность аккаунта и доступ приложений"
    >
      {error && <div className="alert error">{error}</div>}
      {user && (
        <div className="grid three">
          <section className="panel">
            <p className="eyebrow">Аккаунт</p>
            <h2>{user.email}</h2>
            <span className="badge success">Email подтвержден</span>
          </section>
          <section className="panel">
            <p className="eyebrow">MFA</p>
            <h2>{user.mfa_enabled ? "Включена" : "Не настроена"}</h2>
            <span className={`badge ${user.mfa_enabled ? "success" : "warning"}`}>
              {user.mfa_enabled ? "TOTP защищает вход" : "Рекомендуется включить"}
            </span>
          </section>
          <section className="panel">
            <p className="eyebrow">Роль</p>
            <h2>{user.role}</h2>
            <span className="badge">{user.status}</span>
          </section>
        </div>
      )}
    </Shell>
  );
}
