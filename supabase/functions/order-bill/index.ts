// Shivtej Footwear - customer bill (PDF) for one order (Supabase Edge Function).
//
// POST /functions/v1/order-bill
//   { "order_id": "SW...", "mobile": "9876543210" }
//   -> 200 application/pdf  (the bill, downloads as <order_id>.pdf)
//   -> 400 BAD_INPUT | 403 NOT_READY (not paid yet, or not confirmed, or cancelled)
//      | 403 EXPIRED (more than 15 days after the order) | 404 NOT_FOUND
//
// The customer must give the order number AND the mobile used for the order (same check as tracking),
// so nobody can download someone else's bill. The bill is only for ONLINE PAID orders, and only once
// the order is Confirmed (auto-confirmed when the payment is received).
// No outside libraries: the PDF is written here directly (plain Helvetica text, A4, one page).
// Secrets: none. Provided automatically by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// Shop details printed on the bill. Change here if the shop details change.
const SHOP = {
  name: "Shivtej Footwear",
  address: "Bajaj Finance front side, Akluj, Maharashtra - 413101",
  phone: "8898900888",
};
const BILL_STATES = ["Confirmed", "Packed", "Shipped", "Delivered"];
// The bill is not stored anywhere. It is made again each time it is asked for, and only for this many days after the order.
const BILL_DAYS = 15;
// Online payment only (no Cash on Delivery). "Paid" yes; "pending" or "unpaid" no.
function isPaid(s: unknown): boolean {
  const v = String(s ?? "").toLowerCase();
  return v.includes("paid") && !v.includes("unpaid") && !v.includes("pending");
}
const TERMS = "No guarantee, No warranty, No return, No exchange";

// Colours (red matches the website), as PDF colour values 0-1.
const RED = "0.890 0.024 0.075";
const LIGHT = "0.955 0.955 0.955";
const LINE = "0.820 0.820 0.820";
const WHITE = "1 1 1";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function fail(status: number, error: string): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

// Plain letters only (the built-in PDF font). Anything else, such as Hindi or Marathi, shows as "?".
function plain(s: unknown): string {
  return String(s ?? "").replace(/[^\x20-\x7E]/g, "?");
}

// Escapes text for a PDF string: ( ) and \ need a backslash in front.
function pdfEsc(s: string): string {
  return s.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
}

function money(n: unknown): string {
  const v = Math.round(Number(n ?? 0));
  return "Rs. " + v.toLocaleString("en-IN");
}

// How the payment is shown on the bill.
function paymentLabel(s: unknown): string {
  const v = String(s ?? "").toLowerCase();
  if (v.includes("cod")) return "Cash on Delivery";
  if (v.includes("paid")) return "Paid online";
  if (v.includes("pending")) return "Online payment pending";
  return plain(s) || "-";
}

function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  return d.toLocaleDateString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", year: "numeric" });
}

// Splits text into lines of at most maxChars characters (Helvetica is about 0.55 of the font size per letter).
function wrap(text: string, size: number, maxWidth: number): string[] {
  const maxChars = Math.max(8, Math.floor(maxWidth / (size * 0.55)));
  const words = plain(text).split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let line = "";
  for (const w of words) {
    const test = line ? line + " " + w : w;
    if (test.length <= maxChars) {
      line = test;
    } else {
      if (line) lines.push(line);
      line = w.slice(0, maxChars);
    }
  }
  if (line) lines.push(line);
  return lines.length ? lines : [""];
}

