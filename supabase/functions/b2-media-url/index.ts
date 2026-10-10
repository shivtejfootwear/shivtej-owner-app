// Shivtej Footwear - signed read URLs for product media in the PRIVATE Backblaze B2 bucket.
// (Supabase Edge Function)
//
// Request:  GET /functions/v1/b2-media-url?product_id=<product id>&file=<products/... object name>
// Response: 200 { "url": "<signed URL>", "expires_in": 3600 }
//           404 when the file is not linked to that product (nothing is signed).
//
// Rules:
//   - Only object names under products/<product id>/ with an allowed media extension are signed.
//   - The file must already be linked to that product (images, videos or a colour's images)
//     in the products table. Otherwise nothing is signed.
//   - No owner code: the website shows product photos to customers. Each URL works for one file
//     and expires after one hour.
//
// Secrets (Supabase Dashboard > Edge Functions > Secrets, never in code):
//   B2_KEY_ID            - Backblaze Application Key ID (keyId)
//   B2_APPLICATION_KEY   - Backblaze Application Key secret
//   B2_BUCKET_ID         - Bucket ID of "shivtej-footwear-media"
// Provided automatically by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY (server only, never returned).

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const B2_KEY_ID = Deno.env.get("B2_KEY_ID") ?? "";
const B2_APPLICATION_KEY = Deno.env.get("B2_APPLICATION_KEY") ?? "";
const B2_BUCKET_ID = Deno.env.get("B2_BUCKET_ID") ?? "";
const B2_BUCKET_NAME = "shivtej-footwear-media"; // not a secret; used in the download path

const URL_SECONDS = 3600;
const PRODUCT_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
// products/<product id>/<uuid>.<ext>  (the upload proxy creates names in exactly this form)
const FILE_RE = /^products\/[A-Za-z0-9_-]{1,64}\/[a-f0-9-]{36}\.(jpg|png|webp|mp4|mov|webm)$/;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function json(status: number, body: unknown, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json", ...extra },
  });
}

// True only if the exact file name is stored somewhere in this product's row
// (images, videos, or colours[].images). The quotes make the match exact, not a substring.
async function productLinksFile(productId: string, file: string): Promise<boolean> {
  const url = `${SUPABASE_URL}/rest/v1/products?id=eq.${encodeURIComponent(productId)}&select=images,colours`;
  const r = await fetch(url, {
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
  });
  if (!r.ok) throw new Error(`products lookup failed (${r.status})`);
  const rows = await r.json();
  if (!Array.isArray(rows) || rows.length === 0) return false;
  return JSON.stringify(rows[0]).includes(`"${file}"`);
}

async function b2Authorize(): Promise<{ authorizationToken: string; apiUrl: string; downloadUrl: string }> {
  const basic = btoa(`${B2_KEY_ID}:${B2_APPLICATION_KEY}`);
  const r = await fetch("https://api.backblazeb2.com/b2api/v4/b2_authorize_account", {
    headers: { Authorization: `Basic ${basic}` },
  });
  if (!r.ok) throw new Error(`b2_authorize_account failed (${r.status})`);
  const data = await r.json();
  const storage = data?.apiInfo?.storageApi;
  if (!storage?.apiUrl || !storage?.downloadUrl) throw new Error("b2_authorize_account: storage API missing");
  // v4: restrictions live under storageApi.allowed
  const allowed = storage.allowed ?? {};
  const buckets: Array<{ id: string }> = Array.isArray(allowed.buckets) ? allowed.buckets : [];
  if (buckets.length !== 1 || buckets[0].id !== B2_BUCKET_ID) {
    throw new Error("Application Key is not restricted to exactly the media bucket");
  }
  const caps: string[] = Array.isArray(allowed.capabilities) ? allowed.capabilities : [];
  if (!caps.includes("readFiles") || !caps.includes("shareFiles")) {
    throw new Error("Application Key needs readFiles and shareFiles");
  }
  return {
    authorizationToken: data.authorizationToken,
    apiUrl: storage.apiUrl,
    downloadUrl: storage.downloadUrl,
  };
}

// Download token that works for exactly one file name (the prefix is the full name) for URL_SECONDS.
async function b2DownloadToken(auth: { authorizationToken: string; apiUrl: string }, file: string): Promise<string> {
  const r = await fetch(`${auth.apiUrl}/b2api/v4/b2_get_download_authorization`, {
    method: "POST",
    headers: { Authorization: auth.authorizationToken, "Content-Type": "application/json" },
    body: JSON.stringify({
      bucketId: B2_BUCKET_ID,
      fileNamePrefix: file,
      validDurationInSeconds: URL_SECONDS,
    }),
  });
  if (!r.ok) throw new Error(`b2_get_download_authorization failed (${r.status})`);
  const data = await r.json();
  if (!data?.authorizationToken) throw new Error("download authorization token missing");
  return data.authorizationToken as string;
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "GET") return json(405, { error: "METHOD_NOT_ALLOWED" });

  const params = new URL(req.url).searchParams;
  const productId = params.get("product_id") ?? "";
  const file = params.get("file") ?? "";

  if (!PRODUCT_ID_RE.test(productId)) return json(400, { error: "BAD_PRODUCT_ID" });
  if (!FILE_RE.test(file) || !file.startsWith(`products/${productId}/`)) {
    return json(400, { error: "BAD_FILE" });
  }

  if (!SUPABASE_URL || !SERVICE_KEY || !B2_KEY_ID || !B2_APPLICATION_KEY || !B2_BUCKET_ID) {
    return json(500, { error: "SERVER_NOT_CONFIGURED" });
  }

  try {
    // Verify BEFORE signing: the file must belong to this product.
    if (!(await productLinksFile(productId, file))) return json(404, { error: "NOT_FOUND" });

    const auth = await b2Authorize();
    const token = await b2DownloadToken(auth, file);

    const path = file.split("/").map(encodeURIComponent).join("/");
    const url = `${auth.downloadUrl}/file/${B2_BUCKET_NAME}/${path}?Authorization=${encodeURIComponent(token)}`;

    return json(200, { url, expires_in: URL_SECONDS }, { "Cache-Control": "private, max-age=1800" });
  } catch (e) {
    // Never return B2 or database internals to the browser; log them on the server only.
    console.error("b2-media-url error:", (e as Error).message);
    return json(502, { error: "MEDIA_URL_FAILED" });
  }
});
