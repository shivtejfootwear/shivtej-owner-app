// Shivtej Footwear - starts an online payment for one order (Supabase Edge Function).
//
// POST /functions/v1/create-payment
//   { "order_id": "SW...", "mobile": "9876543210" }
//   -> 200 { key_id, razorpay_order_id, amount, currency, name, mobile, order_id }
//   -> 400 BAD_INPUT | 400 NOT_ONLINE | 404 NOT_FOUND | 409 ALREADY_PAID | 502 GATEWAY
//
// The amount is read from the order in the database, never from the browser.
// The order is saved with its Razorpay order id; the payment-webhook then marks it Paid.
// Secrets: RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET. Provided automatically: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const KEY_ID = Deno.env.get("RAZORPAY_KEY_ID") ?? "";
const KEY_SECRET = Deno.env.get("RAZORPAY_KEY_SECRET") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

// Online only: the order must be waiting for payment and must not be cash on delivery.
function isPendingOnline(s: unknown): boolean {
  const v = String(s ?? "").toLowerCase();
  return v.includes("pending") && !v.includes("cod") && !v.includes("paid");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json(405, { error: "METHOD" });
  if (!SUPABASE_URL || !SERVICE_KEY || !KEY_ID || !KEY_SECRET) return json(500, { error: "NOT_CONFIGURED" });

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "BAD_INPUT" });
  }
  const orderId = String(body?.order_id ?? "").trim().toUpperCase();
  const mobile = String(body?.mobile ?? "").replace(/\D/g, "");
  if (!/^SW[0-9A-Z]{6,40}$/.test(orderId) || !/^[6-9]\d{9}$/.test(mobile)) return json(400, { error: "BAD_INPUT" });

  const db = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };
  const r = await fetch(`${SUPABASE_URL}/rest/v1/orders?id=eq.${encodeURIComponent(orderId)}&select=*`, { headers: db });
  const rows: any[] = r.ok ? await r.json() : [];
  const o = rows[0];
  // Same answer for "no such order" and "wrong mobile".
  if (!o || String(o.customer?.mobile ?? "") !== mobile) return json(404, { error: "NOT_FOUND" });
  if (String(o.payment_status ?? "").toLowerCase().includes("paid") && !String(o.payment_status).toLowerCase().includes("unpaid")) {
    return json(409, { error: "ALREADY_PAID" });
  }
  if (!isPendingOnline(o.payment_status)) return json(400, { error: "NOT_ONLINE" });

  const amount = Math.round(Number(o.total ?? 0) * 100); // paise
  if (!Number.isFinite(amount) || amount < 100) return json(400, { error: "BAD_AMOUNT" });

  // 1) Create the Razorpay order (amount in paise).
  const rz = await fetch("https://api.razorpay.com/v1/orders", {
    method: "POST",
    headers: {
      Authorization: "Basic " + btoa(`${KEY_ID}:${KEY_SECRET}`),
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ amount, currency: "INR", receipt: o.id, notes: { order_id: o.id } }),
  });
  const rzJson: any = await rz.json().catch(() => ({}));
  if (!rz.ok || !rzJson.id) {
    console.error("create-payment: razorpay error", rz.status, JSON.stringify(rzJson).slice(0, 300));
    return json(502, { error: "GATEWAY" });
  }

  // 2) Save it on the order, so the webhook can find the order later.
  const p = await fetch(`${SUPABASE_URL}/rest/v1/orders?id=eq.${encodeURIComponent(o.id)}`, {
    method: "PATCH",
    headers: { ...db, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify({ razorpay_order_id: rzJson.id, updated_at: new Date().toISOString() }),
  });
  if (!p.ok) return json(500, { error: "SAVE_FAILED" });

  return json(200, {
    key_id: KEY_ID,
    razorpay_order_id: rzJson.id,
    amount,
    currency: "INR",
    name: "Shivtej Footwear",
    order_id: o.id,
    mobile,
    customer_name: String(o.customer?.name ?? ""),
  });
});
