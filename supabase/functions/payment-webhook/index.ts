// Shivtej Footwear - Razorpay payment webhook (Supabase Edge Function).
//
// Razorpay calls this URL when a payment is captured (event payment.captured).
// It checks the signature with RAZORPAY_WEBHOOK_SECRET.
// New website orders: the order (SW number) is created now, the stock is cut and it is marked Paid
// (site_finalize_checkout). The orders trigger then sets it to "Confirmed".
// Old orders (placed before the pay-first change) are still marked Paid the old way.
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

const DB = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };

// Old orders: find the order by its Razorpay order id and mark it Paid.
async function markOldOrderPaid(rzOrderId: string, rzPaymentId: string, paidPaise: number): Promise<Response> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/orders?razorpay_order_id=eq.${encodeURIComponent(rzOrderId)}&select=id,total,payment_status`, { headers: DB });
  const rows: any[] = r.ok ? await r.json() : [];
  const o = rows[0];
  if (!o) {
    console.error("payment-webhook: no order or checkout for", rzOrderId);
    return json(200, { ok: true, ignored: true });
  }
  const v = String(o.payment_status ?? "").toLowerCase();
  if (v.includes("paid") && !v.includes("unpaid")) return json(200, { ok: true, already: true });
  if (Math.round(Number(o.total ?? 0) * 100) !== paidPaise) {
    console.error("payment-webhook: amount mismatch", o.id, paidPaise);
    return json(200, { ok: true, mismatch: true });
  }
  const p = await fetch(`${SUPABASE_URL}/rest/v1/orders?id=eq.${encodeURIComponent(o.id)}`, {
    method: "PATCH",
    headers: { ...DB, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify({ payment_status: "Paid", razorpay_payment_id: rzPaymentId, updated_at: new Date().toISOString() }),
  });
  if (!p.ok) {
    console.error("payment-webhook: save failed", p.status);
    return json(500, { error: "SAVE_FAILED" });
  }
  return json(200, { ok: true });
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
  const paidPaise = Number(pay.amount);
  if (!rzOrderId || !rzPaymentId) return json(200, { ok: true, ignored: true });

  try {
    // New website order: create the order now (stock is cut and the order is marked Paid).
    const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/site_finalize_checkout`, {
      method: "POST",
      headers: { ...DB, "Content-Type": "application/json" },
      body: JSON.stringify({ p_rz_order: rzOrderId, p_rz_payment: rzPaymentId, p_paid_paise: paidPaise }),
    });
    const text = await r.text();
    if (r.ok) return json(200, { ok: true });

    if (text.includes("NO_CHECKOUT")) return markOldOrderPaid(rzOrderId, rzPaymentId, paidPaise);

    // Paid, but the order could not be created (for example the stock was sold meanwhile).
    // Keep the record, so the shop can refund this payment. Answer 200 so Razorpay does not retry.
    console.error("payment-webhook: order not created", rzOrderId, rzPaymentId, text.slice(0, 300));
    await fetch(`${SUPABASE_URL}/rest/v1/site_checkouts?razorpay_order_id=eq.${encodeURIComponent(rzOrderId)}`, {
      method: "PATCH",
      headers: { ...DB, "Content-Type": "application/json", Prefer: "return=minimal" },
      body: JSON.stringify({ status: "failed_after_payment", razorpay_payment_id: rzPaymentId }),
    });
    return json(200, { ok: true, failed: true });
  } catch (e) {
    console.error("payment-webhook error", e);
    return json(500, { error: "SERVER" });
  }
});
