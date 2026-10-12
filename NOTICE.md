# Avisos de terceros

Faltis incluye código y datos derivados de estos proyectos de código abierto.

## supermercapy — MIT

https://github.com/jtayped/supermercapy · Copyright (c) 2024 Joel Taylor

- `core/src/main/kotlin/.../ProductMatcher.kt` es un port a Kotlin de su matching de productos
  (`src/supermercapy/_match_text.py` y `match.py`): normalización, lector de tamaños, tokens,
  parecido por diferencias, marcas blancas, envases, validación de EAN y puntuación.
- `core/.../WebCatalog.kt` y `app/.../data/WebPrices.kt` (precios web, opcionales) siguen lo que documenta
  sobre las tiendas online de Mercadona y Aldi: los índices de búsqueda, el almacén por código postal y las
  regiones de precio. Código propio; solo se consulta la búsqueda pública que usan sus webs.
- `core/src/test/resources/match/evaluation.json` es su dataset de evaluación, usado solo como
  test (no se incluye en la app). Contiene nombres y precios publicados por las cadenas.

## EasyCompra — MIT

https://github.com/elopositor/EasyCompra · Copyright (c) 2026 Alejandro Valero Collante

- `Text.distance` (Damerau-Levenshtein acotada) sale de su `Busqueda.kt`.

## mercadona-ticket-analyzer — MIT

https://github.com/pezbailarin/mercadona-ticket-analyzer

- Formato del ticket digital de Mercadona (secciones, artículos al peso sin cantidad, número de
  factura) y la regla de alerta de precio contra la media con margen para fruta y verdura.
  Reimplementado, sin copiar código.

## Tipografías — SIL Open Font License 1.1

- Archivo (Omnibus-Type) y IBM Plex Mono (IBM), en `app/src/main/res/font`. Licencia:
  https://openfontlicense.org/open-font-license-official-text/

## Ideas (sin código copiado)

- receipt-reader (mickomso, sin licencia) y mercadona_tickets_analysis (csanz91, GPL-3.0):
  comprobación por línea y tarjeta de cuadre; mayores subidas y bajadas. Solo ideas, escritas
  desde cero.

---

### Licencia MIT

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
