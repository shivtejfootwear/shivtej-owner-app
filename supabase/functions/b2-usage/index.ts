// Shivtej Footwear - size of the private Backblaze B2 bucket (Supabase Edge Function, owner only).
//
// Request (from the owner app):
//   GET /functions/v1/b2-usage
//   Headers: x-owner-code, apikey
// Response: { "bytes": <total size of every file in the bucket> }
//
// Secrets (the same ones b2-upload already uses):
//   B2_KEY_ID, B2_APPLICATION_KEY, B2_BUCKET_ID
// The Backblaze key must be allowed to LIST files in that bucket (listFiles).
// The app never receives the key or a B2 token.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const B2_KEY_ID = Deno.env.get("B2_KEY_ID") ?? "";
const B2_APPLICATION_KEY = Deno.env.get("B2_APPLICATION_KEY") ?? "";
const B2_BUCKET_ID = Deno.env.get("B2_BUCKET_ID") ?? "";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// Same owner check as the other owner functions (owner_ping raises INVALID_OWNER_CODE for a wrong code).
async function ownerCodeOk(code: string): Promise<boolean> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/owner_ping`, {
    method: "POST",
    headers: { apikey: SUPABASE_ANON_KEY, "Content-Type": "application/json" },
    body: JSON.stringify({ p_code: code }),
  });
  if (r.ok) return true;
  const text = await r.text();
  if (text.includes("INVALID_OWNER_CODE")) return false;
  throw new Error(`owner_ping failed (${r.status})`);
}

async function b2Authorize(): Promise<{ authorizationToken: string; apiUrl: string }> {
  const basic = btoa(`${B2_KEY_ID}:${B2_APPLICATION_KEY}`);
  const r = await fetch("https://api.backblazeb2.com/b2api/v4/b2_authorize_account", {
    headers: { Authorization: `Basic ${basic}` },
  });
  if (!r.ok) throw new Error(`b2_authorize_account failed (${r.status})`);
  const data = await r.json();
  const storage = data?.apiInfo?.storageApi;
  if (!storage?.apiUrl) throw new Error("b2_authorize_account: storage API missing");
  const caps: string[] = Array.isArray(storage.allowed?.capabilities) ? storage.allowed.capabilities : [];
  if (!caps.includes("listFiles")) throw new Error("Application Key lacks the listFiles capability");
  return { authorizationToken: data.authorizationToken, apiUrl: storage.apiUrl };
}

// Adds up the size of every file in the bucket, one page of up to 10000 names at a time.
async function b2TotalBytes(auth: { authorizationToken: string; apiUrl: string }): Promise<number> {
  let total = 0;
  let startFileName: string | null = null;
  do {
    const body: Record<string, unknown> = { bucketId: B2_BUCKET_ID, maxFileCount: 10000 };
    if (startFileName) body.startFileName = startFileName;
    const r = await fetch(`${auth.apiUrl}/b2api/v4/b2_list_file_names`, {
      method: "POST",
      headers: { Authorization: auth.authorizationToken, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`b2_list_file_names failed (${r.status})`);
    const data = await r.json();
    for (const f of data.files ?? []) total += Number(f.contentLength ?? 0);
    startFileName = data.nextFileName ?? null;
  } while (startFileName);
  return total;
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "GET") return json(405, { error: "METHOD_NOT_ALLOWED" });

  const ownerCode = req.headers.get("x-owner-code") ?? "";
  if (!ownerCode) return json(401, { error: "INVALID_OWNER_CODE" });

  if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !B2_KEY_ID || !B2_APPLICATION_KEY || !B2_BUCKET_ID) {
    return json(500, { error: "SERVER_NOT_CONFIGURED" });
  }

  try {
    if (!(await ownerCodeOk(ownerCode))) return json(401, { error: "INVALID_OWNER_CODE" });
    const auth = await b2Authorize();
    return json(200, { bytes: await b2TotalBytes(auth) });
  } catch (e) {
    // Details stay in the server log; the app only gets a short code.
    console.error("b2-usage error:", (e as Error).message);
    return json(502, { error: "USAGE_FAILED" });
  }
});
