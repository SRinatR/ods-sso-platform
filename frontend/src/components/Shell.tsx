"use client";

import Link from "next/link";
import { ReactNode } from "react";
import { api } from "@/lib/api";
import { ACCOUNTS_URL, ROOT_URL, onAuth } from "@/lib/domains";

type Product = "account" | "partner" | "admin";

export function Shell({
  title,
  subtitle,
  children,
  product = "account",
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  product?: Product;
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
        <p className="product-name">{productName(product)}</p>
        <ProductNavigation product={product} />
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
            <p className="eyebrow">{productName(product)}</p>
            <h1>{title}</h1>
            {subtitle && <p className="muted">{subtitle}</p>}
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}

function ProductNavigation({ product }: { product: Product }) {
  if (product === "partner") {
    return (
      <nav>
        <a href="#organizations">Все компании</a>
        <a href="#organization">Организация</a>
        <a href="#members">Участники и роли</a>
        <a href="#applications">SSO-приложения</a>
        <a href="#integration">Параметры OIDC</a>
      </nav>
    );
  }
  if (product === "admin") {
    return (
      <nav>
        <a href="#overview">Обзор системы</a>
        <a href="#users">Пользователи</a>
        <a href="#oauth-clients">OAuth-клиенты</a>
        <a href="#sessions">Сессии</a>
        <a href="#audit">Аудит</a>
        <a href="#policies">Политики</a>
      </nav>
    );
  }
  return (
    <nav>
      <Link href={`${ACCOUNTS_URL}/dashboard`}>Обзор</Link>
      <Link href={`${ACCOUNTS_URL}/profile`}>Личный профиль</Link>
      <Link href={`${ACCOUNTS_URL}/security`}>Безопасность</Link>
      <Link href={`${ACCOUNTS_URL}/sessions`}>Сессии и устройства</Link>
      <Link href={`${ACCOUNTS_URL}/apps`}>Подключенные приложения</Link>
    </nav>
  );
}

function productName(product: Product): string {
  if (product === "partner") return "Кабинет контрагента";
  if (product === "admin") return "Системное администрирование";
  return "Личный кабинет";
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
