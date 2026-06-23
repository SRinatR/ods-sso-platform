"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/Shell";
import { apiAt, ApiRequestError } from "@/lib/api";
import { onAuth } from "@/lib/domains";

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
  post_logout_redirect_uris: string[];
  scopes: string[];
  client_type: "public" | "confidential";
  token_endpoint_auth_method: "none" | "client_secret_basic" | "client_secret_post";
  require_pkce: boolean;
  enabled: boolean;
};

type Integration = {
  issuer: string;
  discovery_url: string;
  authorization_endpoint: string;
  token_endpoint: string;
  user_info_endpoint: string;
  jwks_url: string;
  end_session_endpoint: string;
  supported_scopes: string[];
  supported_client_types: string[];
  supported_token_endpoint_auth_methods: string[];
};

type Workspace = {
  organization?: Organization;
  organizations: Organization[];
  applications: Application[];
  members: Member[];
  integration: Integration;
};

type Member = {
  id: string;
  user_id: string;
  email: string;
  name?: string;
  role: "owner" | "admin" | "editor" | "user" | "viewer";
  status: "active" | "disabled";
  created_at: string;
};

type ApplicationForm = {
  name: string;
  description: string;
  redirectUris: string;
  postLogoutRedirectUris: string;
  scopes: string[];
  clientType: "public" | "confidential";
  tokenEndpointAuthMethod: "none" | "client_secret_basic" | "client_secret_post";
};

const emptyApplication: ApplicationForm = {
  name: "",
  description: "",
  redirectUris: "",
  postLogoutRedirectUris: "",
  scopes: ["openid", "profile", "email"],
  clientType: "confidential",
  tokenEndpointAuthMethod: "client_secret_basic",
};

const scopeLabels: Record<string, string> = {
  openid: "Идентификатор пользователя",
  profile: "Имя и профиль",
  email: "Email и статус подтверждения",
  phone: "Номер телефона",
  offline_access: "Refresh token для долгой сессии",
};

