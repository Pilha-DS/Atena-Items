#!/usr/bin/env bash
#
# Inventário do KDE desta máquina: o que está instalado, o que é ruído e o que
# o projeto já controla. Não altera nada e não precisa de root.
#
#   ./audit.sh
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=core/common.sh
source "$HERE/core/common.sh"

# Os pacotes falam português nesta máquina; a análise dos campos depende do
# texto em inglês, então a auditoria olha o pacote do jeito que o pacman imprime
# com LC_ALL=C (o usuário não vê essa diferença).
export LC_ALL=C

APP_CFG="$CONFIG_HOME/plasma-org.kde.plasma.desktop-appletsrc"
KDE_GLOBALS="$CONFIG_HOME/kdeglobals"
KWINRC="$CONFIG_HOME/kwinrc"

count() { wc -l | tr -d ' '; }

sec() { step "$*"; }

pkg_version() {
    pacman -Q "$1" 2>/dev/null | awk '{print $2}' || true
}

audit_ambiente() {
    sec "ambiente"
    note "$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '"')  (sessão: ${XDG_SESSION_TYPE:-?}, desktop: ${XDG_CURRENT_DESKTOP:-?})"
    note "plasma-workspace: $(pkg_version plasma-workspace)"
    note "kwin: $(pkg_version kwin)"
    note "dolphin: $(pkg_version dolphin)"
    note "kcmutils (KCMs): $(pkg_version kcmutils)"
}

audit_pacotes() {
    sec "pacotes"
    note "total: $(pacman -Qq | count)   explícitos: $(pacman -Qeq | count)"
    local orphans
    orphans="$(pacman -Qdtq || true)"
    if [[ -n "$orphans" ]]; then
        note "órfãos ($(printf '%s\n' "$orphans" | count)):"
        printf '%s\n' "$orphans" | sed 's/^/      /'
    else
        note "órfãos: nenhum"
    fi

    local debug_pkgs
    debug_pkgs="$(pacman -Qq | grep -- '-debug$' || true)"
    if [[ -n "$debug_pkgs" ]]; then
        note "pacotes -debug (build AUR, sem uso em runtime):"
        printf '%s\n' "$debug_pkgs" | sed 's/^/      /'
    fi

    note "maiores pacotes instalados:"
    pacman -Qi | awk '/^Name/ {n=$3} /^Installed Size/ {print $4$5, n}' \
        | sort -rh | sed -n '1,12p' | sed 's/^/      /'
}

audit_plasma() {
    sec "plasma"
    if [[ -f "$APP_CFG" ]]; then
        local panels sections ordered
        panels="$(grep -c '^plugin=org\.kde\.panel$' "$APP_CFG" || true)"
        sections="$(grep -c '^\[Containments\]\[[0-9]*\]\[Applets\]' "$APP_CFG" || true)"

        # Só conta o que está de fato nos painéis (AppletOrder). O appletsrc
        # guarda seções antigas de widgets que já saíram do painel.
        local -A name_of=() tally=()
        local id pl order group total=0
        while IFS=' ' read -r id pl; do
            name_of["$id"]="$pl"
        done < <(awk '
            /^\[Containments\]\[[0-9]+\]\[Applets\]\[[0-9]+\]$/ {
                line = $0; sub(/.*\[Applets\]\[/, "", line); sub(/\].*/, "", line); id = line; next
            }
            /^plugin=/ && id != "" { print id, substr($0, 8); id = "" }
        ' "$APP_CFG")

        order="$(sed -n 's/^AppletOrder=//p' "$APP_CFG" | tr ';' ' ')"
        for group in $order; do
            pl="${name_of[$group]:-desconhecido}"
            tally["$pl"]=$(( ${tally["$pl"]:-0} + 1 ))
            total=$((total + 1))
        done

        note "painéis: $panels   applets em painel: $total   seções no arquivo: $sections (o resto é histórico)"
        note "applets por tipo (só os que estão no painel):"
        if [[ ${#tally[@]} -gt 0 ]]; then
            for pl in "${!tally[@]}"; do
                printf '%s %s\n' "${tally[$pl]}" "$pl"
            done | sort -rn | sed 's/^/      /'
        fi
    else
        warn "sem $APP_CFG (nunca abriu o Plasma como este usuário?)"
    fi

    if [[ -f "$KWINRC" ]]; then
        note "efeitos/plugins do KWin ligados:"
        awk '/^\[Plugins\]/ {p=1; next} /^\[/ {p=0} p && /Enabled=true$/ {sub(/Enabled=true/, ""); print "      " $0}' "$KWINRC"
    fi

    if [[ -f "$KDE_GLOBALS" ]]; then
        note "tema atual: $(sed -n 's/^LookAndFeelPackage=//p' "$KDE_GLOBALS")"
    fi
}

audit_configuracoes() {
    sec "telas de configuração"
    if have kcmshell6; then
        local total list
        list="$(QT_QPA_PLATFORM=offscreen kcmshell6 --list 2>/dev/null || true)"
        total="$(printf '%s\n' "$list" | grep -c '^kcm_' || true)"
        note "KCMs instalados: $total"
        note "candidatos a esconder/fundir (mobile, sem descrição e duplicados):"
        printf '%s\n' kcm_mobile_wifi kcm_mobile_wired kcm_mobile_hotspot \
            kcm_mobile_power kcm_cellular_network kcm_qtquicksettings kcm_touchscreen \
            kcm_nighttime kcm_kwintouchscreen \
            | while IFS= read -r id; do
                if printf '%s\n' "$list" | grep -q "^$id "; then
                    printf '      %s\n' "$id"
                fi
            done
    else
        warn "kcmshell6 não encontrado"
    fi
}

audit_menu_temas() {
    sec "menu e temas"
    note ".desktop no sistema: $(ls /usr/share/applications/*.desktop 2>/dev/null | count)   do usuário: $(ls "$DATA_HOME"/applications/*.desktop 2>/dev/null | count)"
    note "esquema de cores ativo: $(sed -n 's/^ColorScheme=//p' "$KDE_GLOBALS" 2>/dev/null)"
    note "esquemas de cor: $(ls "$DATA_HOME"/color-schemes 2>/dev/null | count)"
    note "temas de ícone: $(ls "$DATA_HOME"/icons 2>/dev/null | count)"
    note "look-and-feel: $(ls "$DATA_HOME"/plasma/look-and-feel 2>/dev/null | count)"
    note "plasmoids do usuário: $(ls "$DATA_HOME"/plasma/plasmoids 2>/dev/null | count)"
}

audit_projeto() {
    sec "o que este projeto controla"
    local keys files
    keys="$(grep -cvE '^[[:space:]]*(#|$)' "$KEYS_FILE" || true)"
    files="$(find "$FILES_DIR" -type f 2>/dev/null | count)"
    note "chaves em configs/keys.conf: $keys"
    note "arquivos em configs/files/: $files"
    note "backups: $(find "$STATE_DIR/backup" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | count)"
    note "para ver o que ainda falta aplicar: ./install.sh --diff"
}

audit_ambiente
audit_pacotes
audit_plasma
audit_configuracoes
audit_menu_temas
audit_projeto
