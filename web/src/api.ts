export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

export async function request<T>(
  path: string,
  body?: object,
  signal?: AbortSignal,
): Promise<T> {
  const token = document.querySelector<HTMLMetaElement>(
    'meta[name="viewer-token"]',
  )?.content;
  if (!token || token === "__VIEWER_TOKEN__")
    throw new Error(
      "Viewer authentication is unavailable. Open the URL printed by the Clojure server.",
    );
  const response = await fetch(path, {
    method: body ? "POST" : "GET",
    headers: { "Content-Type": "application/json", "X-Viewer-Token": token },
    credentials: "same-origin",
    body: body ? JSON.stringify(body) : undefined,
    signal,
  });
  const data = await response.json();
  if (!response.ok)
    throw new ApiError(
      data.error?.code ?? "request-failed",
      data.error?.message ?? `Request failed (${response.status}).`,
    );
  return data as T;
}

export function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
