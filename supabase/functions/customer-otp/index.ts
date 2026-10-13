// Shivtej Footwear - customer login with a WhatsApp OTP (Meta WhatsApp Cloud API).
// (Supabase Edge Function)
//
// POST /functions/v1/customer-otp
//   { "action": "send",   "mobile": "9876543210" }                  -> 200 { ok: true, expires_in: 300 }
//   { "action": "verify", "mobile": "9876543210", "code": "123456" } -> 200 { ok: true, token, customer: { id, mobile, name } }
//   Errors: 400 BAD_MOBILE | BAD_CODE | BAD_ACTION | WRONG_CODE | EXPIRED
//           429 TOO_MANY (sends) | TOO_MANY_TRIES (wrong codes)
//           502 SEND_FAILED (Meta rejected the message; details only in the function log)
//
// Secrets (Supabase Dashboard > Edge Functions > Secrets, never in code):
//   WHATSAPP_PHONE_NUMBER_ID - Meta "Phone number ID" of the shop's WhatsApp number (+91 92092 54920)
//   WHATSAPP_TOKEN           - Meta permanent (system user) access token
//   OTP_PEPPER               - long random text; used to hash codes and session tokens
//   WA_OTP_TEMPLATE          - optional, default "shivtej_otp" (approved AUTHENTICATION template)
//   WA_OTP_LANG              - optional, default "en"
//   WA_API_VERSION           - optional, default "v21.0"
// The older names WA_PHONE_NUMBER_ID and WA_ACCESS_TOKEN are still read if the new ones are not set.
// Provided automatically by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY (server only, never returned).
//
// Rules: at most 3 codes per number in 10 minutes; a code lasts 5 minutes and allows 5 tries;
// the login token lasts 30 days. Codes and tokens are stored only as hashes.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const WA_PHONE_NUMBER_ID = Deno.env.get("WHATSAPP_PHONE_NUMBER_ID") ?? Deno.env.get("WA_PHONE_NUMBER_ID") ?? "";
const WA_ACCESS_TOKEN = Deno.env.get("WHATSAPP_TOKEN") ?? Deno.env.get("WA_ACCESS_TOKEN") ?? "";
const WA_OTP_TEMPLATE = Deno.env.get("WA_OTP_TEMPLATE") ?? "shivtej_otp";
const WA_OTP_LANG = Deno.env.get("WA_OTP_LANG") ?? "en";
const OTP_PEPPER = Deno.env.get("OTP_PEPPER") ?? "";
const WA_API_VERSION = Deno.env.get("WA_API_VERSION") ?? "v21.0";

const CODE_SECONDS = 300;
const SESSION_DAYS = 30;
const SENDS_PER_WINDOW = 3;
const WINDOW_MINUTES = 10;
const MAX_TRIES = 5;

const MOBILE_RE = /^[6-9]\d{9}$/;
const CODE_RE = /^\d{6}$/;

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

async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomCode(): string {
  const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1000000;
  return String(n).padStart(6, "0");
}

function randomToken(): string {
  return [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// ---------- database (Supabase REST, service role) ----------

const DB_HEADERS = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };

async function dbSelect(query: string): Promise<any[]> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${query}`, { headers: DB_HEADERS });
  if (!r.ok) throw new Error(`select failed (${r.status})`);
  return r.json();
}

async function dbInsert(table: string, row: Record<string, unknown>): Promise<void> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
    method: "POST",
    headers: { ...DB_HEADERS, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify(row),
  });
  if (!r.ok) throw new Error(`insert ${table} failed (${r.status})`);
}

async function dbPatch(query: string, row: Record<string, unknown>): Promise<void> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${query}`, {
    method: "PATCH",
    headers: { ...DB_HEADERS, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify(row),
  });
  if (!r.ok) throw new Error(`patch failed (${r.status})`);
}

