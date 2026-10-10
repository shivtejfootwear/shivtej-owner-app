// Shivtej Footwear - Backblaze B2 upload proxy (Supabase Edge Function).
//
// Request (from the owner app):
//   POST /functions/v1/b2-upload
//   Headers: x-owner-code, x-kind ("image" | "video"), x-product-id, Content-Type (the file's MIME type)
//   Body:    the raw file bytes
//
// What it does:
//   1. Checks the owner code (same owner_ping() check as every other owner action).
//   2. Checks kind, product id, MIME type and the real file size (images 2 MB, videos 25 MB).
//   3. Uploads the file to the private B2 bucket itself, with a SHA-1 of the real bytes.
//   4. Returns only the object name. The app never receives a B2 token or the Application Key.
//
// Secrets (Supabase Dashboard > Edge Functions > Secrets, never in code):
//   B2_KEY_ID            - Backblaze Application Key ID (keyId)
//   B2_APPLICATION_KEY   - Backblaze Application Key secret
//   B2_BUCKET_ID         - Bucket ID of "shivtej-footwear-media"
// Provided automatically by Supabase: SUPABASE_URL, SUPABASE_ANON_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const B2_KEY_ID = Deno.env.get("B2_KEY_ID") ?? "";
const B2_APPLICATION_KEY = Deno.env.get("B2_APPLICATION_KEY") ?? "";
const B2_BUCKET_ID = Deno.env.get("B2_BUCKET_ID") ?? "";

// Same limits the existing Supabase buckets already use (product-images: 2 MB, product-videos: 25 MB).
const LIMITS: Record<string, { types: Record<string, string>; maxBytes: number }> = {
  image: {
    types: { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" },
    maxBytes: 2 * 1024 * 1024,
  },
  video: {
    types: { "video/mp4": "mp4", "video/quicktime": "mov", "video/webm": "webm" },
    maxBytes: 25 * 1024 * 1024,
  },
};

const PRODUCT_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

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
  // The SQL raises the message INVALID_OWNER_CODE for a wrong code. Check the body, not the HTTP status,
  // because PostgREST's status mapping for this error is not verified.
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
  // v4: restrictions live under storageApi.allowed (buckets: [{id, name}], capabilities: [...]).
  const allowed = storage.allowed ?? {};
  const buckets: Array<{ id: string }> = Array.isArray(allowed.buckets) ? allowed.buckets : [];
  // The key must be restricted to exactly our bucket.
  if (buckets.length !== 1 || buckets[0].id !== B2_BUCKET_ID) {
    throw new Error("Application Key is not restricted to exactly the media bucket");
  }
  // Uploading needs the writeFiles capability.
  if (!Array.isArray(allowed.capabilities) || !allowed.capabilities.includes("writeFiles")) {
    throw new Error("Application Key lacks the writeFiles capability");
  }
  return { authorizationToken: data.authorizationToken, apiUrl: storage.apiUrl };
}

async function b2GetUploadUrl(
  auth: { authorizationToken: string; apiUrl: string },
): Promise<{ uploadUrl: string; uploadToken: string }> {
  // v4 documents this as GET with bucketId in the query string.
  const url = `${auth.apiUrl}/b2api/v4/b2_get_upload_url?bucketId=${encodeURIComponent(B2_BUCKET_ID)}`;
  const r = await fetch(url, { headers: { Authorization: auth.authorizationToken } });
  if (!r.ok) throw new Error(`b2_get_upload_url failed (${r.status})`);
  const data = await r.json();
  return { uploadUrl: data.uploadUrl, uploadToken: data.authorizationToken };
}

// Reads the request body, but stops as soon as it is larger than max bytes.
// Returns null when the body is empty or too large. This protects memory even if Content-Length lies.
async function readCappedBody(req: Request, max: number): Promise<Uint8Array | null> {
  if (!req.body) return null;
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  if (total === 0) return null;
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out;
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });

  // Metadata comes from headers, so the body can be the raw file bytes.
  const ownerCode = req.headers.get("x-owner-code") ?? "";
  const kind = req.headers.get("x-kind") ?? "";
  const productId = req.headers.get("x-product-id") ?? "";
  const contentType = (req.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();

  if (!ownerCode) return json(401, { error: "INVALID_OWNER_CODE" });

  if (!Object.hasOwn(LIMITS, kind)) return json(400, { error: "BAD_KIND" });
  const rule = LIMITS[kind];
  if (!PRODUCT_ID_RE.test(productId)) return json(400, { error: "BAD_PRODUCT_ID" });
  if (!Object.hasOwn(rule.types, contentType)) return json(400, { error: "BAD_CONTENT_TYPE" });
  const ext = rule.types[contentType];

  // Reject a declared size that is too big before reading anything.
  const declared = Number(req.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > rule.maxBytes) {
    return json(400, { error: "FILE_TOO_LARGE_OR_EMPTY", max_bytes: rule.maxBytes });
  }

  if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !B2_KEY_ID || !B2_APPLICATION_KEY || !B2_BUCKET_ID) {
    return json(500, { error: "SERVER_NOT_CONFIGURED" });
  }

  try {
    // Check the owner BEFORE reading the body, so nobody without the code can make the server buffer a file.
    if (!(await ownerCodeOk(ownerCode))) return json(401, { error: "INVALID_OWNER_CODE" });

    const bytes = await readCappedBody(req, rule.maxBytes);
    if (!bytes) return json(400, { error: "FILE_TOO_LARGE_OR_EMPTY", max_bytes: rule.maxBytes });

    const auth = await b2Authorize();
    const up = await b2GetUploadUrl(auth);

    const digest = new Uint8Array(await crypto.subtle.digest("SHA-1", bytes));
    const sha1 = [...digest].map((b) => b.toString(16).padStart(2, "0")).join("");

    // Folder layout: products/<product id>/<random>.<ext>
    const fileName = `products/${productId}/${crypto.randomUUID()}.${ext}`;

    const r = await fetch(up.uploadUrl, {
      method: "POST",
      headers: {
        Authorization: up.uploadToken,
        "X-Bz-File-Name": fileName.split("/").map(encodeURIComponent).join("/"),
        "Content-Type": contentType,
        "X-Bz-Content-Sha1": sha1,
      },
      body: bytes,
    });
    if (!r.ok) throw new Error(`b2_upload_file failed (${r.status})`);
    const data = await r.json();

    // The app gets only the object name and file id. No B2 token.
    return json(200, { file_name: fileName, file_id: data.fileId });
  } catch (e) {
    // Do not return B2 or Supabase internals to the app; log them on the server only.
    console.error("b2-upload error:", (e as Error).message);
    return json(502, { error: "UPLOAD_AUTH_FAILED" });
  }
});
