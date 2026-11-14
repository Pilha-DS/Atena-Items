#!/usr/bin/env bash
#
# Builda os APKs Android de release sempre assinados com a MESMA chave.
#
# O problema que isto resolve: o Android só aceita uma atualização por cima
# quando o APK novo tem o mesmo applicationId E a mesma assinatura. Se a
# keystore muda (outra máquina, "clean" no home, formatação), a única saída é
# desinstalar — e aí os dados locais do app vão embora.
#
# Por isso a keystore não mora no repositório nem é recriada: mora num cofre
# (por padrão ~/.config/atena/android-keys), e este script confere a assinatura
# do APK contra o fingerprint guardado ali depois de cada build.
#
#   ./scripts/android-release.sh              # builda notas + calendário
#   ./scripts/android-release.sh --check      # só valida cofre e credenciais
#   ./scripts/android-release.sh --only notes
#   ./scripts/android-release.sh --bump       # +1 no versionCode antes de buildar
#   ./scripts/android-release.sh --backup     # cofre -> .tar.gz.enc (com senha)
#   ./scripts/android-release.sh --restore <arquivo>
#
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
VAULT="${ATENA_ANDROID_KEYS:-$HOME/.config/atena/android-keys}"
OUT_DIR="${ATENA_APK_OUT:-$HOME/Downloads/atena-apks}"

NOTES_DIR="$REPO_ROOT/notes/Android"
CALENDAR_DIR="$REPO_ROOT/agenda/android"
CREDENTIALS="$VAULT/credentials.env"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33maviso:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merro:\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ---------------------------------------------------------------------------
# Cofre
# ---------------------------------------------------------------------------

require_vault() {
    [ -f "$CREDENTIALS" ] || die "cofre não encontrado: $CREDENTIALS
Restaure um backup com --restore, ou crie o cofre do zero (ver scripts/README.md)."
    [ -f "$VAULT/notes.jks" ]    || die "falta a keystore do notas: $VAULT/notes.jks"
    [ -f "$VAULT/calendar.jks" ] || die "falta a keystore do calendário: $VAULT/calendar.jks"
}

# Credenciais do cofre. Ficam só no processo; nada é escrito no repositório além
# dos dois arquivos de properties que o Gradle já espera.
load_credentials() {
    require_vault
    # shellcheck disable=SC1090
    . "$CREDENTIALS"
    : "${ATENA_NOTES_STORE_PASSWORD:?falta ATENA_NOTES_STORE_PASSWORD no cofre}"
    : "${ATENA_NOTES_KEY_ALIAS:?falta ATENA_NOTES_KEY_ALIAS no cofre}"
    : "${ATENA_NOTES_KEY_PASSWORD:?falta ATENA_NOTES_KEY_PASSWORD no cofre}"
    : "${ATENA_CALENDAR_STORE_PASSWORD:?falta ATENA_CALENDAR_STORE_PASSWORD no cofre}"
    : "${ATENA_CALENDAR_KEY_ALIAS:?falta ATENA_CALENDAR_KEY_ALIAS no cofre}"
    : "${ATENA_CALENDAR_KEY_PASSWORD:?falta ATENA_CALENDAR_KEY_PASSWORD no cofre}"
}

detect_sdk() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$NOTES_DIR/local.properties" ]; then
        sdk="$(awk -F= '/^sdk\.dir=/{sub(/^sdk\.dir=/, ""); print; exit}' "$NOTES_DIR/local.properties")"
    fi
    [ -n "$sdk" ] || sdk="$HOME/Android/Sdk"
    [ -d "$sdk" ] || die "Android SDK não encontrado (defina ANDROID_HOME). Testei: $sdk"
    SDK="$sdk"
    export ANDROID_HOME="$SDK"
}