export default function PartnerPage() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [secret, setSecret] = useState<{ clientId: string; value: string } | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [organizationForm, setOrganizationForm] = useState({
    name: "",
    slug: "",
    legalName: "",
    websiteUrl: "",
    contactEmail: "",
  });
  const [applicationForm, setApplicationForm] =
    useState<ApplicationForm>(emptyApplication);
  const [memberForm, setMemberForm] = useState({
    email: "",
    role: "user" as "editor" | "user" | "viewer",
  });

  const load = useCallback(() => {
    setError("");
    partnerApi<Workspace>("/api/v1/partner/workspace")
      .then((loaded) => {
        setWorkspace(loaded);
        setLoading(false);
      })
      .catch((cause) => {
        if (cause instanceof ApiRequestError && cause.status === 401) {
          window.location.href = onAuth(
            `/login?return_to=${encodeURIComponent(window.location.href)}`,
          );
          return;
        }
        setLoading(false);
        setError(cause instanceof Error ? cause.message : "Кабинет временно недоступен");
      });
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    return () => window.clearTimeout(initial);
  }, [load]);

  async function createOrganization(event: FormEvent) {
    event.preventDefault();
    setError("");
    setSaving(true);
    try {
      const created = await partnerApi<Workspace>("/api/v1/partner/organizations", {
        method: "POST",
        body: JSON.stringify({
          name: organizationForm.name,
          slug: organizationForm.slug || null,
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
    } finally {
      setSaving(false);
    }
  }

  async function saveApplication(event: FormEvent) {
    event.preventDefault();
    setError("");
    setNotice("");
    setSecret(null);
    setSaving(true);
    const payload = applicationPayload(applicationForm);
    try {
      if (editingId) {
        const updated = await partnerApi<Application>(
          `/api/v1/partner/applications/${editingId}`,
          { method: "PATCH", body: JSON.stringify(payload) },
        );
        replaceApplication(updated);
        setNotice(`Настройки «${updated.name}» сохранены.`);
      } else {
        const created = await partnerApi<Application>("/api/v1/partner/applications", {
          method: "POST",
          body: JSON.stringify(payload),
        });
        setWorkspace((current) =>
          current ? { ...current, applications: [created, ...current.applications] } : current,
        );
        if (created.client_secret) {
          setSecret({ clientId: created.client_id, value: created.client_secret });
        }
        setNotice(`SSO-приложение «${created.name}» создано.`);
      }
      cancelEditing();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось сохранить приложение");
    } finally {
      setSaving(false);
    }
  }

  function editApplication(application: Application) {
    setEditingId(application.id);
    setApplicationForm({
      name: application.name,
      description: application.description || "",
      redirectUris: application.redirect_uris.join("\n"),
      postLogoutRedirectUris: application.post_logout_redirect_uris.join("\n"),
      scopes: application.scopes,
      clientType: application.client_type,
      tokenEndpointAuthMethod: application.token_endpoint_auth_method,
    });
    setNotice("");
    setError("");
    document.getElementById("application-editor")?.scrollIntoView({ behavior: "smooth" });
  }

  function cancelEditing() {
    setEditingId(null);
    setApplicationForm(emptyApplication);
  }

  async function toggleApplication(application: Application) {
    setError("");
    try {
      const updated = await partnerApi<Application>(
        `/api/v1/partner/applications/${application.id}`,
        {
          method: "PATCH",
          body: JSON.stringify({ enabled: !application.enabled }),
        },
      );
      replaceApplication(updated);
      setNotice(updated.enabled ? "Приложение включено." : "Приложение отключено.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось изменить статус");
    }
  }

  async function rotateSecret(application: Application) {
    setError("");
    setSecret(null);
    try {
      const updated = await partnerApi<Application>(
        `/api/v1/partner/applications/${application.id}/rotate-secret`,
        { method: "POST" },
      );
      if (updated.client_secret) {
        setSecret({ clientId: updated.client_id, value: updated.client_secret });
      }
      setNotice("Новый client secret создан. Старый secret больше не действует.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось обновить secret");
    }
  }

  function replaceApplication(updated: Application) {
    setWorkspace((current) =>
      current
        ? {
            ...current,
            applications: current.applications.map((item) =>
              item.id === updated.id ? updated : item,
            ),
          }
        : current,
    );
  }

  async function addMember(event: FormEvent) {
    event.preventDefault();
    setError("");
    setNotice("");
    setSaving(true);
    try {
      const created = await partnerApi<Member>("/api/v1/partner/members", {
        method: "POST",
        body: JSON.stringify(memberForm),
      });
      setWorkspace((current) =>
        current ? { ...current, members: [...current.members, created] } : current,
      );
      setMemberForm({ email: "", role: "user" });
      setNotice("Участник добавлен. Его роль попадёт в OIDC-токены этой организации.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось добавить участника");
    } finally {
      setSaving(false);
    }
  }

  async function updateMember(member: Member, patch: Partial<Pick<Member, "role" | "status">>) {
    setError("");
    try {
      const updated = await partnerApi<Member>(`/api/v1/partner/members/${member.id}`, {
        method: "PATCH",
        body: JSON.stringify(patch),
      });
      setWorkspace((current) =>
        current
          ? {
              ...current,
              members: current.members.map((item) => (item.id === updated.id ? updated : item)),
            }
          : current,
      );
      setNotice("Доступ участника обновлён.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось изменить участника");
    }
  }

  const authorizationExample = useMemo(() => {
    const first = workspace?.applications[0];
    if (!first || !workspace) return "";
    const parameters = new URLSearchParams({
      response_type: "code",
      client_id: first.client_id,
      redirect_uri: first.redirect_uris[0],
      scope: first.scopes.join(" "),
      state: "<random-state>",
      nonce: "<random-nonce>",
      code_challenge: "<pkce-s256-challenge>",
      code_challenge_method: "S256",
    });
    return `${workspace.integration.authorization_endpoint}?${parameters}`;
  }, [workspace]);

  return (
    <Shell
      title={workspace?.organization ? workspace.organization.name : "Мои контрагенты"}
      subtitle="Самостоятельная настройка входа через ODS"
      product="partner"
    >
      {error && <div className="alert error">{error}</div>}
      {notice && <div className="alert success">{notice}</div>}
      {loading && <div className="panel">Загрузка кабинета…</div>}

      {workspace && !workspace.organization && (
        <>
          {workspace.organizations.length > 0 && (
            <section className="panel" id="organizations">
              <p className="eyebrow">Контрагенты</p>
              <h2>Выберите компанию</h2>
              <p className="muted">
                Данные, участники, приложения и аналитика каждой компании доступны только
                на её собственном поддомене.
              </p>
              <div className="application-list">
                {workspace.organizations.map((organization) => (
                  <a className="application-card" href={organization.portal_url} key={organization.id}>
                    <div className="row between">
                      <div>
                        <h3>{organization.name}</h3>
                        <code>{organization.portal_url}</code>
                      </div>
                      <span className="badge success">{organization.role}</span>
                    </div>
                  </a>
                ))}
              </div>
            </section>
          )}
          <section className="panel narrow" id="organization">
            <p className="eyebrow">Новый контрагент</p>
            <h2>Создайте отдельный кабинет компании</h2>
            <p className="muted">
              Ваша личная учётная запись станет единственным владельцем организации.
              Кабинет будет доступен только на её поддомене: company.uz → company.ods.uz.
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
                placeholder="company"
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
                  : "Автоматически определяется из сайта"}
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
                placeholder="company.uz"
                value={organizationForm.websiteUrl}
                onChange={(event) => {
                  const websiteUrl = event.target.value;
                  setOrganizationForm({
                    ...organizationForm,
                    websiteUrl,
                    slug: organizationForm.slug || deriveSlug(websiteUrl),
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
            <button className="button" disabled={saving}>
              {saving ? "Создаём кабинет…" : "Создать кабинет"}
            </button>
            </form>
          </section>
        </>
      )}

      {workspace?.organization && (
        <>
          <div className="grid two" id="organization">
            <section className="panel">
              <p className="eyebrow">Организация</p>
              <h2>{workspace.organization.name}</h2>
              <dl className="detail-list">
                <div><dt>Код</dt><dd><code>{workspace.organization.slug}</code></dd></div>
                <div><dt>Роль</dt><dd>{workspace.organization.role}</dd></div>
                <div><dt>Контакт</dt><dd>{workspace.organization.contact_email}</dd></div>
                <div><dt>Статус</dt><dd><span className="badge success">{workspace.organization.status}</span></dd></div>
              </dl>
            </section>
            <section className="panel">
              <p className="eyebrow">Кабинет</p>
              <h2>{workspace.organization.portal_url}</h2>
              <p className="muted">
                Настройки этой организации изолированы от личного кабинета и системной
                админки.
              </p>
              <a className="text-button" href={onAuth("/partner")}>
                Все компании и создание нового контрагента
              </a>
            </section>
          </div>

          {secret && (
            <div className="alert warning secret-delivery">
              <strong>Сохраните secret сейчас. Повторно он не показывается.</strong>
              <span>Client ID</span>
              <code className="secret">{secret.clientId}</code>
              <span>Client secret</span>
              <code className="secret">{secret.value}</code>
              <CopyButton value={secret.value} label="Копировать secret" />
            </div>
          )}

          <section className="panel" id="application-editor">
            <p className="eyebrow">{editingId ? "Редактирование" : "Новое приложение"}</p>
            <h2>{editingId ? "Измените параметры SSO" : "Подключите сервис к ODS"}</h2>
            <p className="muted">
              Все параметры приложения настраиваются здесь. PKCE S256 обязателен для
              каждого приложения и не может быть отключён.
            </p>
            <ApplicationEditor
              form={applicationForm}
              supportedScopes={workspace.integration.supported_scopes}
              saving={saving}
              editing={Boolean(editingId)}
              onChange={setApplicationForm}
              onSubmit={saveApplication}
              onCancel={cancelEditing}
            />
          </section>

          <section className="panel" id="applications">
            <div className="row between">
              <div>
                <p className="eyebrow">SSO-приложения</p>
                <h2>Зарегистрированные сервисы</h2>
              </div>
              <span className="badge">{workspace.applications.length}</span>
            </div>
            {workspace.applications.length === 0 ? (
              <p className="muted">Приложений пока нет.</p>
            ) : (
              <div className="application-list">
                {workspace.applications.map((application) => (
                  <article className="application-card" key={application.id}>
                    <div className="row between">
                      <div>
                        <h3>{application.name}</h3>
                        <code>{application.client_id}</code>
                      </div>
                      <span className={`badge ${application.enabled ? "success" : "danger"}`}>
                        {application.enabled ? "Включено" : "Отключено"}
                      </span>
                    </div>
                    <div className="application-meta">
                      <span>Тип</span>
                      <strong>{application.client_type === "public" ? "Public + PKCE" : "Confidential + PKCE"}</strong>
                      <span>Аутентификация token endpoint</span>
                      <code>{application.token_endpoint_auth_method}</code>
                      <span>Scopes</span>
                      <code>{application.scopes.join(" ")}</code>
                      <span>Callback URL</span>
                      <div>{application.redirect_uris.map((uri) => <code key={uri}>{uri}</code>)}</div>
                      <span>Post logout URL</span>
                      <div>
                        {application.post_logout_redirect_uris.length
                          ? application.post_logout_redirect_uris.map((uri) => <code key={uri}>{uri}</code>)
                          : "Не задан"}
                      </div>
                    </div>
                    <div className="actions top-gap">
                      <button className="text-button" onClick={() => editApplication(application)}>
                        Изменить
                      </button>
                      <button className="text-button" onClick={() => toggleApplication(application)}>
                        {application.enabled ? "Отключить" : "Включить"}
                      </button>
                      {application.client_type === "confidential" && (
                        <button className="text-button danger" onClick={() => rotateSecret(application)}>
                          Выпустить новый secret
                        </button>
                      )}
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="panel" id="members">
            <div className="row between">
              <div>
                <p className="eyebrow">Доступ</p>
                <h2>Участники и роли</h2>
              </div>
              <span className="badge">{workspace.members.length}</span>
            </div>
            <p className="muted">
              Владелец один. Роли editor, user и reader передаются подключённым приложениям
              в claims <code>organization_role</code>, <code>roles</code> и <code>permissions</code>.
            </p>
            {workspace.organization.role === "owner" && (
              <form className="grid two top-gap" onSubmit={addMember}>
                <label>
                  Email зарегистрированного пользователя ODS
                  <input
                    required
                    type="email"
                    value={memberForm.email}
                    onChange={(event) => setMemberForm({ ...memberForm, email: event.target.value })}
                  />
                </label>
                <label>
                  Роль
                  <select
                    value={memberForm.role}
                    onChange={(event) =>
                      setMemberForm({
                        ...memberForm,
                        role: event.target.value as "editor" | "user" | "viewer",
                      })
                    }
                  >
                    <option value="editor">Редактор</option>
                    <option value="user">Пользователь</option>
                    <option value="viewer">Читатель</option>
                  </select>
                </label>
                <button className="button" disabled={saving}>Добавить участника</button>
              </form>
            )}
            <div className="table-wrap top-gap">
              <table>
                <thead>
                  <tr><th>Пользователь</th><th>Роль</th><th>Статус</th><th /></tr>
                </thead>
                <tbody>
                  {workspace.members.map((member) => (
                    <tr key={member.id}>
                      <td><b>{member.name || member.email}</b><br /><span>{member.email}</span></td>
                      <td>{member.role === "viewer" ? "reader" : member.role}</td>
                      <td>{member.status}</td>
                      <td>
                        {workspace.organization?.role === "owner" && member.role !== "owner" && (
                          <div className="actions">
                            <select
                              value={member.role}
                              onChange={(event) =>
                                updateMember(member, {
                                  role: event.target.value as Member["role"],
                                })
                              }
                            >
                              <option value="editor">Редактор</option>
                              <option value="user">Пользователь</option>
                              <option value="viewer">Читатель</option>
                            </select>
                            <button
                              className="text-button danger"
                              onClick={() =>
                                updateMember(member, {
                                  status: member.status === "active" ? "disabled" : "active",
                                })
                              }
                            >
                              {member.status === "active" ? "Отключить" : "Включить"}
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section className="panel" id="integration">
            <p className="eyebrow">Интеграция</p>
            <h2>Готовые параметры OpenID Connect</h2>
            <div className="integration-list">
              <span>Issuer</span><CopyableCode value={workspace.integration.issuer} />
              <span>Discovery</span><CopyableCode value={workspace.integration.discovery_url} />
              <span>Authorize</span><CopyableCode value={workspace.integration.authorization_endpoint} />
              <span>Token</span><CopyableCode value={workspace.integration.token_endpoint} />
              <span>UserInfo</span><CopyableCode value={workspace.integration.user_info_endpoint} />
              <span>JWKS</span><CopyableCode value={workspace.integration.jwks_url} />
              <span>Logout</span><CopyableCode value={workspace.integration.end_session_endpoint} />
            </div>
            {authorizationExample && (
              <div className="top-gap">
                <h3>Шаблон запроса Authorization Code + PKCE</h3>
                <code className="secret">{authorizationExample}</code>
                <CopyButton value={authorizationExample} label="Копировать шаблон" />
              </div>
            )}
          </section>
        </>
      )}
    </Shell>
  );
}

function partnerApi<T>(path: string, init?: RequestInit): Promise<T> {
  return apiAt<T>(window.location.origin, path, init);
}

function ApplicationEditor({
  form,
  supportedScopes,
  saving,
  editing,
  onChange,
  onSubmit,
  onCancel,
}: {
  form: ApplicationForm;
  supportedScopes: string[];
  saving: boolean;
  editing: boolean;
  onChange: (value: ApplicationForm) => void;
  onSubmit: (event: FormEvent) => void;
  onCancel: () => void;
}) {
  return (
    <form className="stack" onSubmit={onSubmit}>
      <div className="grid two">
        <label>
          Название приложения
          <input
            required
            minLength={2}
            value={form.name}
            onChange={(event) => onChange({ ...form, name: event.target.value })}
          />
        </label>
        <label>
          Тип клиента
          <select
            value={form.clientType}
            disabled={editing}
            onChange={(event) => {
              const clientType = event.target.value as ApplicationForm["clientType"];
              onChange({
                ...form,
                clientType,
                tokenEndpointAuthMethod:
                  clientType === "public" ? "none" : "client_secret_basic",
              });
            }}
          >
            <option value="confidential">Confidential — сервер хранит secret</option>
            <option value="public">Public — SPA/mobile, только PKCE</option>
          </select>
          {editing && <small className="muted">Тип клиента нельзя менять после создания.</small>}
        </label>
      </div>
      <label>
        Описание для экрана согласия
        <textarea
          rows={3}
          value={form.description}
          onChange={(event) => onChange({ ...form, description: event.target.value })}
        />
      </label>
      {form.clientType === "confidential" && (
        <label>
          Аутентификация token endpoint
          <select
            value={form.tokenEndpointAuthMethod}
            disabled={editing}
            onChange={(event) =>
              onChange({
                ...form,
                tokenEndpointAuthMethod:
                  event.target.value as ApplicationForm["tokenEndpointAuthMethod"],
              })
            }
          >
            <option value="client_secret_basic">client_secret_basic — рекомендуется</option>
            <option value="client_secret_post">client_secret_post</option>
          </select>
          {editing && (
            <small className="muted">Метод аутентификации нельзя менять после создания.</small>
          )}
        </label>
      )}
      <div className="grid two">
        <label>
          Callback URL — по одному в строке
          <textarea
            required
            rows={5}
            placeholder={"https://service.example.uz/auth/ods/callback\nhttp://localhost:3000/auth/ods/callback"}
            value={form.redirectUris}
            onChange={(event) => onChange({ ...form, redirectUris: event.target.value })}
          />
        </label>
        <label>
          URL после выхода — по одному в строке
          <textarea
            rows={5}
            placeholder={"https://service.example.uz/\nhttp://localhost:3000/"}
            value={form.postLogoutRedirectUris}
            onChange={(event) =>
              onChange({ ...form, postLogoutRedirectUris: event.target.value })
            }
          />
        </label>
      </div>
      <fieldset className="scope-picker">
        <legend>Доступ к данным пользователя</legend>
        {supportedScopes.map((scope) => (
          <label className="checkbox" key={scope}>
            <input
              type="checkbox"
              checked={form.scopes.includes(scope)}
              disabled={scope === "openid"}
              onChange={(event) =>
                onChange({
                  ...form,
                  scopes: event.target.checked
                    ? [...form.scopes, scope]
                    : form.scopes.filter((item) => item !== scope),
                })
              }
            />
            <span><code>{scope}</code> — {scopeLabels[scope] || scope}</span>
          </label>
        ))}
      </fieldset>
      <div className="actions">
        <button className="button" disabled={saving}>
          {saving ? "Сохраняем…" : editing ? "Сохранить изменения" : "Создать приложение"}
        </button>
        {editing && (
          <button className="button secondary" type="button" onClick={onCancel}>
            Отмена
          </button>
        )}
      </div>
    </form>
  );
}

function applicationPayload(form: ApplicationForm) {
  return {
    name: form.name,
    description: form.description || null,
    redirect_uris: splitLines(form.redirectUris),
    post_logout_redirect_uris: splitLines(form.postLogoutRedirectUris),
    scopes: form.scopes,
    client_type: form.clientType,
    token_endpoint_auth_method: form.tokenEndpointAuthMethod,
  };
}

function splitLines(value: string): string[] {
  return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
}

function CopyableCode({ value }: { value: string }) {
  return (
    <div className="copyable-code">
      <code>{value}</code>
      <CopyButton value={value} label="Копировать" />
    </div>
  );
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="text-button"
      type="button"
      onClick={async () => {
        await navigator.clipboard.writeText(value);
        setCopied(true);
        window.setTimeout(() => setCopied(false), 1500);
      }}
    >
      {copied ? "Скопировано" : label}
    </button>
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
