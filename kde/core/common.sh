#!/usr/bin/env bash
#
# Funções e caminhos compartilhados pelos scripts do projeto KDE.
# Não é executável sozinho: install.sh e audit.sh fazem `source` deste arquivo.

KDE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/atena/kde"

KEYS_FILE="$KDE_DIR/configs/keys.conf"
FILES_DIR="$KDE_DIR/configs/files"

if [[ -t 1 ]]; then
    C_STEP=$'\033[1;36m'; C_OK=$'\033[0;32m'; C_WARN=$'\033[1;33m'
    C_ERR=$'\033[1;31m'; C_OFF=$'\033[0m'
else
    C_STEP=""; C_OK=""; C_WARN=""; C_ERR=""; C_OFF=""
fi

step() { printf '\n%s==> %s%s\n' "$C_STEP" "$*" "$C_OFF"; }
ok()   { printf '    %s✓%s %s\n' "$C_OK" "$C_OFF" "$*"; }
note() { printf '    %s\n' "$*"; }
warn() { printf '%saviso:%s %s\n' "$C_WARN" "$C_OFF" "$*" >&2; }
fail() { printf '%sfalhou:%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; }
die()  { printf '%serro:%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# Toda escrita no ~/.config passa por aqui: se a ferramenta do KDE não existir,
# é melhor parar antes de alterar metade da configuração.
require_kde_tools() {
    local tool
    for tool in kwriteconfig6 kreadconfig6; do
        if ! have "$tool"; then
            die "$tool não encontrado (pacote kconfig); sem ele não dá para aplicar as chaves"
        fi
    done
    if have kbuildsycoca6; then
        :
    else
        warn "kbuildsycoca6 ausente: o menu e os atalhos podem demorar a refletir a mudança"
    fi
}
