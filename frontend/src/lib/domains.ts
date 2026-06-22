export const ROOT_URL = process.env.NEXT_PUBLIC_ROOT_URL || "http://localhost";
export const AUTH_URL = process.env.NEXT_PUBLIC_AUTH_URL || "http://account.localhost";
export const ACCOUNTS_URL =
  process.env.NEXT_PUBLIC_ACCOUNTS_URL || "http://accounts.localhost";
export const ADMIN_URL = process.env.NEXT_PUBLIC_ADMIN_URL || "http://admin.localhost";
export const ROOT_DOMAIN = process.env.NEXT_PUBLIC_ROOT_DOMAIN || "localhost";

export function onAuth(path: string): string {
  return new URL(path, `${AUTH_URL}/`).toString();
}

export function onAccounts(path: string): string {
  return new URL(path, `${ACCOUNTS_URL}/`).toString();
}

export function onAdmin(path: string): string {
  return new URL(path, `${ADMIN_URL}/`).toString();
}

export function loginUrl(returnTo: string): string {
  return onAuth(`/login?return_to=${encodeURIComponent(returnTo)}`);
}

export function isTrustedReturnUrl(value: string): boolean {
  try {
    const candidate = new URL(value);
    if (candidate.protocol !== "https:" && ROOT_DOMAIN !== "localhost") return false;
    return (
      candidate.hostname === ROOT_DOMAIN ||
      candidate.hostname.endsWith(`.${ROOT_DOMAIN}`)
    );
  } catch {
    return value.startsWith("/") && !value.startsWith("//");
  }
}
