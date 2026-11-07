import type { StreamState } from "./types";

export interface SseEvent {
  id: string | null;
  event: string;
  data: string;
}

/**
 * Server-sent events over fetch, because EventSource cannot send an Authorization header.
 * Reconnects with backoff (1 s doubling to 15 s) and sends Last-Event-ID, which tracking-svc
 * uses to send only what was missed.
 */
export function openStream(
  url: string,
  headers: () => Promise<Record<string, string>>,
  onEvent: (e: SseEvent) => void,
  onState: (s: StreamState) => void,
): () => void {
  let closed = false;
  let lastId: string | null = null;
  let controller: AbortController | null = null;
  let delay = 1000;

  const run = async () => {
    while (!closed) {
      controller = new AbortController();
      onState(lastId === null ? "connecting" : "reconnecting");
      try {
        const h: Record<string, string> = { Accept: "text/event-stream", ...(await headers()) };
        if (lastId !== null) h["Last-Event-ID"] = lastId;
        const res = await fetch(url, { headers: h, signal: controller.signal });
        if (!res.ok || !res.body) throw new Error(`stream answered ${res.status}`);
        onState("live");
        delay = 1000;
        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += value;
          let cut: number;
          while ((cut = buffer.indexOf("\n\n")) >= 0) {
            const block = buffer.slice(0, cut);
            buffer = buffer.slice(cut + 2);
            const e: SseEvent = { id: null, event: "message", data: "" };
            const data: string[] = [];
            for (const line of block.split("\n")) {
              if (line.startsWith(":")) continue; // keepalive comment
              const i = line.indexOf(":");
              const field = i < 0 ? line : line.slice(0, i);
              const v = i < 0 ? "" : line.slice(i + 1).replace(/^ /, "");
              if (field === "id") e.id = v;
              else if (field === "event") e.event = v;
              else if (field === "data") data.push(v);
            }
            if (data.length === 0) continue;
            e.data = data.join("\n");
            if (e.id !== null) lastId = e.id;
            onEvent(e);
          }
        }
      } catch (err) {
        if (closed) break;
        void err;
      }
      if (closed) break;
      onState("reconnecting");
      await new Promise((r) => setTimeout(r, delay));
      delay = Math.min(delay * 2, 15_000);
    }
    onState("closed");
  };
  void run();
  return () => {
    closed = true;
    controller?.abort();
  };
}
