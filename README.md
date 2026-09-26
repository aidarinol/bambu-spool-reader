# Min3D Studio: Spool Reader & Filament Stock

Small Android app that reads the RFID tag on a Bambu Lab spool, shows the **official colour name**
(e.g. "Jade White", "Charcoal"), and keeps a simple **spool stock list**.

## Features
- **Scan**: hold the phone against a spool to see the colour name, filament type, variant code, hex,
  weight, temperatures and production date.
- **Stock input**: after a scan, the current stock is shown with **−1 / +1** buttons (saved instantly), or type
  `xx spool(s)` and press Save. A typed number **replaces** the old one.
- **Stock page**: every filament in the database, grouped by type in collapsible dropdowns (all closed when opened),
  including colours with 0 stock. Types with the most spools come first, and inside a type the colours with the
  most spools come first. Ties (including all 0-spool types) follow: PLA Basic, PLA Matte, PETG Basic,
  PETG Translucent, PLA Wood, PLA Glow, PLA Marble (the last three abrasive), then A–Z. The order refreshes each
  time the page is opened. Each row has −1 / +1; tap the number to type one.
- **A2L / H2C tags** on each type, from the official spec pages (bambulab.com/en/a2l/specs, /h2c/specs).
  Amber "A2L · HS" = needs a hardened-steel nozzle on the A2L.
- **Reset to 0**: sets every count to 0 (asks for confirmation).
- **Export to PDF**: A4 report of filaments with stock **> 0** only, grouped by type, with subtotals.
- **Save backup / Load backup**: CSV file (`key,type,name,code,colors,qty`) you can keep outside the phone
  and open in Excel. Loading replaces the current stock after a confirmation.

## Privacy & safety
- Only the `NFC` permission. **No INTERNET permission**, so the app cannot send data anywhere.
- **Read-only**: it never writes to a tag.
- Stock counts are stored privately on the phone. Uninstalling the app deletes them, so use **Save backup**
  before uninstalling or updating.
- No third-party libraries; only the Android framework.

## How it works
1. Per-sector MIFARE keys are derived from the tag UID (HKDF-SHA256), following
   [Bambu-Research-Group/RFID-Tag-Guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide).
2. Blocks 1, 2, 4, 5, 6, 12, 14 and 16 are read and parsed (`SpoolData.java`).
3. The colour name is looked up (`ColorDb.java`) in `app/src/main/assets/colors.tsv`, in this order:
   1. material ID + colour code from the variant ID (official Bambu Studio table)
   2. material ID + all colour hex values (official)
   3. material ID + first colour hex (official, flagged "double-check")
   4. community data for old variant IDs (flagged "double-check")

Tested against 5,458 real tag dumps ([Bambu-Lab-RFID-Library](https://github.com/queengooborg/Bambu-Lab-RFID-Library)):
keys matched 5,458/5,458, colour names matched 99.3%.

## Build
Every push to `main` runs the parser tests (`test/ParserTest.java`), builds the APK with GitHub Actions
and publishes it under **Releases**.

## Signing key
No key is stored in this repository. Add two repository secrets
(Settings → Secrets and variables → Actions) so every build is signed with the same key:
- `KEYSTORE_B64`: the PKCS12 keystore (alias `spoolreader`), base64-encoded
- `KEYSTORE_PASSWORD`: its password

Without them, CI signs each build with a one-time key. Android then refuses to install the update
over the old version, and **uninstalling deletes your stock data**.

## Updating the colour list
Download `filaments_color_codes.json` again from BambuStudio and regenerate `colors.tsv`
(columns: fila_id, color_code, colors, fila_type, English name, source).

## Licence & attribution
Released under **GNU AGPL-3.0** (see `LICENSE`) because it contains data derived from:
- **Bambu Studio** (© Bambu Lab, AGPL-3.0): the colour table in `app/src/main/assets/colors.tsv`
  is derived from `resources/profiles/BBL/filament/filaments_color_codes.json`.
- **[Bambu-Lab-RFID-Library](https://github.com/queengooborg/Bambu-Lab-RFID-Library)** (GPL-3.0):
  the tag dumps in `test/fixtures/` and the rows marked `komunitas` in `colors.tsv`.
- The tag format and key derivation follow
  [Bambu-Research-Group/RFID-Tag-Guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide).

Not affiliated with Bambu Lab.
