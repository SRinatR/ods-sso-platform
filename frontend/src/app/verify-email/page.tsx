"use client";

import Link from "next/link";
import { AuthCard } from "@/components/Shell";
import { onAuth } from "@/lib/domains";

export default function VerifyEmailPage() {
  return (
    <AuthCard
      title="Подтверждение email"
      subtitle="Подтверждение по ссылке больше не используется. Введите код из письма на странице регистрации."
    >
      <Link className="button link-button" href={onAuth("/register")}>
        Вернуться к регистрации
      </Link>
    </AuthCard>
  );
}
