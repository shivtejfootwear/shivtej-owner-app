// Shivtej Footwear - admin "Create shipment" with Shiprocket (Supabase Edge Function).
//
// POST /functions/v1/shiprocket-ship
//   Authorization: Bearer <logged-in admin's login token>
//   { "action": "couriers", "order_id": "SW..." }
//       -> 200 { ok: true, couriers: [{ courier_id, name, rate, etd }] }   (nothing is booked)
//   { "order_id": "SW...", "courier_id": "123" }   (courier_id optional; empty = Shiprocket's recommended)
//       -> 200 { ok: true, awb, courier, shipment_id, pickup, already? }
//   Errors: 400 BAD_JSON | BAD_ORDER | NOT_READY | PINCODE_MISSING | ADDRESS_INCOMPLETE | PAYMENT_NOT_CONFIRMED | NOT_INDIA_SHIPPING
//           401 NOT_LOGGED_IN | 403 NOT_ADMIN | 404 ORDER_NOT_FOUND
//           502 SHIPROCKET_FAILED (details only in the function log, never the key)
//
// Steps: check admin -> load order -> create Shiprocket order (once) -> assign AWB -> request pickup
//        -> save AWB, courier and Shiprocket ids on the order. Safe to retry: a created shipment is reused.
//
// Secrets (Supabase Dashboard > Edge Functions > Secrets):
//   SHIPROCKET_EMAIL     - API user email (created in Shiprocket > Settings > API)
//   SHIPROCKET_PASSWORD  - API user password
//   SHIPROCKET_PICKUP    - name of the pickup address in Shiprocket (optional, default "Primary")
// Provided automatically by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const SR_EMAIL = Deno.env.get("SHIPROCKET_EMAIL") ?? "";
const SR_PASSWORD = Deno.env.get("SHIPROCKET_PASSWORD") ?? "";
const SR_PICKUP = Deno.env.get("SHIPROCKET_PICKUP") || "Primary";
const SR_BASE = "https://apiv2.shiprocket.in/v1/external";
const OTP_PEPPER = Deno.env.get("OTP_PEPPER") ?? "";

// Until products have their own weight and size, every pair is counted with these values.
const DEFAULT_WEIGHT_G = 500;
const BOX = { length: 11, breadth: 6, height: 6 }; // cm
const READY_STATUSES = ["Confirmed", "Packed"];

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

class HttpError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

// ---------- database (Supabase REST, service role) ----------

const DB_HEADERS = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };

async function dbSelect(query: string): Promise<any[]> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${query}`, { headers: DB_HEADERS });
  if (!r.ok) throw new Error(`select failed (${r.status})`);
  return r.json();
}

async function dbPatch(query: string, row: Record<string, unknown>): Promise<void> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${query}`, {
    method: "PATCH",
    headers: { ...DB_HEADERS, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify(row),
  });
  if (!r.ok) throw new Error(`patch failed (${r.status})`);
}

// Who is calling? The login token is checked by Supabase Auth, then the user must be an active admin.
async function callerId(token: string): Promise<string | null> {
  const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${token}` },
  });
  if (!r.ok) return null;
  const user = await r.json();
  return user?.id ?? null;
}

async function isActiveAdmin(uid: string): Promise<boolean> {
  const rows = await dbSelect(`admin_users?id=eq.${uid}&active=eq.true&select=id`);
  return rows.length > 0;
}

async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Admin logged in with WhatsApp OTP (admin-otp function). The token is kept only as a hash.
async function isOtpAdmin(token: string): Promise<boolean> {
  if (!/^[a-f0-9]{64}$/.test(token)) return false;
  const sessions = await dbSelect(
    `admin_sessions?token_hash=eq.${await sha256Hex(`admin:${token}:${OTP_PEPPER}`)}&select=admin_mobile,expires_at`,
  );
  const session = sessions[0];
  if (!session || new Date(session.expires_at).getTime() < Date.now()) return false;
  const rows = await dbSelect(`admin_users?mobile=eq.${session.admin_mobile}&active=eq.true&select=id`);
  return rows.length > 0;
}

// ---------- Shiprocket ----------

async function srLogin(): Promise<string> {
  const r = await fetch(`${SR_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: SR_EMAIL, password: SR_PASSWORD }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j?.token) {
    console.error("shiprocket login failed", r.status);
    throw new HttpError(502, "SHIPROCKET_FAILED");
  }
  return j.token as string;
}

