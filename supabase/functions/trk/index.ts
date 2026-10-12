// Shivtej Footwear - Shiprocket tracking webhook (Supabase Edge Function).
// Same logic as shiprocket-webhook and order-update-hook. Short name "trk" for a short webhook URL.
//
// Shiprocket calls this URL on every tracking event (Settings > API > Webhooks).
//   POST /functions/v1/trk
//   Header x-api-key: <the security token you set in Shiprocket>
//   Body: { awb, current_status, courier_name, order_id, sr_order_id, scans, ... }
// It finds the order by AWB, saves the live status for the customer, and moves the order to
// Shipped / Delivered. It never reverses a Delivered order and never touches a Cancelled one.
// Always answers 200 for a valid, signed call (Shiprocket retries otherwise).
//
// Secrets: SHIPROCKET_WEBHOOK_TOKEN (same value as the one set in Shiprocket).
// Provided automatically: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const WEBHOOK_TOKEN = Deno.env.get("SHIPROCKET_WEBHOOK_TOKEN") ?? "";

const DB_HEADERS = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

// Shiprocket status text -> our order status. null = keep the order status as it is.
export function mapStatus(status: string): string | null {
  const s = status.toLowerCase();
  if (s.includes("undelivered")) return null;
  if (s.includes("delivered")) return "Delivered";
  if (s.includes("rto") || s.includes("return") || s.includes("cancel") || s.includes("lost") || s.includes("damage")) return null;
  if (s.includes("picked") || s.includes("transit") || s.includes("out for delivery") || s.includes("shipped")) return "Shipped";
  return null;
}

const RANK: Record<string, number> = { New: 0, Confirmed: 1, Packed: 2, Shipped: 3, Delivered: 4 };

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json(405, { error: "METHOD" });
  if (!SUPABASE_URL || !SERVICE_KEY || !WEBHOOK_TOKEN) {
    console.error("trk: secrets missing");
    return json(500, { error: "NOT_CONFIGURED" });
  }
  if ((req.headers.get("x-api-key") ?? "") !== WEBHOOK_TOKEN) return json(401, { error: "BAD_KEY" });

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "BAD_JSON" });
  }
  const awb = String(body?.awb ?? "").trim();
  const status = String(body?.current_status ?? "").trim();
  if (!awb || !status) return json(200, { ok: true, ignored: true });

  try {
    const r = await fetch(`${SUPABASE_URL}/rest/v1/orders?tracking_no=eq.${encodeURIComponent(awb)}&select=id,order_status`, { headers: DB_HEADERS });
    const rows: any[] = r.ok ? await r.json() : [];
    const o = rows[0];
    if (!o) return json(200, { ok: true, ignored: true });

    const next = mapStatus(status);
    const change: Record<string, unknown> = { shipment_status: status, updated_at: new Date().toISOString() };
    if (next && o.order_status !== "Cancelled" && (RANK[next] ?? 0) > (RANK[o.order_status] ?? -1)) {
      change.order_status = next;
    }
    const p = await fetch(`${SUPABASE_URL}/rest/v1/orders?id=eq.${encodeURIComponent(o.id)}`, {
      method: "PATCH",
      headers: { ...DB_HEADERS, "Content-Type": "application/json", Prefer: "return=minimal" },
      body: JSON.stringify(change),
    });
    if (!p.ok) console.error("trk: save failed", p.status);
    return json(200, { ok: true });
  } catch (e) {
    console.error("trk error", e);
    return json(500, { error: "SERVER" });
  }
});
