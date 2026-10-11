// Shivtej Footwear - Razorpay payment webhook (Supabase Edge Function).
//
// Razorpay calls this URL when a payment is captured (event payment.captured).
// It checks the signature with RAZORPAY_WEBHOOK_SECRET, finds the order by its Razorpay order id,
// checks the amount, and marks the order "Paid". The orders trigger then sets it to "Confirmed".
// Always answers 200 for a valid, signed call (Razorpay retries otherwise).
// Secrets: RAZORPAY_WEBHOOK_SECRET. Provided automatically: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const WEBHOOK_SECRET = Deno.env.get("RAZORPAY_WEBHOOK_SECRET") ?? "";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function hmacHex(secret: string, text: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text));
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json(405, { error: "METHOD" });
  if (!SUPABASE_URL || !SERVICE_KEY || !WEBHOOK_SECRET) {
    console.error("payment-webhook: secrets missing");
    return json(500, { error: "NOT_CONFIGURED" });
  }

  // Razorpay signs the exact raw body, so read it as text first.
  const raw = await req.text();
  const sent = req.headers.get("x-razorpay-signature") ?? "";
  if (!sent || (await hmacHex(WEBHOOK_SECRET, raw)) !== sent) return json(401, { error: "BAD_SIGNATURE" });

  let evt: any;
  try {
    evt = JSON.parse(raw);
  } catch {
    return json(400, { error: "BAD_JSON" });
  }
  if (evt?.event !== "payment.captured") return json(200, { ok: true, ignored: true });

  const pay = evt?.payload?.payment?.entity ?? {};
  const rzOrderId = String(pay.order_id ?? "");
  const rzPaymentId = String(pay.id ?? "");
  if (!rzOrderId || !rzPaymentId) return json(200, { ok: true, ignored: true });

  const db = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };
  try {
    const r = await fetch(`${SUPABASE_URL}/rest/v1/orders?razorpay_order_id=eq.${encodeURIComponent(rzOrderId)}&select=id,total,payment_status`, { headers: db });
    const rows: any[] = r.ok ? await r.json() : [];
    const o = rows[0];
    if (!o) {
      console.error("payment-webhook: no order for", rzOrderId);
      return json(200, { ok: true, ignored: true });
    }
    const v = String(o.payment_status ?? "").toLowerCase();
    if (v.includes("paid") && !v.includes("unpaid")) return json(200, { ok: true, already: true });

    // The amount paid must match the order total (paise).
    if (Math.round(Number(o.total ?? 0) * 100) !== Number(pay.amount)) {
      console.error("payment-webhook: amount mismatch", o.id, pay.amount);
      return json(200, { ok: true, mismatch: true });
    }

    const p = await fetch(`${SUPABASE_URL}/rest/v1/orders?id=eq.${encodeURIComponent(o.id)}`, {
      method: "PATCH",
      headers: { ...db, "Content-Type": "application/json", Prefer: "return=minimal" },
      body: JSON.stringify({ payment_status: "Paid", razorpay_payment_id: rzPaymentId, updated_at: new Date().toISOString() }),
    });
    if (!p.ok) {
      console.error("payment-webhook: save failed", p.status);
      return json(500, { error: "SAVE_FAILED" });
    }
    return json(200, { ok: true });
  } catch (e) {
    console.error("payment-webhook error", e);
    return json(500, { error: "SERVER" });
  }
});
