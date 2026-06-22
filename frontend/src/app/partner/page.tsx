"use client";

import { FormEvent, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api } from "@/lib/api";
import { AUTH_URL, onAuth } from "@/lib/domains";

type Organization = {
  id: string;
  name: string;
  slug: string;
  legal_name?: string;
  website_url?: string;
  contact_email: string;
  status: string;
  role: string;
  portal_url: string;
};

type Application = {
  id: string;
  client_id: string;
  client_secret?: string;
  name: string;
  description?: string;
  redirect_uris: string[];
  scopes: string[];
  token_endpoint_auth_methods: string[];
  enabled: boolean;
};

type Integration = {
  issuer: string;
  discovery_url: string;
  authorization_endpoint: string;
  token_endpoint: string;
  user_info_endpoint: string;
  jwks_url: string;
};

type Workspace = {
  organization?: Organization;
  applications: Application[];
  integration: Integration;
};

export default function PartnerPage() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [error, setError] = useState("");
  const [secret, setSecret] = useState("");
  const [organizationForm, setOrganizationForm] = useState({
    name: "",
    slug: "",
    legalName: "",
    websiteUrl: "",
    contactEmail: "",
  });
  const [applicationForm, setApplicationForm] = useState({
    name: "",
    description: "",
    redirectUris: "",
  });

  function load() {
    api<Workspace>("/api/v1/partner/workspace")
      .then((loaded) => {
        if (
          loaded.organization?.portal_url &&
          window.location.origin === new URL(AUTH_URL).origin
        ) {
          window.location.href = loaded.organization.portal_url;
          return;
        }
        setWorkspace(loaded);
      })
      .catch(() => {
        window.location.href = onAuth(
          `/login?return_to=${encodeURIComponent(window.location.href)}`,
        );
      });
  }

  useEffect(load, []);

  async function createOrganization(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      const created = await api<Workspace>("/api/v1/partner/organizations", {
        method: "POST",
        body: JSON.stringify({
          name: organizationForm.name,
          slug: organizationForm.slug,
          legal_name: organizationForm.legalName || null,
          website_url: organizationForm.websiteUrl || null,
          contact_email: organizationForm.contactEmail,
        }),
      });
      setWorkspace(created);
      if (created.organization?.portal_url) {
        window.location.href = created.organization.portal_url;
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось создать организацию");
    }
  }

  async function createApplication(event: FormEvent) {
    event.preventDefault();
    setError("");
    setSecret("");
    try {
      const created = await api<Application>("/api/v1/partner/applications", {
        method: "POST",
        body: JSON.stringify({
          name: applicationForm.name,
          description: applicationForm.description || null,
          redirect_uris: applicationForm.redirectUris
            .split(/\r?\n/)
            .map((value) => value.trim())
            .filter(Boolean),
        }),
      });
      setSecret(created.client_secret || "");
      setWorkspace((current) =>
        current ? { ...current, applications: [created, ...current.applications] } : current,
      );
      setApplicationForm({ name: "", description: "", redirectUris: "" });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось создать приложение");
    }
  }

  async function rotateSecret(applicationId: string) {
    setError("");
    setSecret("");
    try {
      const updated = await api<Application>(
        `/api/v1/partner/applications/${applicationId}/rotate-secret`,
        { method: "POST" },
      );
      setSecret(updated.client_secret || "");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось обновить secret");
    }
  }

  return (
    <Shell
      title={workspace?.organization ? workspace.organization.name : "Кабинет контрагента"}
      subtitle="Подключение сервисов к единой авторизации ODS"
    >
      {error && <div className="alert error">{error}</div>}
      {!workspace && <div className="panel">Загрузка…</div>}

      {workspace && !workspace.organization && (
        <section className="panel narrow">
          <p className="eyebrow">Шаг 1</p>
          <h2>Зарегистрируйте организацию</h2>
          <p className="muted">
            Ваш личный аккаунт станет владельцем кабинета контрагента. Для сайта tatarlar.uz
            будет предложен адрес tatarlar.ods.uz.
          </p>
          <form className="stack" onSubmit={createOrganization}>
            <label>
              Название организации
              <input
                required
                minLength={2}
                value={organizationForm.name}
                onChange={(event) =>
                  setOrganizationForm({ ...organizationForm, name: event.target.value })
                }
              />
            </label>
            <label>
              Адрес кабинета
              <input
                pattern="[a-z0-9][a-z0-9-]{2,62}"
                placeholder="tatarlar"
                value={organizationForm.slug}
                onChange={(event) =>
                  setOrganizationForm({
                    ...organizationForm,
                    slug: event.target.value.toLowerCase(),
                  })
                }
              />
              <small className="muted">
                {organizationForm.slug
                  ? `https://${organizationForm.slug}.ods.uz`
                  : "Заполнится автоматически из адреса сайта"}
              </small>
            </label>
            <label>
              Юридическое название
              <input
                value={organizationForm.legalName}
                onChange={(event) =>
                  setOrganizationForm({ ...organizationForm, legalName: event.target.value })
                }
              />
            </label>
            <label>
              Сайт
              <input
                type="text"
                inputMode="url"
                placeholder="tatarlar.uz"
                value={organizationForm.websiteUrl}
                onChange={(event) => {
                  const websiteUrl = event.target.value;
                  const derived = deriveSlug(websiteUrl);
                  setOrganizationForm({
                    ...organizationForm,
                    websiteUrl,
                    slug: organizationForm.slug || derived,
                  });
                }}
              />
            </label>
            <label>
              Контактный email
              <input
                required
                type="email"
                value={organizationForm.contactEmail}
                onChange={(event) =>
                  setOrganizationForm({ ...organizationForm, contactEmail: event.target.value })
                }
              />
            </label>
            <button className="button">Создать кабинет</button>
          </form>
        </section>
      )}

      {workspace?.organization && (
        <>
          <div className="grid two">
            <section className="panel">
              <p className="eyebrow">Организация</p>
              <h2>{workspace.organization.name}</h2>
              <p className="muted">
                Код: <code>{workspace.organization.slug}</code>
                <br />
                Контакт: {workspace.organization.contact_email}
              </p>
              <a href={workspace.organization.portal_url}>{workspace.organization.portal_url}</a>
              <span className="badge success">{workspace.organization.status}</span>
            </section>
            <section className="panel">
              <p className="eyebrow">OIDC Discovery</p>
              <code className="secret">{workspace.integration.discovery_url}</code>
              <p className="muted top-gap">
                Используйте Discovery URL вместо ручного копирования endpoint-ов и ключей.
              </p>
            </section>
          </div>

          {secret && (
            <div className="alert warning">
              Сохраните client secret сейчас — повторно он не показывается.
              <code className="secret">{secret}</code>
            </div>
          )}

          <section className="panel">
            <p className="eyebrow">Шаг 2</p>
            <h2>Добавьте приложение</h2>
            <form className="stack" onSubmit={createApplication}>
              <label>
                Название приложения
                <input
                  required
                  minLength={2}
                  value={applicationForm.name}
                  onChange={(event) =>
                    setApplicationForm({ ...applicationForm, name: event.target.value })
                  }
                />
              </label>
              <label>
                Описание для экрана согласия
                <textarea
                  rows={3}
                  value={applicationForm.description}
                  onChange={(event) =>
                    setApplicationForm({ ...applicationForm, description: event.target.value })
                  }
                />
              </label>
              <label>
                Callback URL — по одному в строке
                <textarea
                  required
                  rows={4}
                  placeholder={"https://service.example.uz/auth/ods/callback\nhttp://localhost:3000/auth/ods/callback"}
                  value={applicationForm.redirectUris}
                  onChange={(event) =>
                    setApplicationForm({ ...applicationForm, redirectUris: event.target.value })
                  }
                />
              </label>
              <button className="button">Создать OIDC-приложение</button>
            </form>
          </section>

          <section className="panel">
            <h2>Приложения</h2>
            {workspace.applications.length === 0 ? (
              <p className="muted">Приложений пока нет.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Приложение</th>
                      <th>Callback URL</th>
                      <th>Scopes</th>
                      <th>Статус</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {workspace.applications.map((application) => (
                      <tr key={application.id}>
                        <td>
                          <b>{application.name}</b>
                          <br />
                          <code>{application.client_id}</code>
                        </td>
                        <td>{application.redirect_uris.join(", ")}</td>
                        <td>{application.scopes.join(" ")}</td>
                        <td>{application.enabled ? "enabled" : "disabled"}</td>
                        <td>
                          <button
                            className="text-button"
                            onClick={() => rotateSecret(application.id)}
                          >
                            Новый secret
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="panel">
            <h2>Параметры интеграции</h2>
            <div className="integration-list">
              <span>Issuer</span>
              <code>{workspace.integration.issuer}</code>
              <span>Authorize</span>
              <code>{workspace.integration.authorization_endpoint}</code>
              <span>Token</span>
              <code>{workspace.integration.token_endpoint}</code>
              <span>UserInfo</span>
              <code>{workspace.integration.user_info_endpoint}</code>
              <span>JWKS</span>
              <code>{workspace.integration.jwks_url}</code>
            </div>
          </section>
        </>
      )}
    </Shell>
  );
}

function deriveSlug(website: string): string {
  try {
    const normalized = website.includes("://") ? website : `https://${website}`;
    const hostname = new URL(normalized).hostname.toLowerCase().replace(/^www\./, "");
    const slug = hostname.split(".")[0] || "";
    return /^[a-z0-9][a-z0-9-]{2,62}$/.test(slug) ? slug : "";
  } catch {
    return "";
  }
}
