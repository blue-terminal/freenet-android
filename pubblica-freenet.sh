#!/bin/bash
# Pubblica l'aggiornamento dell'app dentro Freenet: l'app lo trova dal proprio nodo, senza internet.
#
# Ogni APK è diviso in parti da 20 MB, ognuna in un sito Freenet suo (un contratto arriva a 50 MiB).
# Il sito "w70-aggiorna" contiene update.json: versione, note, indirizzi delle parti e impronte SHA-256.
# Solo chi ha le chiavi dei siti (create da `fdev website init`, restano su questo PC) può cambiarli,
# e l'app controlla l'impronta di tutto l'APK prima di proporre l'installazione.
#
# Uso: ./pubblica-freenet.sh            (serve il nodo acceso e fdev nel PATH)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APKS="$ROOT/app/build/outputs/apk/release"
VERSION="v$(sed -n 's/.*versionName "\([0-9.]*\)-.*/\1/p' "$ROOT/app/build.gradle")"
NOTES="$(python3 -c "import json;print(json.load(open('$ROOT/update.json'))['notes'])")"
PART=$((20 * 1024 * 1024))
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

publish() { # cartella, nome della chiave → chiave del sito
  local out
  fdev website init "$2" >/dev/null 2>&1 || true
  for _ in 1 2 3; do
    out="$(fdev website publish "$1" --key "$2" --timeout 900 2>&1 | sed 's/\x1b\[[0-9;]*m//g')"
    grep -q 'must be higher' <<<"$out" || break
    sleep 2
  done
  grep -o '/v1/contract/web/[1-9A-HJ-NP-Za-km-z]*/' <<<"$out" | head -1 | cut -d/ -f5 | grep . \
    || { echo "Pubblicazione di $2 non riuscita: $(tail -1 <<<"$out")" >&2; exit 1; }
}

echo "{" > "$WORK/assets.json"
first=1
for abi in arm64-v8a x86_64; do
  apk="$(ls "$APKS"/*"${VERSION#v}"*"$abi"-release.apk)"
  sha="$(sha256sum "$apk" | cut -d' ' -f1)"
  size="$(stat -c %s "$apk")"
  mkdir -p "$WORK/$abi"
  split -b "$PART" -d -a 1 "$apk" "$WORK/$abi/part-"
  parts=()
  for piece in "$WORK/$abi"/part-*; do
    n="${piece##*-}"
    site="$WORK/site-$abi-$n"
    mkdir -p "$site"
    mv "$piece" "$site/part.bin"
    echo '<!doctype html><meta charset="utf-8"><title>Web 7.0</title><p>Parte di un aggiornamento di Web 7.0.</p>' > "$site/index.html"
    echo "Pubblico $abi parte $((n + 1))…" >&2
    key="$(publish "$site" "w70-agg-$abi-$n")"
    parts+=("\"$key\"")
  done
  [ $first = 1 ] || echo "," >> "$WORK/assets.json"
  first=0
  printf '  "%s": {"sha256": "%s", "size": %s, "parts": [%s]}' "$abi" "$sha" "$size" "$(IFS=,; echo "${parts[*]}")" >> "$WORK/assets.json"
done
echo "}" >> "$WORK/assets.json"

mkdir -p "$WORK/manifest"
python3 - "$WORK" "$VERSION" "$NOTES" <<'EOF'
import json, sys
work, version, notes = sys.argv[1:]
assets = json.load(open(f'{work}/assets.json'))
json.dump({'version': version, 'notes': notes, 'assets': assets}, open(f'{work}/manifest/update.json', 'w'), ensure_ascii=False, indent=1)
EOF
echo '<!doctype html><meta charset="utf-8"><title>Aggiornamenti Web 7.0</title><p>Aggiornamenti dell'"'"'app Web 7.0: li legge l'"'"'app dal proprio nodo Freenet.</p>' > "$WORK/manifest/index.html"
key="$(publish "$WORK/manifest" w70-aggiorna)"
echo "Aggiornamento $VERSION pubblicato su Freenet: /v1/contract/web/$key/update.json"