# O Gradle lê estes dois arquivos; escrevê-los aqui é o que amarra o build ao
# cofre em vez de a uma keystore local que pode ter sido apagada.
wire_properties() {
    info "ligando o build às keystores do cofre"
    cat > "$NOTES_DIR/local.properties" <<EOF
sdk.dir=$SDK
storeFile=$VAULT/notes.jks
JOTTER_KEYSTORE_PASSWORD=$ATENA_NOTES_STORE_PASSWORD
JOTTER_KEY_ALIAS=$ATENA_NOTES_KEY_ALIAS
JOTTER_KEY_PASSWORD=$ATENA_NOTES_KEY_PASSWORD
EOF
    chmod 600 "$NOTES_DIR/local.properties"

    printf 'sdk.dir=%s\n' "$SDK" > "$CALENDAR_DIR/local.properties"
    cat > "$CALENDAR_DIR/keystore.properties" <<EOF
storePassword=$ATENA_CALENDAR_STORE_PASSWORD
keyPassword=$ATENA_CALENDAR_KEY_PASSWORD
keyAlias=$ATENA_CALENDAR_KEY_ALIAS
storeFile=$VAULT/calendar.jks
EOF
    chmod 600 "$CALENDAR_DIR/keystore.properties"
}

# ---------------------------------------------------------------------------
# Assinatura
# ---------------------------------------------------------------------------

build_tools() {
    local dir="$SDK/build-tools"
    [ -d "$dir" ] || die "sem build-tools no SDK ($dir)"
    ls -1 "$dir" | sort -V | tail -1
}

# Impressão digital (SHA-256) do certificado que assinou o APK, sem separadores.
# Extrai pelo formato (32 pares hex) em vez de casar o texto: o apksigner é
# localizado e o rótulo muda com o idioma do sistema.
apk_fingerprint() {
    local apk="$1" bt out
    bt="$(build_tools)"
    # Captura a saída inteira antes de filtrar: com `pipefail`, um `head`/`awk
    # que sai cedo fecha o pipe e o produtor termina com SIGPIPE (141), o que
    # abortaria o script à toa.
    out="$("$SDK/build-tools/$bt/apksigner" verify --print-certs "$apk" 2>/dev/null)"
    # Remove os ":" primeiro: o apksigner imprime o digest sem separador, o
    # keytool imprime com — normalizando, os dois formatos caem no mesmo regex.
    printf '%s\n' "$out" \
        | tr -d ':' \
        | grep -oE '[0-9A-Fa-f]{64}' \
        | awk 'NR == 1 { print tolower($0) }'
}

# Fingerprint que a keystore do cofre produz. É a referência: se o APK não bater
# com ele, o build saiu assinado por outra chave e a atualização vai falhar.
#
# Lê o certificado sem depender do alias: o Gradle usa o alias para assinar, mas
# o que o Android compara é o certificado, então é ele que vale como referência.
vault_fingerprint() {
    local keystore="$1" password="$2" alias="$3" cached="$4"
    # Confere o conteúdo, não só o tamanho: um arquivo com uma linha vazia
    # passaria por "-s" e devolveria "" como se fosse fingerprint.
    if [ -s "$cached" ] && grep -qiE '^[0-9a-f]{64}$' "$cached"; then
        cat "$cached"
        return
    fi
    local fp
    # Exporta o certificado e deixa o openssl calcular o SHA-256. O texto do
    # keytool muda com o locale ("SHA256" x "SHA-256"), o do openssl não.
    fp="$(keytool -exportcert -rfc -keystore "$keystore" -storepass "$password" -alias "$alias" 2>/dev/null \
        | openssl x509 -noout -fingerprint -sha256 2>/dev/null \
        | sed -E 's/.*=//; s/://g' | tr 'A-F' 'a-f')"
    [ -n "$fp" ] || die "não consegui ler o certificado de $keystore (senha ou alias errado no cofre?)"
    printf '%s\n' "$fp" > "$cached"
    chmod 600 "$cached"
    printf '%s\n' "$fp"
}

# Aliases que a keystore realmente tem, para avisar quando o do cofre mudou.
keystore_aliases() {
    local keystore="$1" password="$2"
    keytool -list -keystore "$keystore" -storepass "$password" 2>/dev/null \
        | awk -F',' '/PrivateKeyEntry/{gsub(/^ +| +$/,"",$1); print $1}'
}

# O Gradle falha de um jeito obscuro se o alias não existir; melhor avisar antes.
require_alias() {
    local keystore="$1" password="$2" alias="$3" label="$4"
    keystore_aliases "$keystore" "$password" | grep -qx "$alias" && return 0
    local found
    found="$(keystore_aliases "$keystore" "$password" | paste -sd', ' -)"
    die "$label: o alias \"$alias\" não existe na keystore. Aliases disponíveis: ${found:-nenhum}"
}

