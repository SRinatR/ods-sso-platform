import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ODS Identity",
  description: "Secure account, sessions, MFA and connected applications",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>{children}</body>
    </html>
  );
}

