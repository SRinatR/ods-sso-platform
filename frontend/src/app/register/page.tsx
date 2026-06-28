"use client";

import Link from "next/link";
import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";
import { onAuth, onPartners } from "@/lib/domains";
import { transliterateCyrillicName } from "@/lib/full-name";

type RegistrationResponse = {
  message: string;
  verification_required: boolean;
};

function RegisterForm() {
  const partner = useSearchParams().get("kind") === "partner";
  const [form, setForm] = useState({
    email: "",
    password: "",
    fullNameCyrillic: "",
    fullNameLatin: "",
  });
  const [latinTouched, setLatinTouched] = useState(false);
  const [message, setMessage] = useState("");
  const [verificationRequired, setVerificationRequired] = useState(false);
  const [error, setError] = useState("");
  const [resending, setResending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      const result = await api<RegistrationResponse>("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({
          email: form.email,
          password: form.password,
          full_name_cyrillic: form.fullNameCyrillic,
          full_name_latin: form.fullNameLatin || transliterateCyrillicName(form.fullNameCyrillic),
        }),
      });
      setVerificationRequired(result.verification_required);
      setMessage(
        result.verification_required
          ? "Запрос принят. Если email ожидает подтверждения, Resend принял новое письмо для доставки. Проверьте «Входящие» и «Спам»."
          : "Аккаунт создан. Теперь можно войти.",
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Регистрация не выполнена");
    }
  }

  return (
    <AuthCard
      title={partner ? "Регистрация контрагента" : "Регистрация"}
      subtitle={
        partner
          ? "Сначала создайте личную учетную запись, затем организацию и её кабинет"
          : "Создайте единую учетную запись"
      }
    >
      {message ? (
        <>
          <div className="alert success">{message}</div>
          {verificationRequired && (
            <button
              className="button secondary link-button"
              disabled={resending}
              onClick={async () => {
                setError("");
                setResending(true);
                try {
                  await api("/api/v1/auth/resend-verification", {
                    method: "POST",
                    body: JSON.stringify({ email: form.email }),
                  });
                  setMessage(
                    "Запрос повторной отправки принят. Если аккаунт существует и email ещё не подтверждён, Resend принял новое письмо для доставки.",
                  );
                } catch (cause) {
                  setError(
                    cause instanceof Error
                      ? cause.message
                      : "Почтовый сервис не принял письмо. Повторите позже.",
                  );
                } finally {
                  setResending(false);
                }
              }}
            >
              {resending ? "Передаём в Resend…" : "Отправить письмо ещё раз"}
            </button>
          )}
          {error && <div className="alert error">{error}</div>}
        </>
      ) : (
        <form onSubmit={submit} className="stack">
          {error && <div className="alert error">{error}</div>}
          <label>
            Email
            <input
              type="email"
              required
              value={form.email}
              onChange={(event) => setForm({ ...form, email: event.target.value })}
            />
          </label>
          <label>
            Полное ФИО на кириллице
            <input
              maxLength={255}
              required
              value={form.fullNameCyrillic}
              onChange={(event) => {
                const fullNameCyrillic = event.target.value;
                setForm({
                  ...form,
                  fullNameCyrillic,
                  fullNameLatin: latinTouched
                    ? form.fullNameLatin
                    : transliterateCyrillicName(fullNameCyrillic),
                });
              }}
            />
          </label>
          <label>
            Полное ФИО на латинице
            <input
              maxLength={255}
              pattern="[A-Za-z\\s'’\\-]+"
              required
              value={form.fullNameLatin}
              onChange={(event) => {
                setLatinTouched(true);
                setForm({ ...form, fullNameLatin: event.target.value });
              }}
            />
            <span className="field-hint">
              Заполняется автоматически; вручную можно изменить до 3 символов.
            </span>
          </label>
          <label>
            Пароль
            <input
              type="password"
              minLength={12}
              maxLength={128}
              required
              value={form.password}
              onChange={(event) => setForm({ ...form, password: event.target.value })}
            />
          </label>
          <p className="legal-consent">
            Нажимая «Создать аккаунт», вы принимаете{" "}
            <Link href="/privacy">условия использования и политику конфиденциальности</Link>.
          </p>
          <button className="button">Создать аккаунт</button>
        </form>
      )}
      <div className="auth-links">
        <Link
          href={onAuth(
            partner ? `/login?return_to=${encodeURIComponent(onPartners("/"))}` : "/login",
          )}
        >
          Вернуться ко входу
        </Link>
        {!partner && <Link href={onAuth("/register?kind=partner")}>Для контрагентов</Link>}
      </div>
    </AuthCard>
  );
}

export default function RegisterPage() {
  return (
    <Suspense>
      <RegisterForm />
    </Suspense>
  );
}
