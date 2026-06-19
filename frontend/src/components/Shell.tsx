"use client";

import Link from "next/link";
import { ReactNode } from "react";
import { api } from "@/lib/api";

export function Shell({
  title,
  subtitle,
  children,
  admin = false,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  admin?: boolean;
}) {
  async function logout() {
    await api("/api/v1/auth/logout", { method: "POST" });
    window.location.href = "/login";
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Link href="/dashboard" className="brand">
          ODS Identity
        </Link>
        <nav>
          <Link href="/dashboard">Обзор</Link>
          <Link href="/security">Безопасность и MFA</Link>
          <Link href="/sessions">Сессии и входы</Link>
          <Link href="/apps">Подключенные приложения</Link>
          {admin && <Link href="/admin">Администрирование</Link>}
        </nav>
        <button className="button secondary" onClick={logout}>
          Выйти
        </button>
      </aside>
      <main className="content">
        <header className="page-header">
          <div>
            <p className="eyebrow">Единая учетная запись</p>
            <h1>{title}</h1>
            {subtitle && <p className="muted">{subtitle}</p>}
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}

export function AuthCard({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand">ODS Identity</div>
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        {children}
      </section>
    </main>
  );
}

