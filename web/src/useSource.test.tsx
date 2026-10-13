import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useSource } from "./useSource";

beforeEach(() => {
  const token = document.createElement("meta");
  token.name = "viewer-token"; token.content = "test-token";
  document.head.append(token);
});
afterEach(() => { document.querySelector('meta[name="viewer-token"]')?.remove(); vi.unstubAllGlobals(); });

it("discards source replies for a previously selected class", async () => {
  let complete: (value: Response) => void = () => { throw new Error("Request not started"); };
  const first = new Promise<Response>(resolve => { complete = resolve; });
  vi.stubGlobal("fetch", vi.fn()
    .mockReturnValueOnce(first)
    .mockResolvedValueOnce(new Response(JSON.stringify({ body: "new", file: "new.py", line: null, lang: "python", revision: "r" }))));
  const { result, rerender } = renderHook(({ id }) => useSource("r", id, null, true), { initialProps: { id: "old" } });
  rerender({ id: "new" });
  await waitFor(() => expect(result.current.data?.body).toBe("new"));
  await act(async () => { complete(new Response(JSON.stringify({ body: "old", revision: "r" }))); await first; });
  expect(result.current.data?.body).toBe("new");
});

it("surfaces missing source explicitly", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: { code: "missing-source", message: "No source file." } }), { status: 404 })));
  const { result } = renderHook(() => useSource("r", "class", null, true));
  await waitFor(() => expect(result.current.error).toBe("No source file."));
  expect(result.current.data).toBeNull();
});
