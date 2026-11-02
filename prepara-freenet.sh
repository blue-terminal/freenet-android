#!/bin/bash
# Scarica Freenet, controlla le impronte e lo mette dove Gradle lo prende per l'APK.
# Le impronte sono quelle di SHA256SUMS.txt della release, con firma ed25519 verificata.
set -euo pipefail
VERSIONE=0.2.154
BASE="https://github.com/freenet/freenet-core/releases/download/v$VERSIONE"
qui="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
installa() {
    local programma="$1" abi="$2" nome="$1-$3-unknown-linux-musl.tar.gz" impronta="$4"
    curl -fsSL -o "$tmp/$nome" "$BASE/$nome"
    echo "$impronta  $tmp/$nome" | sha256sum -c -
    tar -xzf "$tmp/$nome" -C "$tmp" "$programma"
    mkdir -p "$qui/app/src/main/jniLibs/$abi"
    mv "$tmp/$programma" "$qui/app/src/main/jniLibs/$abi/lib$programma.so"
}
installa freenet arm64-v8a aarch64 ac9f5c5271ddabfa07a7e40125bbb6dc497a4367274c6d42d860f6e2ebc439b0
installa freenet x86_64 x86_64 e0c1e6d87f8227c3a84706c93aa101195674f2c26917189c364c5aa1bb1e2ff1
installa fdev arm64-v8a aarch64 bb178bf54743587db2834311ede323afb2b6f4917869fe120988975a658581a7
installa fdev x86_64 x86_64 da7045a856a3c96a3867e88b289ac278eba660073531b1da5b9dfe50aaf78b14
# ghostkey non ha programmi gia' pronti: si compila per i due tipi di telefono
lld="$(find "$(rustc --print sysroot)" -name rust-lld | head -1)"
compila() {
    rustup target add "$2"
    CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER="$lld" \
    RUSTFLAGS="-C target-feature=+crt-static -C link-self-contained=yes" \
    cargo install ghostkey --version 0.1.8 --root "$tmp/$2" --target "$2"
    cp "$tmp/$2/bin/ghostkey" "$qui/app/src/main/jniLibs/$1/libghostkey.so"
}
compila arm64-v8a aarch64-unknown-linux-musl
compila x86_64 x86_64-unknown-linux-musl
echo "Freenet $VERSIONE pronto."