// Builds the PDF file bytes from one page of drawing commands.
function makePdf(content: string): Uint8Array {
  const objs: string[] = [];
  objs[1] = "<< /Type /Catalog /Pages 2 0 R >>";
  objs[2] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>";
  objs[3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 6 0 R /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> >>";
  objs[4] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>";
  objs[5] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>";
  objs[6] = `<< /Length ${content.length} >>\nstream\n${content}\nendstream`;

  let out = "%PDF-1.4\n";
  const offsets: number[] = [];
  for (let i = 1; i <= 6; i++) {
    offsets[i] = out.length;
    out += `${i} 0 obj\n${objs[i]}\nendobj\n`;
  }
  const xref = out.length;
  out += "xref\n0 7\n0000000000 65535 f \n";
  for (let i = 1; i <= 6; i++) out += String(offsets[i]).padStart(10, "0") + " 00000 n \n";
  out += `trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new TextEncoder().encode(out);
}

function buildBill(o: any): Uint8Array {
  const ops: string[] = [];
  const L = 40;
  const R = 555;

  // Text at x,y. st.bold uses Helvetica-Bold; st.color sets the text colour.
  const text = (s: string, x: number, y: number, size = 10, st: { bold?: boolean; grey?: boolean; color?: string } = {}) => {
    const col = st.color ? `${st.color} rg` : st.grey ? "0.40 g" : "0.10 g";
    ops.push(`BT ${col} /${st.bold ? "F2" : "F1"} ${size} Tf ${x} ${y} Td (${pdfEsc(plain(s))}) Tj ET`);
  };
  const hline = (x1: number, x2: number, y: number, color = LINE, w = 0.7) =>
    ops.push(`${color} RG ${w} w ${x1} ${y} m ${x2} ${y} l S`);
  const fillRect = (x: number, y: number, w: number, h: number, color: string) =>
    ops.push(`${color} rg ${x} ${y} ${w} ${h} re f`);

  // Red band at the top
  fillRect(0, 822, 595, 20, RED);

  // Shop details (left)
  text(SHOP.name, L, 790, 20, { bold: true });
  let y = 774;
  for (const l of wrap(SHOP.address, 9.5, 280)) { text(l, L, y, 9.5, { grey: true }); y -= 12; }
  text("Phone: " + SHOP.phone, L, y, 9.5, { grey: true });

  // BILL title and order details (right)
  text("BILL", 470, 790, 22, { bold: true, color: RED });
  const details: [string, string][] = [
    ["Order no:", String(o.id)],
    ["Date:", fmtDate(o.created_at)],
    ["Payment:", paymentLabel(o.payment_status)],
    ["Status:", String(o.order_status || "-")],
  ];
  let dy = 772;
  for (const [k, v] of details) {
    text(k, 330, dy, 9.5, { bold: true });
    text(v, 395, dy, 9.5);
    dy -= 13;
  }
  hline(L, R, 706, RED, 1.2);

  // Bill to and shipping
  y = 688;
  text("BILL TO", L, y, 9, { bold: true, color: RED });
  text("SHIPPING", 320, y, 9, { bold: true, color: RED });
  y -= 14;
  const c = o.customer ?? {};
  const addrLines = wrap([c.address, c.city, c.state, c.pincode ? "PIN " + c.pincode : ""].filter(Boolean).join(", "), 10, 240);
  const leftRows = [String(c.name || ""), "Mobile: " + String(c.mobile || "-"), ...addrLines];
  const rightRows = [
    "Courier: " + String(o.courier || "Not booked yet"),
    "Tracking no: " + String(o.tracking_no || "Pending"),
  ];
  const n = Math.max(leftRows.length, rightRows.length);
  for (let i = 0; i < n; i++) {
    if (leftRows[i]) text(leftRows[i], L, y - i * 13, 10, { bold: i === 0 });
    if (rightRows[i]) text(rightRows[i], 320, y - i * 13, 10);
  }
  y -= n * 13 + 12;
  hline(L, R, y);
  y -= 22;

  // Items table: grey header bar, one row per item
  const X = { item: L + 8, colour: 270, size: 350, qty: 405, price: 445, amount: 500 };
  fillRect(L, y - 6, R - L, 20, LIGHT);
  text("Item", X.item, y, 9.5, { bold: true });
  text("Colour", X.colour, y, 9.5, { bold: true });
  text("Size", X.size, y, 9.5, { bold: true });
  text("Qty", X.qty, y, 9.5, { bold: true });
  text("Price", X.price, y, 9.5, { bold: true });
  text("Amount", X.amount, y, 9.5, { bold: true });
  y -= 24;

  const items: any[] = Array.isArray(o.items) ? o.items : [];
  for (const it of items.slice(0, 18)) {
    const qty = Number(it.qty ?? it.q ?? 1);
    const price = Number(it.price ?? 0);
    const name = wrap(String(it.name || it.article || "Item"), 10, 215);
    text(name[0], X.item, y, 10);
    text(String(it.colour || "-"), X.colour, y, 10);
    text(String(it.size || "-"), X.size, y, 10);
    text(String(qty), X.qty, y, 10);
    text(money(price), X.price, y, 10);
    text(money(price * qty), X.amount, y, 10);
    let last = y;
    for (const extra of name.slice(1)) { last -= 13; text(extra, X.item, last, 10); }
    const sep = last - 7;
    hline(L, R, sep, LINE, 0.4);
    y = sep - 14;
  }

  // Totals (right side), total in a red box
  y -= 8;
  const TX = 330;
  const totRow = (label: string, value: string) => {
    text(label, TX + 10, y, 10);
    text(value, 470, y, 10);
    y -= 17;
  };
  totRow("Items total", money(o.subtotal));
  totRow("Shipping", money(o.shipping));
  // y is now 17 below the Shipping line, so the red box starts 14 below that line
  const boxTop = y + 3;
  fillRect(TX, boxTop - 30, R - TX, 30, RED);
  text("Total payable", TX + 10, boxTop - 19, 12, { bold: true, color: WHITE });
  text(money(o.total), 470, boxTop - 19, 12, { bold: true, color: WHITE });

  // Terms and thanks at the bottom
  hline(L, R, 110);
  text(TERMS, L, 92, 10, { bold: true });
  text("Thank you for shopping with " + SHOP.name + ".", L, 76, 9.5, { grey: true });

  return makePdf(ops.join("\n"));
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return fail(405, "METHOD");
  if (!SUPABASE_URL || !SERVICE_KEY) return fail(500, "NOT_CONFIGURED");

  let body: any;
  try {
    body = await req.json();
  } catch {
    return fail(400, "BAD_INPUT");
  }
  const orderId = String(body?.order_id ?? "").trim().toUpperCase();
  const mobile = String(body?.mobile ?? "").replace(/\D/g, "");
  if (!/^SW[0-9A-Z]{6,40}$/.test(orderId) || !/^[6-9]\d{9}$/.test(mobile)) return fail(400, "BAD_INPUT");

  const r = await fetch(`${SUPABASE_URL}/rest/v1/orders?id=eq.${encodeURIComponent(orderId)}&select=*`, {
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
  });
  const rows: any[] = r.ok ? await r.json() : [];
  const o = rows[0];
  // Same answer for "no such order" and "wrong mobile", so the order number alone reveals nothing.
  if (!o || String(o.customer?.mobile ?? "") !== mobile) return fail(404, "NOT_FOUND");
  if (!isPaid(o.payment_status)) return fail(403, "NOT_READY");
  if (!BILL_STATES.includes(o.order_status)) return fail(403, "NOT_READY");
  const placed = new Date(o.created_at).getTime();
  if (!isNaN(placed) && Date.now() - placed > BILL_DAYS * 86400000) return fail(403, "EXPIRED");

  try {
    const bytes = buildBill(o);
    return new Response(bytes, {
      status: 200,
      headers: {
        ...CORS,
        "Content-Type": "application/pdf",
        "Content-Disposition": `attachment; filename="${orderId}.pdf"`,
        "Cache-Control": "no-store",
      },
    });
  } catch (e) {
    console.error("order-bill error", e);
    return fail(500, "SERVER");
  }
});
