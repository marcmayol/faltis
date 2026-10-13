// Faltis · volcar el búfer a Notion barajado, para que las líneas de un mismo
// ticket no queden juntas ni con la misma hora de creación.
const BATCH = 600;
const data = $getWorkflowStaticData("global");
const buffer = data.buffer || [];
if (buffer.length === 0) return [];
for (let i = buffer.length - 1; i > 0; i--) {
  const j = Math.floor(Math.random() * (i + 1));
  [buffer[i], buffer[j]] = [buffer[j], buffer[i]];
}
const out = buffer.splice(0, BATCH);
data.buffer = buffer;
return out.map((l) => ({ json: { ...l, euros: l.price / 100 } }));
