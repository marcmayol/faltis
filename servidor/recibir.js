// Faltis · recibir precios compartidos (PLAN §3.3).
// Valida cada línea y la deja en el búfer; no guarda nada del que envía.
const CHAINS = new Set(["mercadona","carrefour","alcampo","eroski","lidl","dia","aldi","consum","hipercentro","agromart","spar","suma","ifa","ahorramas","elcorteingles","bm","caprabo","condis","bonpreu","gadis","froiz","masymas","hiperdino","coviran","familycash","bonarea","alimerka","supeco","vidal","sorli","eljamon"]);
const KINDS = new Set(["REGULAR", "PROMO", "MEMBER"]);
const PERSONAL = /\d{5,}|\b(TARJETA|VISA|MASTERCARD|MAESTRO|CLIENTE|SOCIO|NIF|CIF|DNI|TELF?|TELEFONO|CAJERO|CAJERA|OPERADOR|IBAN|CUENTA|PUNTOS|CUPON|TOTAL|CAMBIO|EFECTIVO|IVA|FACTURA|TICKET)\b|\b(ATENDID|ENTREGAD|AUTORIZ)/;
const MAX_LINES = 200, MAX_BUFFER = 20000, DAY = 86400000;

const body = $input.first().json.body || {};
if (body.v !== 1 || !Array.isArray(body.lines) || body.lines.length === 0 || body.lines.length > MAX_LINES) {
  return [{ json: { ok: false, error: "formato" } }];
}

const today = Date.now();
const valid = (l) => l && typeof l === "object"
  && CHAINS.has(l.chain)
  && typeof l.cp === "string" && /^(\d{5})?$/.test(l.cp)
  && typeof l.text === "string" && /^[A-Z0-9.,\/ ]{3,60}$/.test(l.text) && (l.text.match(/[A-Z]/g) || []).length >= 3 && !PERSONAL.test(l.text)
  && Number.isInteger(l.price) && l.price >= 1 && l.price <= 50000
  && (l.unit === "ud" || l.unit === "kg")
  && KINDS.has(l.kind)
  && typeof l.day === "string" && /^\d{4}-\d{2}-\d{2}$/.test(l.day)
  && (() => { const t = Date.parse(l.day + "T12:00:00Z"); return t <= today + DAY && t >= today - 60 * DAY; })();

const seen = new Set();
const lines = [];
for (const l of body.lines) {
  if (!valid(l)) continue;
  // Solo los campos conocidos: lo que sobre en la petición no se guarda.
  const clean = { chain: l.chain, cp: l.cp, text: l.text, price: l.price, unit: l.unit, kind: l.kind, day: l.day };
  const key = JSON.stringify(clean);
  if (seen.has(key)) continue;
  seen.add(key);
  lines.push(clean);
}

const data = $getWorkflowStaticData("global");
data.buffer = data.buffer || [];
const room = Math.max(0, MAX_BUFFER - data.buffer.length);
data.buffer.push(...lines.slice(0, room));
return [{ json: { ok: true, accepted: Math.min(lines.length, room) } }];
