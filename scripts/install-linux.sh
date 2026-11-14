#!/usr/bin/env bash
#
# Instala o Atena inteiro no Linux (Arch Linux + KDE Plasma 6).
#
#   ./scripts/install-linux.sh                  notas + agenda + @Search
#   ./scripts/install-linux.sh --hub            inclui o Atena Hub (docker)
#   ./scripts/install-linux.sh --only notes,search
#   ./scripts/install-linux.sh --check          só confere, não instala nada
#   ./scripts/install-linux.sh --uninstall      remove (preserva seus dados)
#
# Sequência:
#   1. confere distro, Plasma e pré-requisitos;
#   2. instala via pacman só o que falta (JDK 21, docker, jq, qt6-tools…);
#   3. configura a sessão gráfica (PATH com ~/.local/bin);
#   4. chama o install.sh de cada projeto — o build fica com quem sabe fazer;
#   5. registra os widgets do Plasma: eles aparecem em "Adicionar widgets",
#      mas nada é colocado no painel;
#   6. verifica o resultado e diz exatamente o que falhou.
#
# Este é o único script do repo que mexe em algo fora de $HOME, e só via sudo
# para o pacman. Todo o resto instala em ~/.local, ~/.config e ~/.local/share.
#
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
NOTES_DIR="$REPO_ROOT/notes/linux"
AGENDA_DIR="$REPO_ROOT/agenda/linux"
HUB_DIR="$REPO_ROOT/server"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/atena"
ENV_FILE="$CONFIG_HOME/environment.d/50-atena-path.conf"

# Pacotes do que os apps precisam. O runtime das apps vai embutido na app image
# (createDistributable), então o JDK só é necessário para buildar.
PACMAN_PKGS=(
    jdk21-openjdk   # build dos ports Compose Desktop
    jq              # instalador do @Search exige
    curl            # instalador do @Search exige
    unzip           # extração do AppImage do @Search
    libnotify       # notificações dos eventos do dia
    xdg-utils       # update-desktop-database
    qt6-tools       # qmllint antes de instalar o plasmoid
    kmod            # modprobe uinput (input server do @Search)
    docker          # Atena Hub
    docker-compose  # Atena Hub
)

