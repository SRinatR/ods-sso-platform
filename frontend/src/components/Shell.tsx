"use client";

import Link from "next/link";
import { ReactNode } from "react";
import { api } from "@/lib/api";
import { ACCOUNTS_URL, ADMIN_URL, ROOT_URL, onAuth } from "@/lib/domains";

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
    window.location.href = onAuth("/login");
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Link href={ROOT_URL} className="brand">
          ODS Identity
        </Link>
        <nav>
          <Link href={`${ACCOUNTS_URL}/dashboard`}>Обзор</Link>
          <Link href={`${ACCOUNTS_URL}/profile`}>Личный профиль</Link>
          <Link href={`${ACCOUNTS_URL}/security`}>Безопасность и MFA</Link>
          <Link href={`${ACCOUNTS_URL}/sessions`}>Сессии и входы</Link>
          <Link href={`${ACCOUNTS_URL}/apps`}>Подключенные приложения</Link>
          <Link href={onAuth("/partner")}>Кабинет контрагента</Link>
          {admin && <Link href={`${ADMIN_URL}/admin`}>Администрирование</Link>}
        </nav>
        <button className="button secondary" onClick={logout}>
          Выйти
        </button>
        <Link href={`${ROOT_URL}/privacy`} className="sidebar-legal">
          Конфиденциальность
        </Link>
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
        <Link href={ROOT_URL} className="brand">
          ODS Identity
        </Link>
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        {children}
        <div className="auth-footer">
          <Link href={`${ROOT_URL}/privacy`}>Конфиденциальность и условия</Link>
        </div>
      </section>
    </main>
  );
}
