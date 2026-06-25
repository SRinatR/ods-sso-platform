import Link from "next/link";
import { ACCOUNTS_URL, AUTH_URL, PARTNERS_URL, onAuth, onPartners } from "@/lib/domains";

export default function HomePage() {
  return (
    <main className="landing">
      <nav className="landing-nav">
        <Link href="/" className="brand">
          ODS Identity
        </Link>
        <div className="actions">
          <Link href={onPartners("/")} className="button secondary">
            Кабинет контрагента
          </Link>
          <Link href={onAuth("/login")} className="button secondary">
            Войти
          </Link>
          <Link href={onAuth("/register")} className="button">
            Создать аккаунт
          </Link>
        </div>
      </nav>

      <section className="hero">
        <p className="eyebrow">Единый безопасный вход</p>
        <h1>Одна учетная запись для сервисов ODS и наших партнёров</h1>
        <p className="hero-copy">
          Пользователь регистрируется один раз в ODS, контролирует свои сессии и разрешения, а
          подключенные сервисы получают стандартный OAuth 2.1 / OpenID Connect вход.
        </p>
        <div className="actions">
          <Link href={onAuth("/register")} className="button">
            Зарегистрироваться
          </Link>
          <Link href={onAuth("/register?kind=partner")} className="button secondary">
            Войти как контрагент
          </Link>
          <Link href={ACCOUNTS_URL} className="button secondary">
            Войти в аккаунт
          </Link>
        </div>
      </section>

      <section className="landing-grid">
        <article className="panel">
          <p className="eyebrow">Пользователям</p>
          <h2>Единый профиль и контроль доступа</h2>
          <p className="muted">
            Безопасный вход, MFA, список активных сессий и возможность отозвать доступ у любого
            подключенного приложения.
          </p>
        </article>
        <article className="panel">
          <p className="eyebrow">Контрагентам</p>
          <h2>Подключение без собственного модуля авторизации</h2>
          <p className="muted">
            Создайте организацию и OIDC-приложение, укажите callback URL и добавьте кнопку «Войти
            через ODS».
          </p>
          <Link href={onAuth("/register?kind=partner")}>Создать аккаунт контрагента →</Link>
        </article>
        <article className="panel">
          <p className="eyebrow">Стандарт</p>
          <h2>OAuth 2.1 + OpenID Connect</h2>
          <p className="muted">
            Authorization Code, PKCE S256, подписанные ID tokens, Discovery и JWKS без
            нестандартных интеграционных протоколов.
          </p>
        </article>
      </section>
      <section className="panel top-gap">
        <p className="eyebrow">Разделение сервисов</p>
        <h2>Каждый адрес выполняет одну понятную роль</h2>
        <div className="integration-list">
          <span>Регистрация и вход</span>
          <code>{AUTH_URL}</code>
          <span>Личный кабинет пользователя</span>
          <code>{ACCOUNTS_URL}</code>
          <span>Кабинет контрагента</span>
          <code>{PARTNERS_URL}</code>
          <span>Настройки конкретной компании</span>
          <code>https://company.ods.uz</code>
        </div>
      </section>
      <footer className="landing-footer">
        <span>ODS Identity</span>
        <Link href="/privacy">Конфиденциальность и условия</Link>
        <Link href="https://status.ods.uz">Состояние сервисов</Link>
      </footer>
    </main>
  );
}