DO_NOTES=1
DO_AGENDA=1
DO_SEARCH=1
DO_HUB=0
DO_WIDGETS=1
SKIP_DEPS=0
ASSUME_YES=0
FORCE=0
MODE="install"
SEARCH_DIR=""
PLASMA_OK=0
WIDGETS_REGISTERED=0
ERRORS=0

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
fail() { printf '%sfalhou:%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; ERRORS=$((ERRORS + 1)); }
die()  { printf '%serro:%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

usage() {
    sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    cat <<'EOF'
Opções:
  --check            confere pré-requisitos e estado; não altera nada
  --only LISTA       notes, agenda, search, hub (separados por vírgula)
  --hub              instala/atualiza também o Atena Hub via docker compose
  --skip-deps        não instala pacotes (use quando já sabe que estão lá)
  --no-widgets       pula o registro dos widgets do Plasma
  --yes, -y          pacman sem confirmação
  --force            segue mesmo se a distro não for Arch
  --uninstall        remove tudo o que foi instalado (dados preservados)
  -h, --help         esta ajuda
EOF
}

# ---------------------------------------------------------------------------
# Pré-requisitos
# ---------------------------------------------------------------------------

assert_user() {
    [[ $EUID -ne 0 ]] || die "não rode como root: ele instala em \$HOME/.local.
       rode como usuário normal; o sudo é usado só no pacman."
    [[ -z "${SUDO_USER:-}" ]] || die "não rode com sudo: HOME viraria /root.
       rode: ./scripts/install-linux.sh"
}

detect_distro() {
    [[ -r /etc/os-release ]] || die "não encontrei /etc/os-release"
    local id like pretty
    # shellcheck disable=SC1091
    { id="$(. /etc/os-release && printf '%s' "${ID:-}")"; } 2>/dev/null || id=""
    like="$(. /etc/os-release && printf '%s' "${ID_LIKE:-}")"
    pretty="$(. /etc/os-release && printf '%s' "${PRETTY_NAME:-}")"

    if [[ "$id" != "arch" && "$like" != *arch* ]]; then
        if [[ "$FORCE" -ne 1 ]]; then
            die "este instalador é para Arch Linux (detectei: ${pretty:-desconhecido}).
       em outra distro os pacotes e o caminho do Plasma mudam;
       use --force por sua conta e risco."
        fi
        warn "distro não é Arch (${pretty:-?}); seguindo por causa de --force"
    fi
    ok "distro: ${pretty:-Arch Linux}"
}

detect_plasma() {
    if ! command -v plasmashell >/dev/null 2>&1; then
        warn "plasmashell não encontrado: sem widgets do Plasma nesta máquina"
        return 0
    fi
    local raw version
    raw="$(plasmashell --version 2>/dev/null || true)"
    version="${raw##* }"
    if [[ "$version" == 6.* ]]; then
        PLASMA_OK=1
        ok "KDE Plasma $version"
    else
        warn "Plasma detectado em versão ${version:-desconhecida}; os widgets exigem Plasma 6"
    fi
}

# ---------------------------------------------------------------------------
# Dependências
# ---------------------------------------------------------------------------

missing_packages() {
    local pkg
    for pkg in "${PACMAN_PKGS[@]}"; do
        pacman -Q "$pkg" >/dev/null 2>&1 || printf '%s\n' "$pkg"
    done
}

install_deps() {
    step "dependências (pacman)"
    command -v pacman >/dev/null 2>&1 || { warn "pacman não encontrado; pulando"; return 0; }

    local -a missing=()
    mapfile -t missing < <(missing_packages)

    if [[ ${#missing[@]} -eq 0 ]]; then
        ok "nada faltando (${#PACMAN_PKGS[@]} pacotes conferidos)"
        return 0
    fi

    note "faltando: ${missing[*]}"
    if [[ "$SKIP_DEPS" -eq 1 ]]; then
        warn "--skip-deps: não vou instalar; o build pode falhar por isso"
        return 0
    fi
    command -v sudo >/dev/null 2>&1 || die "sudo não encontrado e faltam pacotes: ${missing[*]}"

    local -a args=(-S --needed)
    [[ "$ASSUME_YES" -eq 1 ]] && args+=(--noconfirm)
    sudo pacman "${args[@]}" "${missing[@]}" || die "pacman falhou ao instalar: ${missing[*]}"
    ok "pacotes instalados"
}

# ---------------------------------------------------------------------------
# Configuração inicial
# ---------------------------------------------------------------------------

configure_environment() {
    step "configuração inicial da sessão"

    mkdir -p "$STATE_DIR" || die "não consegui criar $STATE_DIR"
    ok "estado em $STATE_DIR"

    # As .desktop usam caminho absoluto, mas o vicinae, os atalhos e os
    # plasmoids procuram os helpers pelo PATH. O environment.d é o que o
    # systemd --user (e portanto a sessão Plasma) lê.
    if [[ ":$PATH:" == *":$HOME/.local/bin:"* ]]; then
        ok "~/.local/bin já está no PATH"
    else
        mkdir -p "$(dirname "$ENV_FILE")"
        cat > "$ENV_FILE" <<EOF
# Gerado por Atena: scripts/install-linux.sh
# Coloca os lançadores em ~/.local/bin no PATH da sessão gráfica.
PATH=$HOME/.local/bin:$PATH
EOF
        ok "PATH da sessão gráfica atualizado ($ENV_FILE)"
        warn "faça logout/login para o PATH valer em toda a sessão"
    fi
    export PATH="$HOME/.local/bin:$PATH"

    if command -v kbuildsycoca6 >/dev/null 2>&1; then
        kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
        ok "cache do KDE reconstruído (atalhos e entradas do menu)"
    fi
}

# ---------------------------------------------------------------------------
# Apps — cada projeto instala a si mesmo
# ---------------------------------------------------------------------------

required_project() {
    local dir="$1" label="$2"
    [[ -d "$dir" ]] || die "pasta do $label não encontrada: $dir"
    [[ -x "$dir/scripts/install.sh" ]] || die "instalador do $label não encontrado ou sem permissão: $dir/scripts/install.sh"
}

install_notes() {
    step "Atena Notes (desktop + widget)"
    required_project "$NOTES_DIR" "Notes"
    ( cd "$NOTES_DIR" && ./scripts/install.sh ) || die "o install.sh do Notes falhou"
    ok "app, lançador e widget instalados"
}

install_agenda() {
    step "Agenda (desktop + widget)"
    required_project "$AGENDA_DIR" "Agenda"
    ( cd "$AGENDA_DIR" && ./scripts/install.sh ) || die "o install.sh da Agenda falhou"
    ok "app, lançador, widget e autostart instalados"
}

# A pasta do @Search pode ter sido renomeada (fork versionado ao lado do
# upstream); procura por qualquer 'search*' que tenha o instalador.
detect_search_dir() {
    local candidate
    for candidate in "$REPO_ROOT/search" "$REPO_ROOT"/*; do
        [[ -d "$candidate" ]] || continue
        [[ "${candidate##*/}" == search* ]] || continue
        [[ -x "$candidate/scripts/install.sh" ]] || continue
        printf '%s\n' "$candidate"
        return 0
    done
    return 1
}

install_search() {
    step "@Search (launcher)"
    if ! SEARCH_DIR="$(detect_search_dir)"; then
        fail "não achei a pasta do @Search (search/ ou similar) neste repo"
        return 0
    fi
    note "projeto: ${SEARCH_DIR#"$REPO_ROOT"/}"
    # Instalação por usuário: nada de root, e o serviço systemd do vicinae cai
    # em ~/.local/share/systemd/user, que é onde o systemd --user procura.
    ( cd "$SEARCH_DIR" && ./scripts/install.sh --prefix "$HOME/.local" ) \
        || { fail "o instalador do @Search falhou"; return 0; }
    if command -v systemctl >/dev/null 2>&1; then
        systemctl --user daemon-reload >/dev/null 2>&1 || true
    fi
    ok "vicinae em ~/.local/bin/vicinae"
}

hub_port() {
    local port="8080"
    if [[ -f "$HUB_DIR/.env" ]]; then
        port="$(sed -n 's/^ATENA_HOST_PORT=\([0-9]\{1,\}\).*/\1/p' "$HUB_DIR/.env" | awk 'NR == 1')"
    fi
    printf '%s\n' "${port:-8080}"
}

port_held_by_hub() {
    docker ps --filter name=atena-hub --format '{{.Ports}}' 2>/dev/null | grep -q ":$1->"
}

install_hub() {
    step "Atena Hub (docker compose)"
    [[ -d "$HUB_DIR" ]] || { fail "pasta server/ não encontrada"; return 0; }
    command -v docker >/dev/null 2>&1 || { fail "docker não instalado"; return 0; }

    if ! docker info >/dev/null 2>&1; then
        note "o daemon do docker não está acessível para este usuário."
        note "habilite e entre no grupo (depois faça logout/login):"
        note "  sudo systemctl enable --now docker"
        note "  sudo usermod -aG docker \$USER"
        fail "hub não instalado: daemon do docker inacessível"
        return 0
    fi

    # A porta 8080 é disputada (phpMyAdmin, outro dev server…). Sem esta
    # checagem o docker falha com um erro de bind que não diz o que fazer.
    local port
    port="$(hub_port)"
    if command -v ss >/dev/null 2>&1 && [[ -n "$(ss -ltnH "sport = :$port" 2>/dev/null)" ]]; then
        if ! port_held_by_hub "$port"; then
            note "a porta $port já está ocupada:"
            ss -ltnH "sport = :$port" 2>/dev/null | sed 's/^/      /'
            note "escolha outra em $HUB_DIR/.env (copie de .env.example):"
            note "  ATENA_HOST_PORT=8090"
            fail "hub não instalado: porta $port ocupada"
            return 0
        fi
        ok "porta $port já é do atena-hub; atualizando no lugar"
    fi

    ( cd "$HUB_DIR" && docker compose up -d --build ) || { fail "docker compose falhou"; return 0; }

    local i
    for i in $(seq 1 30); do
        if curl -fsS "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
            ok "hub respondendo em http://127.0.0.1:$port"
            note "admin: http://127.0.0.1:$port/admin (ADMIN_USER/ADMIN_PASSWORD no .env)"
            return 0
        fi
        sleep 2
    done
    fail "o hub subiu, mas /health não respondeu em 60s (veja: docker compose logs)"
}

# ---------------------------------------------------------------------------
# Widgets do Plasma
# ---------------------------------------------------------------------------

plasmoid_id() {
    sed -n 's/.*"Id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | awk 'NR == 1'
}

register_widgets() {
    step "widgets do Plasma (registrados, sem tocar no painel)"

    if [[ "$PLASMA_OK" -ne 1 ]]; then
        warn "Plasma 6 não detectado; widgets não foram registrados"
        return 0
    fi
    if ! command -v kpackagetool6 >/dev/null 2>&1; then
        warn "kpackagetool6 ausente (pacote plasma-workspace); widgets não registrados"
        return 0
    fi

    local -a metas=()
    [[ "$DO_NOTES" -eq 1 ]] && metas+=("$NOTES_DIR"/kde/*/metadata.json)
    [[ "$DO_AGENDA" -eq 1 ]] && metas+=("$AGENDA_DIR"/kde/*/metadata.json)

    local meta pkg_dir id dest
    local found=0

    for meta in "${metas[@]}"; do
        [[ -f "$meta" ]] || continue
        # kde/ guarda mais que widget (o kwin-script do Notes também tem
        # metadata.json); só Plasma/Applet vira item em "Adicionar widgets".
        grep -q '"KPackageStructure"[[:space:]]*:[[:space:]]*"Plasma/Applet"' "$meta" || continue
        pkg_dir="$(dirname "$meta")"
        id="$(plasmoid_id "$meta")"
        [[ -n "$id" ]] || { warn "metadata sem Id em $meta"; continue; }
        dest="$DATA_HOME/plasma/plasmoids/$id"
        found=1

        # kpackagetool6 é o que registra o pacote de verdade: um `cp` simples
        # deixa o widget invisível em "Adicionar widgets" até reiniciar o shell.
        if [[ -d "$dest" ]]; then
            if ! kpackagetool6 --type Plasma/Applet --upgrade "$pkg_dir" >/dev/null 2>&1; then
                # Pacote antigo instalado à mão não aceita --upgrade; troca.
                kpackagetool6 --type Plasma/Applet --remove "$id" >/dev/null 2>&1 || true
                kpackagetool6 --type Plasma/Applet --install "$pkg_dir" >/dev/null 2>&1 \
                    || { fail "não consegui registrar o widget $id"; continue; }
            fi
        else
            kpackagetool6 --type Plasma/Applet --install "$pkg_dir" >/dev/null 2>&1 \
                || { fail "não consegui registrar o widget $id"; continue; }
        fi
        ok "$id"
    done

    if [[ ${#metas[@]} -eq 0 ]]; then
        return 0
    fi

    if [[ "$found" -eq 0 ]]; then
        fail "nenhum widget válido (KPackageStructure Plasma/Applet) encontrado"
        return 0
    fi

    WIDGETS_REGISTERED=1
    kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
    note "para usar: botão direito no painel -> \"Adicionar widgets…\""
    note "se não aparecerem na lista: systemctl --user restart plasma-plasmashell"
}

# ---------------------------------------------------------------------------
# Verificação
# ---------------------------------------------------------------------------

verify_file() {
    if [[ -e "$1" ]]; then
        ok "$2"
    else
        fail "$2 (esperado em $1)"
    fi
}

verify_widget() {
    if kpackagetool6 --type Plasma/Applet --list 2>/dev/null | grep -qx "$1"; then
        ok "widget $1 registrado"
    else
        fail "widget $1 não registrado"
    fi
}

verify() {
    step "verificação"

    if [[ "$DO_NOTES" -eq 1 ]]; then
        verify_file "$HOME/.local/opt/jotter/bin/jotter" "Notes (app)"
        verify_file "$HOME/.local/bin/jotter-toggle" "Notes (lançador)"
        verify_file "$DATA_HOME/applications/jotter.desktop" "Notes (menu + Super+N)"
    fi
    if [[ "$DO_AGENDA" -eq 1 ]]; then
        verify_file "$HOME/.local/opt/agenda/bin/agenda" "Agenda (app)"
        verify_file "$HOME/.local/bin/agenda-toggle" "Agenda (lançador)"
        verify_file "$DATA_HOME/applications/agenda.desktop" "Agenda (menu + Super+C)"
        verify_file "$CONFIG_HOME/autostart/agenda.desktop" "Agenda (autostart para notificações)"
    fi
    if [[ "$DO_SEARCH" -eq 1 && -n "$SEARCH_DIR" ]]; then
        verify_file "$HOME/.local/bin/vicinae" "@Search (binário)"
        verify_file "$DATA_HOME/systemd/user/vicinae.service" "@Search (serviço systemd)"
    fi
    if [[ "$DO_WIDGETS" -eq 1 && "$PLASMA_OK" -eq 1 ]]; then
        [[ "$DO_NOTES" -eq 1 ]] && verify_widget "org.jotter.widget"
        [[ "$DO_AGENDA" -eq 1 ]] && verify_widget "org.agenda.widget"
    fi
    if [[ "$DO_HUB" -eq 1 ]]; then
        if docker ps --filter name=atena-hub --format '{{.Names}}' 2>/dev/null | grep -qx "atena-hub"; then
            ok "container atena-hub em execução"
        else
            fail "container atena-hub não está rodando"
        fi
    fi
}

# ---------------------------------------------------------------------------
# Desinstalação
# ---------------------------------------------------------------------------

uninstall() {
    step "removendo o que foi instalado (seus dados ficam)"

    if [[ -x "$NOTES_DIR/scripts/install.sh" ]]; then
        ( cd "$NOTES_DIR" && ./scripts/install.sh uninstall ) || warn "uninstall do Notes falhou"
    fi
    if [[ -x "$AGENDA_DIR/scripts/install.sh" ]]; then
        ( cd "$AGENDA_DIR" && ./scripts/install.sh uninstall ) || warn "uninstall da Agenda falhou"
    fi
    if SEARCH_DIR="$(detect_search_dir)"; then
        ( cd "$SEARCH_DIR" && ./scripts/install.sh --uninstall --prefix "$HOME/.local" ) \
            || warn "uninstall do @Search falhou"
    fi
    rm -f "$ENV_FILE"
    if command -v kbuildsycoca6 >/dev/null 2>&1; then
        kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
    fi

    ok "apps, lançadores e widgets removidos"
    note "preservados: ~/.config/{jotter,agenda}, bancos locais e volumes do docker"
}

# ---------------------------------------------------------------------------
# --check
# ---------------------------------------------------------------------------

check() {
    step "pré-requisitos"
    local -a missing=()
    mapfile -t missing < <(missing_packages)

    if [[ ${#missing[@]} -eq 0 ]]; then
        ok "pacotes: nada faltando"
    else
        warn "pacotes faltando: ${missing[*]}"
        note "serão instalados com: sudo pacman -S --needed ${missing[*]}"
    fi

    if SEARCH_DIR="$(detect_search_dir)"; then
        ok "@Search: ${SEARCH_DIR#"$REPO_ROOT"/}"
    else
        warn "@Search: pasta não encontrada (será pulado)"
    fi

    if [[ "$DO_HUB" -eq 1 ]]; then
        local port
        port="$(hub_port)"
        if command -v ss >/dev/null 2>&1 && [[ -n "$(ss -ltnH "sport = :$port" 2>/dev/null)" ]]; then
            if port_held_by_hub "$port"; then
                ok "hub: porta $port já publicada pelo atena-hub"
            else
                warn "hub: porta $port ocupada por outro serviço (use ATENA_HOST_PORT no server/.env)"
            fi
        else
            ok "hub: porta $port livre"
        fi
    fi

    step "o que já está instalado"
    local entry
    for entry in \
        "$HOME/.local/opt/jotter/bin/jotter:Notes" \
        "$HOME/.local/opt/agenda/bin/agenda:Agenda" \
        "$HOME/.local/bin/vicinae:@Search"; do
        if [[ -e "${entry%%:*}" ]]; then
            ok "${entry##*:}: instalado"
        else
            note "${entry##*:}: ainda não instalado"
        fi
    done
    if [[ "$PLASMA_OK" -eq 1 ]]; then
        local id
        for id in org.jotter.widget org.agenda.widget; do
            if kpackagetool6 --type Plasma/Applet --list 2>/dev/null | grep -qx "$id"; then
                ok "widget $id registrado"
            else
                note "widget $id não registrado"
            fi
        done
    fi

    step "resumo"
    note "este comando não alterou nada."
    note "para instalar: ./scripts/install-linux.sh"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)   usage; exit 0 ;;
            --check)     MODE="check" ;;
            --uninstall) MODE="uninstall" ;;
            --hub)       DO_HUB=1 ;;
            --no-widgets) DO_WIDGETS=0 ;;
            --skip-deps) SKIP_DEPS=1 ;;
            --force)     FORCE=1 ;;
            --yes|-y)    ASSUME_YES=1 ;;
            --only)
                shift
                [[ -n "${1:-}" ]] || die "--only precisa de uma lista: notes,agenda,search,hub"
                DO_NOTES=0; DO_AGENDA=0; DO_SEARCH=0; DO_HUB=0
                local item
                local IFS=,
                for item in $1; do
                    case "$item" in
                        notes)   DO_NOTES=1 ;;
                        agenda)  DO_AGENDA=1 ;;
                        search)  DO_SEARCH=1 ;;
                        hub)     DO_HUB=1 ;;
                        *) die "--only não conhece '$item' (use notes, agenda, search, hub)" ;;
                    esac
                done
                unset IFS
                ;;
            *) die "opção desconhecida: $1 (use --help)" ;;
        esac
        shift
    done
}

