// Shivtej Footwear - starts the online payment for a NEW website order (Supabase Edge Function).
//
// POST /functions/v1/create-payment
//   { "token": "<customer login token>", "name": "...", "country": "India", "state": "...", "city": "...",
//     "pincode": "413101", "address": "...", "payment": "ONLINE",
//     "items": [{ "product_id": "...", "colour": "...", "size": "9", "qty": 1 }], "coupon": "",
//     "courier_id": "<optional: the courier the customer chose from ship-rates>" }
//   With courier_id the shipping charge is that courier's Shiprocket price (else the fixed shipping charge);
//   400 COURIER_UNAVAILABLE when that courier no longer serves the pincode.
//   -> 200 { key_id, razorpay_order_id, amount, currency, name, mobile, checkout_id, customer_name,
//            subtotal, discount, coupon, shipping, total }
//   -> 401 NOT_LOGGED_IN | 400 INVALID_DETAILS | INVALID_ITEMS | NOT_ONLINE | STOCK | RATE_LIMIT |
//      COUPON_INVALID | COUPON_NOT_FOR_CART | 502 GATEWAY
//
// No order is saved here and no stock is cut. The mobile number is the logged-in customer's number.
// The order is created only when the payment is received (payment-webhook -> site_finalize_checkout).
// Secrets: RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, OTP_PEPPER (same as customer-otp).
// Provided automatically: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const KEY_ID = Deno.env.get("RAZORPAY_KEY_ID") ?? "";
const KEY_SECRET = Deno.env.get("RAZORPAY_KEY_SECRET") ?? "";
const OTP_PEPPER = Deno.env.get("OTP_PEPPER") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

// ---------- courier chosen by the customer (optional "courier_id") ----------
// The price is asked from Shiprocket here again, never taken from the browser. Same rules as ship-rates:
// 500 g per pair, pickup 413101, prepaid. Secrets: SHIPROCKET_EMAIL, SHIPROCKET_PASSWORD.
const SR_EMAIL = Deno.env.get("SHIPROCKET_EMAIL") ?? "";
const SR_PASSWORD = Deno.env.get("SHIPROCKET_PASSWORD") ?? "";
const SR_BASE = "https://apiv2.shiprocket.in/v1/external";
const SHOP_PIN = "413101";
const PAIR_WEIGHT_KG = 0.5;

// Full Shiprocket charge for one courier - same rule as ship-rates (the larger of "rate" and the charge parts).
// The Shiprocket panel shows every courier exactly Rs 5 more than the API "rate" (checked 17 Oct 2026:
// Blue Dart 132.84 -> 137.84, India Post 46.02 -> 51.02, Shadowfax 74.72 -> 79.72), so that Rs 5 is added here.
const SHIPROCKET_EXTRA = 5;
function chargeOf(c: any): number {
  const n = (v: any) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const parts = n(c.freight_charge) + n(c.coverage_charges) + n(c.other_charges) + n(c.cod_charges);
  return Math.ceil(Math.max(n(c.rate), n(c.freight_charge), parts) + SHIPROCKET_EXTRA);
}

async function courierQuote(pin: string, pairs: number, courierId: string, value: number) {
  const login = await fetch(`${SR_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: SR_EMAIL, password: SR_PASSWORD }),
  });
  const lj: any = await login.json().catch(() => ({}));
  if (!login.ok || !lj?.token) throw new Error(`shiprocket login failed (${login.status})`);
  const weight = Math.max(PAIR_WEIGHT_KG, pairs * PAIR_WEIGHT_KG);
  const q = `pickup_postcode=${SHOP_PIN}&delivery_postcode=${pin}&cod=0&weight=${weight}` + (value > 0 ? `&declared_value=${value}` : "");
  const r = await fetch(`${SR_BASE}/courier/serviceability/?${q}`, { headers: { Authorization: `Bearer ${lj.token}` } });
  const j: any = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`serviceability failed (${r.status})`);
  const c = (j?.data?.available_courier_companies ?? []).find((x: any) => String(x.courier_company_id ?? x.id ?? "") === courierId);
  if (!c) return null;
  const rate = chargeOf(c);
  if (!(rate > 0)) return null;
  return {
    id: courierId, name: String(c.courier_name ?? c.name ?? "").slice(0, 80), rate,
    days: Number(c.estimated_delivery_days ?? 0) || null, etd: String(c.etd ?? "").slice(0, 40),
  };
}

// Error words the database can raise (checkout rules); anything else is a server problem.
const KNOWN_ERRORS = [
  "INVALID_DETAILS", "INVALID_ITEMS", "NOT_ONLINE", "RATE_LIMIT", "STOCK",
  "COUPON_NOT_FOR_CART", "COUPON_INVALID",
];

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

async function sha256Hex(text: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

const DB = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };

async function dbSelect(query: string): Promise<any[]> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${query}`, { headers: DB });
  if (!r.ok) throw new Error(`select failed (${r.status})`);
  return r.json();
}

