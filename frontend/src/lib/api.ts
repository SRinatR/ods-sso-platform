export const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export type ApiError = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  error?: string;
  message?: string;
  details?: Array<Record<string, unknown>>;
  request_id?: string;
};

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({
      detail: "Request failed",
    }))) as Partial<ApiError>;
    throw new Error(
      payload.detail ||
        payload.message ||
        payload.title ||
        payload.error ||
        `HTTP ${response.status}`,
    );
  }
  return (await response.json()) as T;
}