verify_apk() {
    local apk="$1" expected="$2" label="$3"
    [ -f "$apk" ] || die "$label: APK não foi gerado ($apk)"
    local actual
    actual="$(apk_fingerprint "$apk")"
    [ -n "$actual" ] || die "$label: APK sem assinatura verificável"
    if [ "$actual" != "$expected" ]; then
        die "$label: assinatura DIFERENTE do cofre!
  esperado: $expected
  no APK:   $actual
Instalar isso exigiria desinstalar o app. Abortando."
    fi
    info "$label: assinatura confere (${actual:0:16}…)"
}

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

gradle_build() {
    local dir="$1" task="$2" label="$3"
    info "buildando $label ($task)"
    ( cd "$dir" && ./gradlew "$task" --console=plain -q )
}

# Copia o APK para a pasta de entrega com nome legível, lendo a versão do
# próprio APK em vez de confiar no arquivo de build.
deliver() {
    local apk="$1" base="$2" bt out version
    bt="$(build_tools)"
    out="$("$SDK/build-tools/$bt/aapt2" dump badging "$apk" 2>/dev/null)"
    version="$(printf '%s\n' "$out" | awk -F"'" '/^package:/{print $6}')"
    mkdir -p "$OUT_DIR"
    local dest="$OUT_DIR/$base-${version:-x}.apk"
    cp -f "$apk" "$dest"
    info "entregue: $dest"
    printf '%s\n' "$dest"
}

bump_notes() {
    local file="$NOTES_DIR/app/build.gradle.kts"
    local current count
    count="$(grep -cE '^[[:space:]]*versionCode = [0-9]+' "$file" || true)"
    [ "$count" = "1" ] || die "--bump: esperava 1 versionCode em $file, achei $count"
    current="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$file")"
    sed -i -E "s/^([[:space:]]*versionCode = )$current/\1$((current + 1))/" "$file"
    info "notas: versionCode $current -> $((current + 1))"
}

bump_calendar() {
    local file="$CALENDAR_DIR/gradle.properties"
    local current count
    count="$(grep -cE '^VERSION_CODE=[0-9]+' "$file" || true)"
    [ "$count" = "1" ] || die "--bump: esperava 1 VERSION_CODE em $file, achei $count"
    current="$(sed -nE 's/^VERSION_CODE=([0-9]+).*/\1/p' "$file")"
    sed -i -E "s/^VERSION_CODE=$current/VERSION_CODE=$((current + 1))/" "$file"
    info "calendário: VERSION_CODE $current -> $((current + 1))"
}

build_notes() {
    local expected
    expected="$(vault_fingerprint "$VAULT/notes.jks" "$ATENA_NOTES_STORE_PASSWORD" \
        "$ATENA_NOTES_KEY_ALIAS" "$VAULT/notes.fingerprint")"
    require_alias "$VAULT/notes.jks" "$ATENA_NOTES_STORE_PASSWORD" \
        "$ATENA_NOTES_KEY_ALIAS" "notas"
    gradle_build "$NOTES_DIR" ":app:assembleRelease" "notas"
    local apk="$NOTES_DIR/app/build/outputs/apk/release/app-release.apk"
    verify_apk "$apk" "$expected" "notas"
    deliver "$apk" "atena-notes" >/dev/null
}

build_calendar() {
    local expected
    expected="$(vault_fingerprint "$VAULT/calendar.jks" "$ATENA_CALENDAR_STORE_PASSWORD" \
        "$ATENA_CALENDAR_KEY_ALIAS" "$VAULT/calendar.fingerprint")"
    require_alias "$VAULT/calendar.jks" "$ATENA_CALENDAR_STORE_PASSWORD" \
        "$ATENA_CALENDAR_KEY_ALIAS" "calendário"
    gradle_build "$CALENDAR_DIR" ":app:assembleFossRelease" "calendário"
    local apk="$CALENDAR_DIR/app/build/outputs/apk/foss/release/calendar-foss-release.apk"
    # O nome carrega o versionCode (calendar-<code>-foss-release.apk).
    if [ ! -f "$apk" ]; then
        apk="$(find "$CALENDAR_DIR/app/build/outputs/apk/foss/release" -name '*-foss-release.apk' -print -quit)"
    fi
    verify_apk "$apk" "$expected" "calendário"
    deliver "$apk" "atena-calendar" >/dev/null
}

