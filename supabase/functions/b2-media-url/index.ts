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
//   - Daily cap fix (2026-10-28): the link is now signed HERE with the S3-compatible signature
//     (no Backblaze call per photo). Backblaze is asked only once per server start
//     (b2_authorize_account, to learn the S3 address and check the key). Before this, every photo
//     link used 2 "Class C" calls and the free daily cap (2,500) ran out.
//     If the S3 address is not available, the old way (b2_get_download_authorization) is used.
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
// Optional (not secret): the bucket's S3 Endpoint from the Backblaze Buckets page,
// e.g. s3.us-east-005.backblazeb2.com . When it is set, NO Backblaze API call is made at all.
const B2_S3_ENDPOINT = (Deno.env.get("B2_S3_ENDPOINT") ?? "").trim();

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

type B2Auth = { authorizationToken: string; apiUrl: string; downloadUrl: string; s3ApiUrl: string };

async function b2Authorize(): Promise<B2Auth> {
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
    s3ApiUrl: typeof storage.s3ApiUrl === "string" ? storage.s3ApiUrl : "",
  };
}

// The authorization is kept in memory while this server copy runs (B2 tokens last 24 hours;
// it is renewed after 20 hours). So Backblaze is not asked again for every photo.
let cachedAuth: { auth: B2Auth; until: number } | null = null;
async function getAuth(): Promise<B2Auth> {
  const now = Date.now();
  if (cachedAuth && now < cachedAuth.until) return cachedAuth.auth;
  const auth = await b2Authorize();
  cachedAuth = { auth, until: now + 20 * 3600 * 1000 };
  return auth;
}

// ---- S3-compatible signed GET link (AWS Signature V4, query string). Made here, no call to Backblaze. ----
const enc = new TextEncoder();
const hex = (buf: ArrayBuffer) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
async function sha256Hex(s: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(s)));
}
async function hmac(key: string | Uint8Array, s: string): Promise<Uint8Array> {
  const k = await crypto.subtle.importKey(
    "raw", typeof key === "string" ? enc.encode(key) : key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, enc.encode(s)));
}
const rfc3986 = (s: string) =>
  encodeURIComponent(s).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());

// s3ApiUrl looks like https://s3.us-west-004.backblazeb2.com ; the region is the part after "s3.".
async function s3SignedUrl(s3ApiUrl: string, file: string): Promise<string | null> {
  const host = s3ApiUrl.replace(/^https?:\/\//, "").replace(/\/.*$/, "");
  const m = host.match(/^s3\.([a-z0-9-]+)\.backblazeb2\.com$/);
  if (!m) return null;
  const region = m[1];
  // The sign time is rounded down to the hour and the link lasts 2 hours, so for one hour every
  // visitor gets the SAME link for a photo. The browser then keeps the photo in its cache and shows it
  // at once on the next visit (faster, and fewer downloads from Backblaze). At least 1 hour is always left.
  const hourStart = new Date(Math.floor(Date.now() / 3600000) * 3600000);
  const amzDate = hourStart.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const day = amzDate.slice(0, 8);
  const scope = `${day}/${region}/s3/aws4_request`;
  const q: Record<string, string> = {
    "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
    "X-Amz-Credential": `${B2_KEY_ID}/${scope}`,
    "X-Amz-Date": amzDate,
    "X-Amz-Expires": String(URL_SECONDS * 2),
    "X-Amz-SignedHeaders": "host",
  };
  const query = Object.keys(q).sort().map((k) => `${rfc3986(k)}=${rfc3986(q[k])}`).join("&");
  const path = "/" + [B2_BUCKET_NAME, ...file.split("/")].map(rfc3986).join("/");
  const canon = `GET\n${path}\n${query}\nhost:${host}\n\nhost\nUNSIGNED-PAYLOAD`;
  const toSign = `AWS4-HMAC-SHA256\n${amzDate}\n${scope}\n${await sha256Hex(canon)}`;
  let k = await hmac("AWS4" + B2_APPLICATION_KEY, day);
  k = await hmac(k, region);
  k = await hmac(k, "s3");
  k = await hmac(k, "aws4_request");
  const sig = hex((await hmac(k, toSign)).buffer as ArrayBuffer);
  return `https://${host}${path}?${query}&X-Amz-Signature=${sig}`;
}

// Download token that works for exactly one file name (the prefix is the full name) for URL_SECONDS.
async function b2DownloadToken(auth: B2Auth, file: string): Promise<string> {
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

    // Best way: endpoint secret set -> signed here with zero Backblaze calls.
    let url = B2_S3_ENDPOINT ? await s3SignedUrl(B2_S3_ENDPOINT, file) : null;
    if (url) return json(200, { url, expires_in: URL_SECONDS }, { "Cache-Control": "private, max-age=1800" });

    const auth = await getAuth();
    // Next: signed here after one authorize per server copy. Old way only if the S3 address is missing.
    url = auth.s3ApiUrl ? await s3SignedUrl(auth.s3ApiUrl, file) : null;
    if (!url) {
      const token = await b2DownloadToken(auth, file);
      const path = file.split("/").map(encodeURIComponent).join("/");
      url = `${auth.downloadUrl}/file/${B2_BUCKET_NAME}/${path}?Authorization=${encodeURIComponent(token)}`;
    }

    return json(200, { url, expires_in: URL_SECONDS }, { "Cache-Control": "private, max-age=1800" });
  } catch (e) {
    // Never return B2 or database internals to the browser; log them on the server only.
    console.error("b2-media-url error:", (e as Error).message);
    cachedAuth = null; // a bad or expired token is not kept
    return json(502, { error: "MEDIA_URL_FAILED" });
  }
});
