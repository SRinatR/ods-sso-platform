"use client";

import { Shell } from "@/components/Shell";

export default function SettingsPage() {
  return (
    <Shell
      title="Настройки"
      subtitle="Раздел находится в разработке. Профиль, безопасность, сессии и согласия доступны в отдельных разделах."
    >
      <section className="account-card settings-dev-card">
        <span aria-hidden="true">DEV</span>
        <div>
          <h2>Настройки в разработке</h2>
          <p>Здесь появятся общие параметры аккаунта, языка и уведомлений после готовности API.</p>
        </div>
      </section>
    </Shell>
  );
}
