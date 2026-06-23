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

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

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
    const retryAfterSeconds = Number(
      response.headers.get("Retry-After") ||
        payload.details?.[0]?.retry_after_seconds ||
        0,
    );
    const fallback =
      payload.detail ||
      payload.message ||
      payload.title ||
      payload.error ||
      `HTTP ${response.status}`;
    const message =
      response.status === 429
        ? `Слишком много попыток. Повторите через ${formatRetry(retryAfterSeconds)}.`
        : fallback;
    throw new ApiRequestError(
      message,
      response.status,
      payload.error,
      retryAfterSeconds || undefined,
    );
  }
  return (await response.json()) as T;
}

function formatRetry(seconds: number): string {
  if (!seconds || seconds < 60) return `${Math.max(1, seconds)} сек.`;
  if (seconds < 3600) return `${Math.ceil(seconds / 60)} мин.`;
  return `${Math.ceil(seconds / 3600)} ч.`;
}
