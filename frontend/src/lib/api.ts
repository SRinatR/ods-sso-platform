export const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export type ApiError = {
  error: string;
  message: string;
  details: Array<Record<string, unknown>>;
  request_id: string;
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
      message: "Request failed",
    }))) as Partial<ApiError>;
    throw new Error(payload.message || payload.error || `HTTP ${response.status}`);
  }
  return (await response.json()) as T;
}

