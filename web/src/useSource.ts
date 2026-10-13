import { useEffect, useState } from "react";
import { message, request } from "./api";
import type { SourceResult } from "./types";

export function useSource(revision: string, id: string, member: string | null, enabled: boolean) {
  const [state, setState] = useState<{ data: SourceResult | null; error: string | null; loading: boolean }>({ data: null, error: null, loading: false });
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let active = true;
    setState({ data: null, error: null, loading: true });
    request<SourceResult>("/api/source", { revision, classId: id, member }, controller.signal)
      .then(data => { if (active) setState({ data, error: null, loading: false }); })
      .catch(error => { if (active) setState({ data: null, error: message(error), loading: false }); });
    return () => { active = false; controller.abort(); };
  }, [revision, id, member, enabled]);
  return state;
}
