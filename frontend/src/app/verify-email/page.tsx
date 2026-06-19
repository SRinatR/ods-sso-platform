"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AuthCard } from "@/components/Shell";
import { api } from "@/lib/api";

function Verification() {
  const token = useSearchParams().get("token");
  const [status, setStatus] = useState(
    token ? "Подтверждаем email…" : "Ссылка подтверждения неполная.",
  );

  useEffect(() => {
    if (!token) {
      return;
    }
    api("/api/v1/auth/verify-email", {
      method: "POST",
      body: JSON.stringify({ token }),
    })
      .then(() => setStatus("Email подтвержден. Теперь можно войти."))
      .catch((cause) => setStatus(cause instanceof Error ? cause.message : "Ошибка подтверждения"));
  }, [token]);

  return (
    <AuthCard title="Подтверждение email" subtitle={status}>
      <Link className="button link-button" href="/login">
        Перейти ко входу
      </Link>
    </AuthCard>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense>
      <Verification />
    </Suspense>
  );
}
