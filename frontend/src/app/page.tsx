import Link from "next/link";

export default function HomePage() {
  return (
    <main className="landing">
      <nav className="landing-nav">
        <Link href="/" className="brand">
          ODS Identity
        </Link>
        <div className="actions">
          <Link href="/login" className="button secondary">
            Войти
          </Link>
          <Link href="/register" className="button">
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
          <Link href="/register" className="button">
            Зарегистрироваться
          </Link>
          <Link href="/login" className="button secondary">
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
          <Link href="/register">Создать аккаунт партнёра →</Link>
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
    </main>
  );
}