// The login token from customer-otp -> the customer's mobile number. Null = not logged in.
async function loggedInMobile(token: string): Promise<{ mobile: string; name: string } | null> {
  if (!/^[a-f0-9]{64}$/.test(token)) return null;
  const sessions = await dbSelect(
    `customer_sessions?token_hash=eq.${await sha256Hex(`session:${token}:${OTP_PEPPER}`)}&select=customer_id,expires_at`,
  );
  const s = sessions[0];
  if (!s || new Date(s.expires_at).getTime() < Date.now()) return null;
  const me = (await dbSelect(`customers?id=eq.${s.customer_id}&select=mobile,name`))[0];
  if (!me || !/^[6-9]\d{9}$/.test(String(me.mobile))) return null;
  return { mobile: String(me.mobile), name: String(me.name ?? "") };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json(405, { error: "METHOD" });
  if (!SUPABASE_URL || !SERVICE_KEY || !KEY_ID || !KEY_SECRET || !OTP_PEPPER) return json(500, { error: "NOT_CONFIGURED" });

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "INVALID_DETAILS" });
  }

  let me: { mobile: string; name: string } | null;
  try {
    me = await loggedInMobile(String(body?.token ?? "").trim());
  } catch (e) {
    console.error("create-payment: login check failed", e);
    return json(500, { error: "SERVER" });
  }
  if (!me) return json(401, { error: "NOT_LOGGED_IN" });

  // 0) Courier chosen on the website: its price for this pincode and weight, straight from Shiprocket.
  let courier: any = null;
  const courierId = String(body?.courier_id ?? "").trim();
  if (courierId) {
    const pin = String(body?.pincode ?? "").replace(/\D/g, "");
    const items: any[] = Array.isArray(body?.items) ? body.items : [];
    const pairs = items.reduce((n, it) => n + (Number(it?.qty) || 0), 0);
    if (!/^\d{1,12}$/.test(courierId) || !/^[1-9]\d{5}$/.test(pin) || pairs < 1 || pairs > 200) {
      return json(400, { error: "COURIER_UNAVAILABLE" });
    }
    try {
      // order value (product prices from the database x qty) - the website sends the same cart total to ship-rates
      const ids = [...new Set(items.map((it) => String(it?.product_id ?? "")).filter(Boolean))];
      let value = 0;
      if (ids.length) {
        const rows = await dbSelect(`products?id=in.(${ids.map((x) => encodeURIComponent(`"${x.replace(/"/g, "")}"`)).join(",")})&select=id,price`);
        const price = new Map(rows.map((x: any) => [String(x.id), Number(x.price) || 0]));
        value = Math.round(items.reduce((n, it) => n + (price.get(String(it?.product_id ?? "")) ?? 0) * (Number(it?.qty) || 0), 0));
      }
      courier = await courierQuote(pin, pairs, courierId, value > 0 && value <= 500000 ? value : 0);
    } catch (e) {
      console.error("create-payment: courier price failed", (e as Error).message);
    }
    if (!courier) return json(400, { error: "COURIER_UNAVAILABLE" });
  }

  // 1) Check the details and stock, and keep the checkout (no order yet).
  const prep = await fetch(`${SUPABASE_URL}/rest/v1/rpc/site_prepare_checkout`, {
    method: "POST",
    headers: { ...DB, "Content-Type": "application/json" },
    body: JSON.stringify({
      p: {
        name: body?.name, mobile: me.mobile, country: body?.country, state: body?.state, city: body?.city,
        pincode: body?.pincode, address: body?.address, payment: body?.payment, items: body?.items,
        coupon: body?.coupon,
        // only when the customer chose a courier; the database uses this instead of the fixed shipping charge
        ...(courier ? { ship_fee: courier.rate, courier } : {}),
      },
    }),
  });
  const prepText = await prep.text();
  if (!prep.ok) {
    const known = KNOWN_ERRORS.find((k) => prepText.includes(k));
    console.error("create-payment: checkout refused", prep.status, prepText.slice(0, 300));
    return known ? json(400, { error: known }) : json(500, { error: "SERVER" });
  }
  const pr: any = JSON.parse(prepText);
  const amount = Math.round(Number(pr.total ?? 0) * 100); // paise
  if (!Number.isFinite(amount) || amount < 100) return json(400, { error: "INVALID_DETAILS" });

  // 2) Create the Razorpay payment for that amount (amount in paise).
  const rz = await fetch("https://api.razorpay.com/v1/orders", {
    method: "POST",
    headers: {
      Authorization: "Basic " + btoa(`${KEY_ID}:${KEY_SECRET}`),
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ amount, currency: "INR", receipt: String(pr.checkout_id), notes: { checkout_id: String(pr.checkout_id) } }),
  });
  const rzJson: any = await rz.json().catch(() => ({}));
  if (!rz.ok || !rzJson.id) {
    console.error("create-payment: razorpay error", rz.status, JSON.stringify(rzJson).slice(0, 300));
    return json(502, { error: "GATEWAY" });
  }

  // 3) Keep the Razorpay order id on the checkout, so the webhook can find it.
  const save = await fetch(`${SUPABASE_URL}/rest/v1/site_checkouts?id=eq.${encodeURIComponent(String(pr.checkout_id))}`, {
    method: "PATCH",
    headers: { ...DB, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify({ razorpay_order_id: rzJson.id }),
  });
  if (!save.ok) return json(500, { error: "SAVE_FAILED" });

  return json(200, {
    key_id: KEY_ID,
    razorpay_order_id: rzJson.id,
    amount,
    currency: "INR",
    name: "Shivtej Footwear",
    mobile: me.mobile,
    checkout_id: pr.checkout_id,
    customer_name: me.name,
    subtotal: pr.subtotal,
    discount: pr.discount,
    coupon: pr.coupon ?? "",
    shipping: pr.shipping,
    total: pr.total,
    courier: courier ? courier.name : "",
  });
});
