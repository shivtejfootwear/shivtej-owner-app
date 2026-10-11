// Shivtej Footwear - website photo upload for the owner app's Admin Portal (Supabase Edge Function).
//
// Request (from the owner app):
//   POST /functions/v1/site-media-upload
//   Headers: x-owner-code, x-slot ("hero" | "band" | "heroart"), Content-Type ("image/jpeg" | "image/png" | "image/webp")
//   Body:    the raw image bytes (the app already makes a JPEG of about 1280 px)
//
// What it does:
//   1. Checks the owner code (same owner_ping() check as every other owner action).
//   2. Checks slot, MIME type and the real file size (max 5 MB).
//   3. Uploads the file to the public "site-media" bucket with the service role key.
//      The key is never sent to the app.
//   4. Returns the public URL. The website admin page and the shop read the same bucket.
//
// Provided automatically by Supabase: SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const BUCKET = "site-media";
const MAX_BYTES = 5 * 1024 * 1024;
const TYPES: Record<string, string> = {
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
};
const SLOTS = ["hero", "band", "heroart"];

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// Uses the existing owner_ping(p_code text) SQL function.
// Returns true for a valid code, false for a wrong code, and throws for any other failure.
async function ownerCodeOk(code: string): Promise<boolean> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/owner_ping`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ p_code: code }),
  });
  if (r.ok) return true;
  const text = await r.text();
  if (text.includes("INVALID_OWNER_CODE")) return false;
  throw new Error(`owner_ping failed (${r.status})`);
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json(405, { message: "POST only" });

  if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SERVICE_KEY) {
    return json(500, { message: "SERVER_NOT_CONFIGURED" });
  }

  const code = req.headers.get("x-owner-code") ?? "";
  const slot = req.headers.get("x-slot") ?? "";
  const mime = (req.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();

  if (!SLOTS.includes(slot)) return json(400, { message: "BAD_SLOT" });
  const ext = TYPES[mime];
  if (!ext) return json(400, { message: "BAD_TYPE" });

  let ok: boolean;
  try {
    ok = await ownerCodeOk(code);
  } catch (_e) {
    return json(502, { message: "OWNER_CHECK_FAILED" });
  }
  if (!ok) return json(401, { message: "INVALID_OWNER_CODE" });

  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length === 0) return json(400, { message: "EMPTY" });
  if (bytes.length > MAX_BYTES) return json(413, { message: "TOO_BIG" });

  const name = `${slot}-${Date.now()}.${ext}`;
  const up = await fetch(`${SUPABASE_URL}/storage/v1/object/${BUCKET}/${name}`, {
    method: "POST",
    headers: {
      apikey: SERVICE_KEY,
      Authorization: `Bearer ${SERVICE_KEY}`,
      "Content-Type": mime,
      "x-upsert": "true",
    },
    body: bytes,
  });
  if (!up.ok) {
    const detail = (await up.text()).slice(0, 200);
    return json(502, { message: "UPLOAD_FAILED", detail });
  }

  return json(200, { url: `${SUPABASE_URL}/storage/v1/object/public/${BUCKET}/${name}` });
});
