const fs = require("fs");
const run = (file, body, store) => {
  const fn = new Function("$input", "$getWorkflowStaticData", fs.readFileSync(file, "utf8"));
  return fn({ first: () => ({ json: { body } }) }, () => store);
};
const store = {};
const d = new Date().toISOString().slice(0, 10);
const ok = { chain: "mercadona", cp: "07570", text: "LECHE ENT. HCDO 1L", price: 96, unit: "ud", kind: "REGULAR", day: d, extra: "fuera" };
const bad = [
  { ...ok, chain: "inventada" }, { ...ok, cp: "7570" }, { ...ok, text: "TARJETA 1234" }, { ...ok, text: "leche" },
  { ...ok, price: 0 }, { ...ok, price: 1.5 }, { ...ok, unit: "l" }, { ...ok, kind: "X" }, { ...ok, day: "2020-01-01" }, null,
];
console.log(run("recibir.js", { v: 1, lines: [ok, ok, { ...ok, price: 90, kind: "PROMO" }, ...bad] }, store));
console.log(run("recibir.js", { v: 2, lines: [ok] }, store));
console.log(run("recibir.js", { lines: "x" }, store));
console.log(store.buffer);
console.log(run("volcar.js", null, store), store.buffer.length);