root_followups() {
    local pending=()

    if [[ "$DO_SEARCH" -eq 1 ]]; then
        local input_bin="$HOME/.local/lib/vicinae/usr/libexec/vicinae/vicinae-input-server"
        if ! lsmod 2>/dev/null | grep -q '^uinput'; then
            pending+=("sudo modprobe uinput")
        fi
        if [[ -f "$input_bin" ]]; then
            if ! command -v getcap >/dev/null 2>&1 || [[ -z "$(getcap "$input_bin" 2>/dev/null)" ]]; then
                pending+=("sudo setcap cap_dac_override=ep $input_bin")
            fi
        fi
    fi

    if [[ "$DO_HUB" -eq 1 ]] && ! id -nG 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
        pending+=("sudo usermod -aG docker \$USER   # e faça logout/login")
    fi

    if [[ ${#pending[@]} -eq 0 ]]; then
        return 0
    fi

    printf '\n%sPendências que precisam de root (opcionais):%s\n' "$C_WARN" "$C_OFF"
    printf '  %s\n' "${pending[@]}"
    note "sem elas o @Search funciona, mas sem colar/snippets."
}

summary() {
    step "pronto"
    if [[ "$ERRORS" -gt 0 ]]; then
        printf '%s%d problema(s) na verificação — veja os "falhou" acima.%s\n' \
            "$C_ERR" "$ERRORS" "$C_OFF"
    else
        ok "tudo instalado e verificado"
    fi

    local -a lines=()
    if [[ "$DO_NOTES" -eq 1 ]]; then
        lines+=("Super+N        abre/fecha o Notes")
    fi
    if [[ "$DO_AGENDA" -eq 1 ]]; then
        lines+=("Super+C        abre/fecha a Agenda")
    fi
    if [[ "$DO_WIDGETS" -eq 1 && "$WIDGETS_REGISTERED" -eq 1 ]]; then
        lines+=("widgets        botão direito no painel -> \"Adicionar widgets…\"")
    fi
    if [[ ${#lines[@]} -gt 0 ]]; then
        printf '\nAtalhos e widgets:\n'
        printf '    %s\n' "${lines[@]}"
    fi

    cat <<EOF

Mudou código de algum projeto? Rode de novo:
  ./scripts/install-linux.sh --only notes
  ./scripts/install-linux.sh --only agenda
  ./scripts/install-linux.sh --only search

A regra .cursor/rules/installer-sync.mdc existe para lembrar de atualizar este
script sempre que um projeto passar a instalar algo novo.

EOF

    root_followups
}

main() {
    parse_args "$@"

    step "ambiente"
    assert_user
    detect_distro
    detect_plasma

    if [[ "$MODE" = "check" ]]; then
        check
        return 0
    fi

    if [[ "$MODE" = "uninstall" ]]; then
        uninstall
        return 0
    fi

    install_deps
    configure_environment

    [[ "$DO_NOTES" -eq 1 ]]  && install_notes
    [[ "$DO_AGENDA" -eq 1 ]] && install_agenda
    [[ "$DO_SEARCH" -eq 1 ]] && install_search
    [[ "$DO_HUB" -eq 1 ]]    && install_hub

    if [[ "$DO_WIDGETS" -eq 1 ]]; then
        register_widgets
    fi

    verify
    summary

    if [[ "$ERRORS" -gt 0 ]]; then
        exit 1
    fi
}

main "$@"