async function sr(path: string, token: string, body: unknown): Promise<any> {
  const r = await fetch(`${SR_BASE}${path}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await r.text();
  if (!r.ok) {
    // Log only the path, status and the start of Shiprocket's reply (no token, no password).
    console.error("shiprocket call failed", path, r.status, text.slice(0, 300));
    throw new HttpError(502, "SHIPROCKET_FAILED");
  }
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
}

// Shiprocket wants the local date and time (India), e.g. "2026-10-11 10:30".
function istNow(): string {
  return new Date(Date.now() + 5.5 * 3600_000).toISOString().slice(0, 16).replace("T", " ");
}

// Pickup pincode of the shop (Akluj). Used to ask Shiprocket which couriers can deliver.
const SHOP_PIN = "413101";

async function srGet(path: string, token: string): Promise<any> {
  const r = await fetch(`${SR_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  const text = await r.text();
  if (!r.ok) {
    console.error("shiprocket get failed", path.split("?")[0], r.status, text.slice(0, 300));
    throw new HttpError(502, "SHIPROCKET_FAILED");
  }
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
}

// Couriers that can deliver this order, with the freight charge, so the admin can choose.
async function courierOptions(o: any, token: string): Promise<Record<string, unknown>[]> {
  const c = o.customer ?? {};
  if (String(c.country ?? "India") !== "India") throw new HttpError(400, "NOT_INDIA_SHIPPING");
  const pin = String(c.pincode ?? "").replace(/\D/g, "");
  if (!/^\d{6}$/.test(pin)) throw new HttpError(400, "PINCODE_MISSING");
  const items: any[] = Array.isArray(o.items) ? o.items : [];
  const pairs = items.reduce((n, it) => n + Number(it.qty ?? it.q ?? 1), 0);
  const weightKg = Math.max(0.5, (pairs * DEFAULT_WEIGHT_G) / 1000);
  const q = `pickup_postcode=${SHOP_PIN}&delivery_postcode=${pin}&cod=0&weight=${weightKg}`;
  const res = await srGet(`/courier/serviceability/?${q}`, token);
  const list: any[] = res?.data?.available_courier_companies ?? [];
  return list
    .map((c: any) => ({
      courier_id: String(c.courier_company_id ?? c.id ?? ""),
      name: String(c.courier_name ?? c.name ?? ""),
      rate: Number(c.rate ?? c.freight_charge ?? 0),
      etd: String(c.etd ?? c.estimated_delivery_days ?? ""),
    }))
    .filter((c) => c.courier_id && c.name)
    .map((x: any) => (String(o.customer?.courier_choice?.id ?? "") === x.courier_id ? { ...x, name: `★ ${x.name} (customer's choice)`, chosen: true } : x))
    .sort((a: any, b: any) => (b.chosen ? 1 : 0) - (a.chosen ? 1 : 0) || a.rate - b.rate);
}

function buildPayload(o: any): Record<string, unknown> {
  const c = o.customer ?? {};
  if (String(c.country ?? "India") !== "India") throw new HttpError(400, "NOT_INDIA_SHIPPING");
  const pin = String(c.pincode ?? "").replace(/\D/g, "");
  if (!/^\d{6}$/.test(pin)) throw new HttpError(400, "PINCODE_MISSING");
  if (!String(c.city ?? "").trim() || !String(c.state ?? "").trim() || !String(c.address ?? "").trim()) {
    throw new HttpError(400, "ADDRESS_INCOMPLETE");
  }

  const paid = String(o.payment_status ?? "").toLowerCase().includes("paid");
  if (!paid) throw new HttpError(400, "PAYMENT_NOT_CONFIRMED");

  const items: any[] = Array.isArray(o.items) ? o.items : [];
  const pairs = items.reduce((n, it) => n + Number(it.qty ?? it.q ?? 1), 0);
  const weightKg = Math.max(0.5, (pairs * DEFAULT_WEIGHT_G) / 1000);

  return {
    order_id: o.id,
    order_date: istNow(),
    pickup_location: SR_PICKUP,
    billing_customer_name: String(c.name ?? "Customer"),
    billing_last_name: "",
    billing_address: String(c.address),
    billing_city: String(c.city),
    billing_pincode: pin,
    billing_state: String(c.state),
    billing_country: "India",
    billing_phone: String(c.mobile ?? ""),
    shipping_is_billing: true,
    payment_method: "Prepaid",
    sub_total: Number(o.subtotal ?? 0),
    length: BOX.length,
    breadth: BOX.breadth,
    height: BOX.height,
    weight: weightKg,
    order_items: items.map((it) => ({
      name: String(it.name ?? it.article ?? "Footwear"),
      sku: `${it.productId ?? it.id ?? "P"}-${it.colour ?? ""}-${it.size ?? ""}`,
      units: Number(it.qty ?? it.q ?? 1),
      selling_price: Number(it.price ?? 0),
    })),
  };
}

// ---------- action ----------

async function createShipment(orderId: string, bearer: string, action: string, courierId: string): Promise<Record<string, unknown>> {
  const uid = await callerId(bearer);
  if (uid) {
    if (!(await isActiveAdmin(uid))) throw new HttpError(403, "NOT_ADMIN");
  } else if (!(await isOtpAdmin(bearer))) {
    throw new HttpError(401, "NOT_LOGGED_IN");
  }

  const rows = await dbSelect(`orders?id=eq.${encodeURIComponent(orderId)}&select=*`);
  const o = rows[0];
  if (!o) throw new HttpError(404, "ORDER_NOT_FOUND");

  // Courier list for the admin to choose from (nothing is booked).
  if (action === "couriers") {
    if (!READY_STATUSES.includes(o.order_status)) throw new HttpError(400, "NOT_READY");
    const token = await srLogin();
    return { ok: true, couriers: await courierOptions(o, token) };
  }

  // Already shipped: return what we have (safe to press twice).
  if (o.tracking_no && o.shiprocket_order_id) {
    return { ok: true, already: true, awb: o.tracking_no, courier: o.courier ?? "", shipment_id: o.shipment_id ?? "" };
  }
  if (!READY_STATUSES.includes(o.order_status)) throw new HttpError(400, "NOT_READY");

  const payload = buildPayload(o);
  const token = await srLogin();

  // 1) Create the Shiprocket order once and save its ids before anything else.
  let shipmentId = String(o.shipment_id ?? "");
  if (!shipmentId) {
    const created = await sr("/orders/create/adhoc", token, payload);
    shipmentId = String(created?.shipment_id ?? "");
    if (!shipmentId) throw new HttpError(502, "SHIPROCKET_FAILED");
    await dbPatch(`orders?id=eq.${encodeURIComponent(orderId)}`, {
      shiprocket_order_id: String(created?.order_id ?? ""),
      shipment_id: shipmentId,
      updated_at: new Date().toISOString(),
    });
  }

  // 2) Assign courier and AWB. If the admin chose a courier, use it; otherwise Shiprocket's recommended one.
  const assignBody: Record<string, unknown> = { shipment_id: Number(shipmentId) };
  if (courierId) assignBody.courier_id = Number(courierId);
  const assigned = await sr("/courier/assign/awb", token, assignBody);
  const data = assigned?.response?.data ?? {};
  const awb = String(data.awb_code ?? "");
  const courier = String(data.courier_name ?? "");
  if (!awb) throw new HttpError(502, "SHIPROCKET_FAILED");

  // 3) Ask for pickup. If it fails, the AWB is still saved and the order stays Packed.
  let pickup = true;
  try {
    await sr("/courier/generate/pickup", token, { shipment_id: [Number(shipmentId)] });
  } catch {
    pickup = false;
  }

  await dbPatch(`orders?id=eq.${encodeURIComponent(orderId)}`, {
    tracking_no: awb,
    courier,
    order_status: pickup ? "Shipped" : o.order_status,
    updated_at: new Date().toISOString(),
  });

  return { ok: true, awb, courier, shipment_id: shipmentId, pickup };
}

// ---------- entry point ----------

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json(405, { error: "METHOD" });

  if (!SUPABASE_URL || !SERVICE_KEY || !SR_EMAIL || !SR_PASSWORD) {
    console.error("shiprocket-ship: secrets missing");
    return json(500, { error: "NOT_CONFIGURED" });
  }

  const bearer = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!bearer) return json(401, { error: "NOT_LOGGED_IN" });

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "BAD_JSON" });
  }
  const orderId = String(body?.order_id ?? "").trim();
  if (!/^SW[0-9A-Z]{6,40}$/.test(orderId)) return json(400, { error: "BAD_ORDER" });
  const action = body?.action === "couriers" ? "couriers" : "ship";
  const courierId = String(body?.courier_id ?? "").replace(/\D/g, "");

  try {
    return json(200, await createShipment(orderId, bearer, action, courierId));
  } catch (e) {
    if (e instanceof HttpError) return json(e.status, { error: e.code });
    console.error("shiprocket-ship error", e);
    return json(500, { error: "SERVER" });
  }
});