async function dbDelete(query: string): Promise<void> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${query}`, { method: "DELETE", headers: DB_HEADERS });
  if (!r.ok) throw new Error(`delete failed (${r.status})`);
}

// ---------- WhatsApp (Meta Cloud API) ----------

async function sendWhatsAppOtp(mobile: string, code: string): Promise<void> {
  const r = await fetch(`https://graph.facebook.com/${WA_API_VERSION}/${WA_PHONE_NUMBER_ID}/messages`, {
    method: "POST",
    headers: { Authorization: `Bearer ${WA_ACCESS_TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      to: "91" + mobile,
      type: "template",
      template: {
        name: WA_OTP_TEMPLATE,
        language: { code: WA_OTP_LANG },
        components: [
          { type: "body", parameters: [{ type: "text", text: code }] },
          // "Copy code" button of the authentication template
          { type: "button", sub_type: "url", index: "0", parameters: [{ type: "text", text: code }] },
        ],
      },
    }),
  });
  if (!r.ok) {
    // Log only the status and the start of Meta's message (never the token or the code).
    const detail = (await r.text()).slice(0, 300);
    console.error("whatsapp send failed", r.status, detail);
    throw new Error("WA_SEND");
  }
}

// ---------- actions ----------

async function handleSend(mobile: string): Promise<Response> {
  const since = new Date(Date.now() - WINDOW_MINUTES * 60_000).toISOString();
  const recent = await dbSelect(
    `otp_codes?mobile=eq.${mobile}&created_at=gte.${encodeURIComponent(since)}&select=id`,
  );
  if (recent.length >= SENDS_PER_WINDOW) return json(429, { error: "TOO_MANY" });

  const code = randomCode();
  await dbInsert("otp_codes", {
    mobile,
    code_hash: await sha256Hex(`${mobile}:${code}:${OTP_PEPPER}`),
    expires_at: new Date(Date.now() + CODE_SECONDS * 1000).toISOString(),
  });

  try {
    await sendWhatsAppOtp(mobile, code);
  } catch {
    return json(502, { error: "SEND_FAILED" });
  }
  return json(200, { ok: true, expires_in: CODE_SECONDS });
}

async function handleVerify(mobile: string, code: string): Promise<Response> {
  const rows = await dbSelect(
    `otp_codes?mobile=eq.${mobile}&order=created_at.desc&limit=1&select=id,code_hash,expires_at,attempts`,
  );
  const row = rows[0];
  if (!row || new Date(row.expires_at).getTime() < Date.now()) return json(400, { error: "EXPIRED" });
  if (row.attempts >= MAX_TRIES) return json(429, { error: "TOO_MANY_TRIES" });

  const correct = (await sha256Hex(`${mobile}:${code}:${OTP_PEPPER}`)) === row.code_hash;
  await dbPatch(`otp_codes?id=eq.${row.id}`, { attempts: row.attempts + 1 });
  if (!correct) return json(400, { error: "WRONG_CODE" });

  // Code used: remove all codes of this number so it cannot be reused.
  await dbDelete(`otp_codes?mobile=eq.${mobile}`);

  // Find or create the customer.
  const customerQuery = `customers?mobile=eq.${mobile}&select=id,mobile,name`;
  let customer = (await dbSelect(customerQuery))[0];
  if (!customer) {
    const nowIso = new Date().toISOString();
    await dbInsert("customers", { mobile, created_at: nowIso, updated_at: nowIso });
    customer = (await dbSelect(customerQuery))[0];
  }

  // Login token: the browser keeps the token, the database keeps only its hash.
  const token = randomToken();
  await dbInsert("customer_sessions", {
    token_hash: await sha256Hex(`session:${token}:${OTP_PEPPER}`),
    customer_id: customer.id,
    expires_at: new Date(Date.now() + SESSION_DAYS * 86_400_000).toISOString(),
  });

  return json(200, { ok: true, token, customer });
}

// ---------- entry point ----------

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json(405, { error: "METHOD" });

  if (!SUPABASE_URL || !SERVICE_KEY || !WA_PHONE_NUMBER_ID || !WA_ACCESS_TOKEN || !WA_OTP_TEMPLATE || !OTP_PEPPER) {
    console.error("customer-otp: secrets missing");
    return json(500, { error: "NOT_CONFIGURED" });
  }

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "BAD_JSON" });
  }

  const mobile = String(body?.mobile ?? "").trim();
  if (!MOBILE_RE.test(mobile)) return json(400, { error: "BAD_MOBILE" });

  try {
    if (body.action === "send") return await handleSend(mobile);
    if (body.action === "verify") {
      const code = String(body?.code ?? "").trim();
      if (!CODE_RE.test(code)) return json(400, { error: "BAD_CODE" });
      return await handleVerify(mobile, code);
    }
    return json(400, { error: "BAD_ACTION" });
  } catch (e) {
    console.error("customer-otp error", e);
    return json(500, { error: "SERVER" });
  }
});