# ---------------------------------------------------------------------------
# Cofre: backup e restauração
# ---------------------------------------------------------------------------

backup_vault() {
    require_vault
    local dest="${1:-$HOME/atena-android-keys-$(date +%Y%m%d).tar.gz.enc}"
    info "backup criptografado do cofre -> $dest"
    tar czf - -C "$(dirname "$VAULT")" "$(basename "$VAULT")" \
        | openssl enc -aes-256-cbc -pbkdf2 -salt -out "$dest"
    chmod 600 "$dest"
    info "guarde a senha em local separado: sem ela o backup é inútil"
}

restore_vault() {
    local source="${1:-}"
    [ -n "$source" ] || die "--restore precisa do arquivo do backup"
    [ -f "$source" ] || die "backup não encontrado: $source"
    [ -e "$VAULT" ] && die "cofre já existe em $VAULT; mova-o antes de restaurar"
    info "restaurando cofre de $source"
    mkdir -p "$(dirname "$VAULT")"
    openssl enc -d -aes-256-cbc -pbkdf2 -in "$source" \
        | tar xzf - -C "$(dirname "$VAULT")"
    chmod 700 "$VAULT"
    chmod 600 "$VAULT"/*.jks "$VAULT"/*.env 2>/dev/null || true
    info "cofre restaurado em $VAULT"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    local do_notes=1 do_calendar=1 do_bump=0 mode="build" restore_from=""

    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)     usage; exit 0 ;;
            --check)       mode="check" ;;
            --bump)        do_bump=1 ;;
            --only)        shift; case "${1:-}" in
                               notes)    do_calendar=0 ;;
                               calendar) do_notes=0 ;;
                               *) die "--only aceita 'notes' ou 'calendar'" ;;
                           esac ;;
            --backup)      mode="backup" ;;
            --restore)     mode="restore"; shift; restore_from="${1:-}" ;;
            *)             die "opção desconhecida: $1 (use --help)" ;;
        esac
        shift
    done

    case "$mode" in
        restore) restore_vault "$restore_from"; return 0 ;;
        backup)  backup_vault; return 0 ;;
    esac

    load_credentials
    detect_sdk

    # Garante o fingerprint de referência mesmo em modo --check, para detectar
    # cedo uma keystore trocada.
    local notes_fp calendar_fp
    notes_fp="$(vault_fingerprint "$VAULT/notes.jks" "$ATENA_NOTES_STORE_PASSWORD" \
        "$ATENA_NOTES_KEY_ALIAS" "$VAULT/notes.fingerprint")"
    calendar_fp="$(vault_fingerprint "$VAULT/calendar.jks" "$ATENA_CALENDAR_STORE_PASSWORD" \
        "$ATENA_CALENDAR_KEY_ALIAS" "$VAULT/calendar.fingerprint")"

    grep -qiE '^[0-9a-f]{64}$' <<<"$notes_fp"    || die "fingerprint do notas inválido no cofre"
    grep -qiE '^[0-9a-f]{64}$' <<<"$calendar_fp" || die "fingerprint do calendário inválido no cofre"

    if [ "$mode" = "check" ]; then
        info "cofre: $VAULT"
        info "notas:      ${notes_fp:0:16}… (alias $ATENA_NOTES_KEY_ALIAS, na keystore: $(keystore_aliases "$VAULT/notes.jks" "$ATENA_NOTES_STORE_PASSWORD" | paste -sd', ' -))"
        info "calendário: ${calendar_fp:0:16}… (alias $ATENA_CALENDAR_KEY_ALIAS, na keystore: $(keystore_aliases "$VAULT/calendar.jks" "$ATENA_CALENDAR_STORE_PASSWORD" | paste -sd', ' -))"
        info "SDK: $SDK"
        info "tudo pronto; um build aqui gera APK que atualiza por cima"
        return 0
    fi

    wire_properties
    [ "$do_bump" = "1" ] && { bump_notes; bump_calendar; }
    [ "$do_notes" = "1" ]    && build_notes
    [ "$do_calendar" = "1" ] && build_calendar

    ( cd "$OUT_DIR" && sha256sum *.apk > SHA256SUMS.txt )

    info "pronto. APKs em $OUT_DIR"
    info "instale com: adb install -r $OUT_DIR/<arquivo>.apk"
}

main "$@"
