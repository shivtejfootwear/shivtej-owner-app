// Shivtej Footwear - courier choices with price and delivery days for one pincode (Supabase Edge Function).
//
// POST /functions/v1/ship-rates   (Verify JWT: OFF - the website calls it before login is needed)
//   { "pincode": "413101", "pairs": 2, "value": 1398 }   (value = cart total in rupees, optional)
//   -> 200 { ok: true, weight_kg: 1, couriers: [{ courier_id, name, rate, days, etd, tag }] }
//          tag: "cheapest" | "fastest" | ""   (rate in whole rupees, rounded up)
//   -> 400 BAD_PINCODE | BAD_PAIRS     502 SHIPROCKET_FAILED
//
// Weight: 500 g per pair (2 pairs = 1 kg). Pickup from the shop pincode 413101. Prepaid (no COD).
// The order value is sent to Shiprocket as declared_value (the panel's Rate Calculator uses it too), and the price
// shown is the full charge: the larger of "rate" and freight + coverage + other charges, so the customer never pays
// less than Shiprocket will charge the shop.
// create-payment asks Shiprocket again for the chosen courier, so the price a customer pays is always the server's.
// Secrets (same as shiprocket-ship): SHIPROCKET_EMAIL, SHIPROCKET_PASSWORD.

const SR_EMAIL = Deno.env.get("SHIPROCKET_EMAIL") ?? "";
const SR_PASSWORD = Deno.env.get("SHIPROCKET_PASSWORD") ?? "";
const SR_BASE = "https://apiv2.shiprocket.in/v1/external";
const SHOP_PIN = "413101";
const PAIR_WEIGHT_KG = 0.5;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};
function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

// The Shiprocket login lasts days; keep it while this function stays warm.
let token = "", tokenAt = 0;
async function srToken(): Promise<string> {
  if (token && Date.now() - tokenAt < 6 * 3600_000) return token;
  const r = await fetch(`${SR_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: SR_EMAIL, password: SR_PASSWORD }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j?.token) throw new Error(`shiprocket login failed (${r.status})`);
  token = j.token; tokenAt = Date.now();
  return token;
}

// Full Shiprocket charge for one courier (all fields Shiprocket may add on top of freight).
function chargeOf(c: any): number {
  const n = (v: any) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const parts = n(c.freight_charge) + n(c.coverage_charges) + n(c.other_charges) + n(c.cod_charges);
  return Math.ceil(Math.max(n(c.rate), n(c.freight_charge), parts));
}
let logged = 0;

// Same pincode, weight and value within 10 minutes = same answer (saves Shiprocket calls).
const cache = new Map<string, { at: number; list: any[] }>();

async function courierRates(pin: string, pairs: number, value: number) {
  const weight = Math.max(PAIR_WEIGHT_KG, pairs * PAIR_WEIGHT_KG);
  const key = `${pin}|${weight}|${value}`;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < 600_000) return { weight, list: hit.list };
  const q = `pickup_postcode=${SHOP_PIN}&delivery_postcode=${pin}&cod=0&weight=${weight}` + (value > 0 ? `&declared_value=${value}` : "");
  let r = await fetch(`${SR_BASE}/courier/serviceability/?${q}`, { headers: { Authorization: `Bearer ${await srToken()}` } });
  if (r.status === 401) { token = ""; r = await fetch(`${SR_BASE}/courier/serviceability/?${q}`, { headers: { Authorization: `Bearer ${await srToken()}` } }); }
  const j: any = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`serviceability failed (${r.status}) ${JSON.stringify(j).slice(0, 160)}`);
  const raw: any[] = j?.data?.available_courier_companies ?? [];
  // A few lines in Logs to compare with the Shiprocket panel (prices only, no customer data).
  if (logged < 20 && raw[0]) {
    logged++;
    console.log("ship-rates price check", pin, weight, value, JSON.stringify(raw.slice(0, 3).map((c: any) => ({
      name: c.courier_name, rate: c.rate, freight_charge: c.freight_charge, coverage_charges: c.coverage_charges,
      other_charges: c.other_charges, cod_charges: c.cod_charges, shown: chargeOf(c),
    }))));
  }
  const list = raw
    .map((c: any) => ({
      courier_id: String(c.courier_company_id ?? c.id ?? ""),
      name: String(c.courier_name ?? c.name ?? "").trim(),
      rate: chargeOf(c),
      days: Number(c.estimated_delivery_days ?? 0) || null,
      etd: String(c.etd ?? ""),
    }))
    .filter((c) => c.courier_id && c.name && c.rate > 0)
    .sort((a, b) => a.rate - b.rate || (a.days ?? 99) - (b.days ?? 99));
  cache.set(key, { at: Date.now(), list });
  return { weight, list };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json(405, { error: "METHOD" });
  if (!SR_EMAIL || !SR_PASSWORD) return json(500, { error: "NOT_CONFIGURED" });
  let body: any;
  try { body = await req.json(); } catch { return json(400, { error: "BAD_PINCODE" }); }
  const pin = String(body?.pincode ?? "").replace(/\D/g, "");
  const pairs = Number(body?.pairs);
  if (!/^[1-9]\d{5}$/.test(pin)) return json(400, { error: "BAD_PINCODE" });
  if (!Number.isInteger(pairs) || pairs < 1 || pairs > 20) return json(400, { error: "BAD_PAIRS" });
  const v = Math.round(Number(body?.value) || 0);
  const value = v > 0 && v <= 500000 ? v : 0;
  try {
    const { weight, list } = await courierRates(pin, pairs, value);
    const fastest = list.reduce((best: any, c: any) => (c.days && (!best || c.days < best.days) ? c : best), null);
    const couriers = list.map((c: any, i: number) => ({
      ...c,
      tag: i === 0 ? "cheapest" : fastest && c.courier_id === fastest.courier_id ? "fastest" : "",
    }));
    return json(200, { ok: true, weight_kg: weight, couriers });
  } catch (e) {
    console.error("ship-rates:", (e as Error).message);
    return json(502, { error: "SHIPROCKET_FAILED" });
  }
});
