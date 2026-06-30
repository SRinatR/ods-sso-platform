"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ReactNode } from "react";
import { api } from "@/lib/api";
import { ACCOUNTS_URL, ROOT_URL, onAuth } from "@/lib/domains";

type Product = "account" | "partner" | "admin";
type AccountNavIcon =
  | "apps"
  | "home"
  | "logout"
  | "profile"
  | "security"
  | "sessions"
  | "settings"
  | "support";

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
    <div className="app-shell" data-product={product}>
      <aside className="sidebar">
        <Link href={ROOT_URL} className="brand account-brand">
          <span className="brand-mark" aria-hidden="true">
            <AccountIcon name="security" />
          </span>
          <span>
            <strong>ODS SSO</strong>
            <small>Account</small>
          </span>
        </Link>
        <p className="product-name">{productName(product)}</p>
        <ProductNavigation product={product} />
        {product === "account" ? (
          <>
            <div className="account-support">
              <span aria-hidden="true">
                <AccountIcon name="support" />
              </span>
              <div>
                <strong>Нужна помощь?</strong>
                <p>Мы готовы помочь 24/7</p>
              </div>
              <Link href={`${ROOT_URL}/privacy`}>Поддержка</Link>
            </div>
            <button className="account-logout" onClick={logout}>
              <AccountIcon name="logout" />
              <span>Выйти из аккаунта</span>
            </button>
          </>
        ) : (
          <>
            <button className="button secondary" onClick={logout}>
              Выйти
            </button>
            <Link href={`${ROOT_URL}/privacy`} className="sidebar-legal">
              Конфиденциальность
            </Link>
          </>
        )}
      </aside>
      <main className="content">
        <header className={`page-header ${product === "account" ? "account-page-header" : ""}`}>
          <div>
            <p className="eyebrow">{productName(product)}</p>
            <h1>{title}</h1>
            {subtitle && <p className="muted">{subtitle}</p>}
          </div>
          {product === "account" ? (
            <div className="account-header-actions">
              <div className="account-user-chip">
                <span aria-hidden="true">OD</span>
                <strong>Аккаунт</strong>
              </div>
            </div>
          ) : null}
        </header>
        {children}
      </main>
    </div>
  );
}

function ProductNavigation({ product }: { product: Product }) {
  const pathname = usePathname();

  if (product === "partner") {
    return (
      <nav>
        <a href="#organizations">Все компании</a>
        <a href="#organization">Организация</a>
        <a href="#analytics">Аналитика</a>
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
  const accountItems: Array<{
    href: string;
    icon: AccountNavIcon;
    label: string;
    path: string;
  }> = [
    { href: `${ACCOUNTS_URL}/dashboard`, icon: "home", label: "Обзор", path: "/dashboard" },
    { href: `${ACCOUNTS_URL}/profile`, icon: "profile", label: "Профиль", path: "/profile" },
    { href: `${ACCOUNTS_URL}/security`, icon: "security", label: "Безопасность", path: "/security" },
    { href: `${ACCOUNTS_URL}/sessions`, icon: "sessions", label: "Сессии и входы", path: "/sessions" },
    { href: `${ACCOUNTS_URL}/apps`, icon: "apps", label: "Согласия и доступы", path: "/apps" },
    { href: `${ACCOUNTS_URL}/settings`, icon: "settings", label: "Настройки", path: "/settings" },
  ];
  return (
    <nav className="account-nav">
      {accountItems.map((item) => (
        <Link
          className={pathname === item.path ? "active" : undefined}
          href={item.href}
          key={item.label}
        >
          <AccountIcon name={item.icon} />
          <span>{item.label}</span>
        </Link>
      ))}
    </nav>
  );
}

function productName(product: Product): string {
  if (product === "partner") return "Партнёрский кабинет";
  if (product === "admin") return "Системное администрирование";
  return "Личный кабинет";
}

function AccountIcon({ name }: { name: AccountNavIcon }) {
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
      {name === "home" ? (
        <>
          <path d="M4 11 12 4l8 7" />
          <path d="M6 10v10h12V10" />
          <path d="M10 20v-5h4v5" />
        </>
      ) : null}
      {name === "logout" ? (
        <>
          <path d="M9 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h3" />
          <path d="M15 8l4 4-4 4" />
          <path d="M19 12H9" />
        </>
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
      {name === "sessions" ? (
        <>
          <rect x="4" y="5" width="16" height="11" rx="2" />
          <path d="M9 20h6M12 16v4" />
        </>
      ) : null}
      {name === "settings" ? (
        <>
          <circle cx="12" cy="12" r="3" />
          <path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.5-2.4 1a7 7 0 0 0-1.7-1L14.5 3h-5l-.4 3a7 7 0 0 0-1.7 1L5 6 3 9.5 5.1 11a7 7 0 0 0 0 2L3 14.5 5 18l2.4-1a7 7 0 0 0 1.7 1l.4 3h5l.4-3a7 7 0 0 0 1.7-1l2.4 1 2-3.5-2.1-1.5c.1-.3.1-.7.1-1Z" />
        </>
      ) : null}
      {name === "support" ? (
        <>
          <path d="M5 13v-1a7 7 0 0 1 14 0v1" />
          <path d="M5 13h3v5H5zM16 13h3v5h-3z" />
          <path d="M19 18c0 2-2 3-5 3h-2" />
        </>
      ) : null}
    </svg>
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
