# Faltis (nombre provisional) · v0.2

App Android de lista de la compra con lectura de tickets y comparador honesto de precios entre supermercados.
El plan, las especificaciones y el diseño están en `C:\Users\marcm\Desktop\Cistella\`: `PLAN.md`, `SPECS.md`,
`ANALISIS_REPOS.md` (qué aportan los proyectos abiertos) y el diseño temporal en https://claude.ai/artifact/4jDjbrNkj3ra1V9PqHx5eD.

## Módulos

- **`:core`** (Kotlin/JVM, sin Android). Contiene toda la lógica y se prueba con `./gradlew :core:test`:
  - `Taxonomy` + `resources/taxonomia.json`: tipos, sinónimos en castellano y catalán, abreviaturas de ticket; tolera una errata en la lista («lehce»).
  - `FreeTextParser`: «2 leches, pan» → necesidades (F-03).
  - `TicketParser`: texto de OCR o PDF → líneas, peso, descuentos, total, nº de factura y hora; une el texto «en bloques» y comprueba cada línea (F-09).
  - `PriceBook`: precio de una necesidad en una tienda (F-05).
  - `PriceTrends`: «Ha subido» contra la media, lo que más ha cambiado e inflación personal (F-10.5).
  - `BasketComparator`: comparación sobre los artículos comunes (F-06).
  - `Equivalence`: `=` `≈` `~` con el porqué (F-12).
  - `ProductMatcher`: port del matching de supermercapy (tamaños «6 x 1 l», EAN, marcas blancas, envases); validado con su dataset.
  - `Packs.fromText`: formato del envase leído del nombre.
  - `AisleLearner`: orden de pasillos (F-08).
  - `SampleData`: datos de ejemplo ficticios y deterministas.
- **`:app`**: Compose y Material 3, Room (v2), DataStore, ML Kit Text (en el APK) y PdfBox-Android.
  - Tema claro, oscuro y del sistema. Archivo e IBM Plex Mono (OFL) en `res/font`.
  - Importación de varios tickets a la vez (compartir varios PDF o «Importar varios tickets»).
- **`:actualizador`**: auto-actualización fuera de Play Store (DracApps). Copia del módulo de Kuse vía Prompter; ver su README.
  Es lo único que usa la red: el manifiesto `https://marcmayol.com/faltis/updates.json` y el APK de la Release.
  Solo actúa en la build release (la debug es otro paquete).

Código y datos de terceros: ver `NOTICE.md`.

## Compilar y probar

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot"
./gradlew :core:test :actualizador:testDebugUnitTest :app:assembleDebug
```

- Emulador: `faltis_test` (API 34 x86_64, 4 GB).
- La build **debug** trae en «Escanear ticket» varios tickets de prueba (`src/debug/assets`), un lote de 5 PDF para la importación en lote y la opción de pegar texto.
- El golden test del emparejado necesita `core/src/test/resources/match/evaluation.json` de supermercapy (fuera de git); sin él se salta.

## Publicar (DracApps)

- Versión: `appVersionCode` y `appVersionName` en `gradle.properties` (fuente única).
- Firma: `keystore.properties` en la raíz (gitignored) → `C:\Users\marcm\faltis-release.jks`, alias `faltis`. Huella en `scripts/firma_esperada.txt`.
- `python scripts/publicar_release.py --notas "…"` (o `--dry-run`): exige árbol limpio, construye y firma el APK, verifica versión y firma,
  crea la Release `vX.Y.Z` en `marcmayol/faltis`, publica `docs/updates.json` en Pages, espera a que la URL lo sirva y lanza el catálogo de DracApps.
- La tienda: `marcmayol/faltis` en `apps.yaml` de DracApps.
