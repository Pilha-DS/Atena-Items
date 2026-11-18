#!/usr/bin/env bash
#
# Aplica, confere ou desfaz os ajustes versionados do KDE (Dolphin) e instala a
# janela de configurações (modulos/settings).
#
#   ./install.sh              aplica configs/ na sessão (~/.config e ~/.local/share)
#   ./install.sh --diff       mostra o que mudaria; não escreve nada
#   ./install.sh --check      igual ao --diff, mas sai com código 1 se houver diferença
#   ./install.sh --capture    traz para o repo o valor atual da máquina
#   ./install.sh --app        compila e instala a janela de configurações
#   ./install.sh --no-override      instala a janela sem mexer no System Settings
#   ./install.sh --drop-override    desfaz só o override (volta a abrir o original)
#   ./install.sh --uninstall  restaura o estado de antes da primeira aplicação
#
# Por padrão a entrada do menu do System Settings é sombreada por uma cópia em
# ~/.local/share/applications/systemsettings.desktop apontando para a janela
# nova, e ~/.local/bin/systemsettings vira um desvio para ela (as telas do
# Plasma abrem outra tela chamando o binário num processo novo). É um desvio
# temporário, marcado com X-Atena-Override no arquivo e listado em CONFIG-MAP.md;
# --drop-override ou --uninstall desfazem.
#
# Nada aqui precisa de root: tudo vai para $XDG_CONFIG_HOME e $XDG_DATA_HOME.
# Cada arquivo alterado é copiado para
#   ${XDG_STATE_HOME:-~/.local/state}/atena/kde/backup/<data>/
# antes da primeira escrita, e é esse backup que o --uninstall restaura.
#
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=core/common.sh
source "$HERE/core/common.sh"

MODE="apply"
OVERRIDE="on"
UNSET_SENTINEL="__atena_unset__"
SNAP=""
CHANGED=0
SAME=0
FILES_CHANGED=0
FILES_SAME=0
SCHEME_CHANGED=0

usage() {
    sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# Backup
# ---------------------------------------------------------------------------

snapshot_dir() {
    if [[ -z "$SNAP" ]]; then
        SNAP="$STATE_DIR/backup/$(date +%Y-%m-%dT%H-%M-%S)"
        mkdir -p "$SNAP"
    fi
}

# Destino de um arquivo de configs/files: a primeira pasta diz em qual home ele
# cai (config/ -> ~/.config, data/ -> ~/.local/share).
target_path() {
    local rel="$1"
    case "$rel" in
        config/*) printf '%s/%s\n' "$CONFIG_HOME" "${rel#config/}" ;;
        data/*)   printf '%s/%s\n' "$DATA_HOME" "${rel#data/}" ;;
        *)        return 1 ;;
    esac
}

# Guarda uma cópia do arquivo antes da primeira escrita desta execução.
# O snapshot espelha o caminho absoluto, então restaurar é só tirar o prefixo.
snapshot_file() {
    local abs="$1" snap
    snapshot_dir
    snap="$SNAP"
    if [[ -f "$snap/marks$abs" ]]; then
        return 0
    fi
    mkdir -p "$snap/marks$(dirname "$abs")"
    : > "$snap/marks$abs"
    if [[ -e "$abs" ]]; then
        mkdir -p "$snap/files$(dirname "$abs")"
        cp -p "$abs" "$snap/files$abs"
    else
        printf '%s\n' "$abs" >> "$snap/created.list"
    fi
}

oldest_snapshot() {
    local dir
    for dir in "$STATE_DIR"/backup/*/; do
        if [[ -d "$dir" ]]; then
            printf '%s\n' "$dir"
            return 0
        fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# Chaves (configs/keys.conf)
# ---------------------------------------------------------------------------

# Lê o valor atual de uma chave; devolve o sentinela quando ela não existe.
current_value() {
    kreadconfig6 --file "$1" --group "$2" --key "$3" --default "$UNSET_SENTINEL"
}

# Um tema de ícones que não está instalado deixa a sessão com quadrados
# vazios. Avisa antes de escrever a chave; o resto do keys.conf segue.
icon_theme_exists() {
    local theme="$1" root
    for root in "$HOME/.local/share/icons" "$HOME/.icons" /usr/share/icons /usr/local/share/icons; do
        if [[ -d "$root/$theme" ]]; then
            return 0
        fi
    done
    return 1
}

each_key() {
    local mode="$1"
    local line file group key value abs cur
    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ -z "$line" || "${line:0:1}" == "#" ]]; then
            continue
        fi
        IFS='|' read -r file group key value <<< "$line"
        if [[ -z "${file:-}" || -z "${group:-}" || -z "${key:-}" ]]; then
            warn "linha inválida em keys.conf: $line"
            continue
        fi
        if [[ "$group" == "Icons" && "$key" == "Theme" ]] && ! icon_theme_exists "$value"; then
            warn "tema de ícones $value não está instalado; mantive o tema atual"
            continue
        fi
        abs="$CONFIG_HOME/$file"
        cur="$(current_value "$abs" "$group" "$key")"

        if [[ "$cur" == "$value" ]]; then
            SAME=$((SAME + 1))
            if [[ "$mode" == "diff" ]]; then
                note "= $file [$group] $key = $value"
            fi
            continue
        fi

        CHANGED=$((CHANGED + 1))
        if [[ "$cur" == "$UNSET_SENTINEL" ]]; then
            printf '    ~ %s [%s] %s: (ausente) -> %s\n' "$file" "$group" "$key" "$value"
        else
            printf '    ~ %s [%s] %s: %s -> %s\n' "$file" "$group" "$key" "$cur" "$value"
        fi

        if [[ "$mode" == "apply" ]]; then
            snapshot_file "$abs"
            kwriteconfig6 --file "$abs" --group "$group" --key "$key" "$value"
        fi
    done < "$KEYS_FILE"
}

# ---------------------------------------------------------------------------
# Arquivos inteiros (configs/files/)
# ---------------------------------------------------------------------------

each_file() {
    local mode="$1" src rel dest
    [[ -d "$FILES_DIR" ]] || return 0
    while IFS= read -r -d '' src; do
        rel="${src#"$FILES_DIR"/}"
        if ! dest="$(target_path "$rel")"; then
            warn "configs/files/$rel precisa começar com config/ ou data/; ignorado"
            continue
        fi
        if [[ -f "$dest" ]] && cmp -s "$src" "$dest"; then
            FILES_SAME=$((FILES_SAME + 1))
            if [[ "$mode" == "diff" ]]; then
                note "= $rel -> $dest"
            fi
            continue
        fi
        FILES_CHANGED=$((FILES_CHANGED + 1))
        if [[ -f "$dest" ]]; then
            printf '    ~ %s -> %s (substituído)\n' "$rel" "$dest"
        else
            printf '    + %s -> %s (novo)\n' "$rel" "$dest"
        fi
        if [[ "$mode" == "apply" ]]; then
            snapshot_file "$dest"
            mkdir -p "$(dirname "$dest")"
            cp "$src" "$dest"
        fi
    done < <(find "$FILES_DIR" -type f -print0 | sort -z)
}

# ---------------------------------------------------------------------------
# Esquema de cores (configs/files/data/color-schemes)
# ---------------------------------------------------------------------------

COLOR_SCHEME="AtenaDark"
COLOR_SCHEME_SRC="$FILES_DIR/data/color-schemes/$COLOR_SCHEME.colors"
KDE_GLOBALS="$CONFIG_HOME/kdeglobals"

# O plasma-apply-colorscheme grava o SHA-1 do arquivo em ColorSchemeHash e
# recusa reaplicar um esquema que já está ativo — pelo nome, sem olhar o
# conteúdo. Comparar o hash pega tanto "nunca apliquei" quanto "editei o
# arquivo e não reapliquei".
colorscheme() {
    local mode="$1" want_hash cur_hash active previous
    [[ -f "$COLOR_SCHEME_SRC" ]] || return 0
    if ! have sha1sum; then
        die "sha1sum não encontrado (pacote coreutils); sem ele não dá para conferir o esquema"
    fi

    want_hash="$(sha1sum "$COLOR_SCHEME_SRC" | cut -d' ' -f1)"
    cur_hash="$(current_value "$KDE_GLOBALS" General ColorSchemeHash)"
    active="$(current_value "$KDE_GLOBALS" General ColorScheme)"

    if [[ "$cur_hash" == "$want_hash" && "$active" == "$COLOR_SCHEME" ]]; then
        note "= esquema de cores $COLOR_SCHEME"
        return 0
    fi

    SCHEME_CHANGED=$((SCHEME_CHANGED + 1))
    printf '    ~ esquema de cores -> %s\n' "$COLOR_SCHEME"
    if [[ "$mode" != "apply" ]]; then
        return 0
    fi
    if ! have plasma-apply-colorscheme; then
        die "plasma-apply-colorscheme não encontrado (pacote plasma-workspace)"
    fi

    # Aplicar grava kdeglobals; o snapshot garante que o --uninstall volte ao
    # esquema que existia antes da primeira aplicação.
    snapshot_file "$KDE_GLOBALS"

    previous="$active"
    if [[ "$active" == "$COLOR_SCHEME" ]]; then
        # A ferramenta recusa o nome já ativo; apagar só o nome (as cores ficam)
        # faz ela reaplicar o arquivo atualizado. Se falhar, o nome volta.
        kwriteconfig6 --file "$KDE_GLOBALS" --group General --key ColorScheme --delete
    fi
    if ! plasma-apply-colorscheme "$COLOR_SCHEME" >/dev/null; then
        if [[ "$previous" != "$UNSET_SENTINEL" ]]; then
            kwriteconfig6 --file "$KDE_GLOBALS" --group General --key ColorScheme "$previous"
        fi
        die "plasma-apply-colorscheme falhou ao aplicar $COLOR_SCHEME"
    fi
}

# ---------------------------------------------------------------------------
# Janela de configurações (modulos/settings)
# ---------------------------------------------------------------------------

SETTINGS_DIR="$KDE_DIR/modulos/settings"
SETTINGS_BUILD="$SETTINGS_DIR/build"
SETTINGS_BIN="$HOME/.local/bin/atena-settings"
SETTINGS_LAYOUT="$DATA_HOME/atena-settings/layout.conf"
SETTINGS_DESKTOP="$DATA_HOME/applications/atena-settings.desktop"
# Ícone da entrada e da janela. O `systemsettings` do Breeze é uma placa escura
# com três trilhos azuis: no launcher ele destoa do resto (que é monocromático) e
# é o único ícone "de fora" que ainda vinha do desenho de fábrica. A engrenagem
# com ferramenta do próprio tema diz a mesma coisa, é monocromática e existe em
# qualquer tema que herde Breeze.
SETTINGS_ICON="preferences-system"
# Desvio do binário: as telas do Plasma abrem outra tela chamando
# `systemsettings <kcm_id>` num processo novo. Este script, antes de /usr/bin no
# PATH, manda o pedido para a janela nova.
SETTINGS_SHIM="$HOME/.local/bin/systemsettings"
# Entrada que sombreia a do sistema (mesmo nome do arquivo, em ~/.local):
# o menu passa a abrir a nossa janela em vez do System Settings.
SETTINGS_OVERRIDE="$DATA_HOME/applications/systemsettings.desktop"
SYSTEM_ENTRY="/usr/share/applications/systemsettings.desktop"

# Compilar precisa das ferramentas de desenvolvimento do Qt 6 e do KF6; falhar
# aqui é melhor do que instalar um binário velho sem avisar.
require_build_tools() {
    local tool
    for tool in cmake c++; do
        if ! have "$tool"; then
            die "$tool não encontrado (pacote cmake/gcc); sem ele não dá para compilar a janela"
        fi
    done
    if ! have ninja; then
        warn "ninja ausente: usando o gerador padrão do cmake"
    fi
}

# Compila só quando o binário não existe ou está mais velho que a fonte.
settings_build() {
    local src="$SETTINGS_DIR/src/main.cpp"
    if [[ -x "$SETTINGS_BUILD/atena-settings" ]] && [[ "$SETTINGS_BUILD/atena-settings" -nt "$src" ]]; then
        note "binário já está atualizado"
        return 0
    fi
    require_build_tools
    step "compilando modulos/settings"
    local generator=()
    if have ninja; then
        generator=(-G Ninja)
    fi
    cmake -S "$SETTINGS_DIR" -B "$SETTINGS_BUILD" "${generator[@]}" -DCMAKE_BUILD_TYPE=Release >/dev/null
    cmake --build "$SETTINGS_BUILD"
    ok "compilado"
}

settings_desktop_entry() {
    local nodisplay="$1"
    cat <<EOF
[Desktop Entry]
Type=Application
Name=Configurações do Atena
Comment=As telas de configuração do Plasma, agrupadas por assunto
Exec=$SETTINGS_BIN
Icon=$SETTINGS_ICON
Terminal=false
Categories=Qt;KDE;Settings;
Keywords=settings;configurações;plasma;
StartupNotify=true
NoDisplay=$nodisplay
EOF
}

# A entrada do sistema, com o Exec trocado. Copiar em vez de escrever do zero
# mantém nome traduzido, ícone, categoria, OnlyShowIn e palavras-chave nativas.
# As "Actions" (abrir direto um KCM) saem: elas chamam `systemsettings kcm_*`,
# que abriria a janela antiga no meio da nova.
settings_override_entry() {
    local bin="$SETTINGS_BIN" icon="$SETTINGS_ICON"
    awk -v bin="$bin" -v icon="$icon" '
        BEGIN {
            print "# Gerado por kde/install.sh: desvio temporário do System Settings."
            print "# Desfazer: cd kde && ./install.sh --drop-override"
        }
        /^\[Desktop Action/ { skip = 1; next }
        /^\[/              { skip = 0 }
        skip               { next }
        /^Actions=/        { next }
        # O ícone do tema novo no lugar do genérico: a engrenagem com ferramenta
        # diz "configurações do sistema" e existe em qualquer tema que herde
        # Breeze, então a entrada não fica sem ícone em outro tema.
        /^Icon=/           { print "Icon=" icon; next }
        /^Exec=systemsettings$/ {
            print "Exec=" bin
            print "X-Atena-Override=systemsettings"
            next
        }
        /^Exec=systemsettings / { next }
        { print }
    ' "$SYSTEM_ENTRY"
}

# Instala (ou confere) um arquivo da janela. Tudo passa por snapshot_file, então
# o --uninstall remove o que criamos e restaura o que existia antes.
install_setting_file() {
    local mode="$1" src="$2" dest="$3" label="$4"
    if [[ -f "$dest" ]] && cmp -s "$src" "$dest"; then
        FILES_SAME=$((FILES_SAME + 1))
        # Conteúdo igual não garante permissão igual: o .desktop nasce do mktemp
        # (600) e o menu precisa de leitura.
        if [[ "$dest" == *.desktop && "$mode" == "apply" ]]; then
            chmod 644 "$dest"
        fi
        if [[ "$mode" == "diff" ]]; then
            note "= $label -> $dest"
        fi
        return 0
    fi
    FILES_CHANGED=$((FILES_CHANGED + 1))
    if [[ -f "$dest" ]]; then
        printf '    ~ %s -> %s (substituído)\n' "$label" "$dest"
    else
        printf '    + %s -> %s (novo)\n' "$label" "$dest"
    fi
    if [[ "$mode" == "apply" ]]; then
        snapshot_file "$dest"
        mkdir -p "$(dirname "$dest")"
        # Copiar para um temporário no mesmo diretório e renomear troca o inode:
        # sobrescrever direto falha com ETXTBSY quando o app está aberto
        # ("Área de texto ocupada"), que é justamente o caso de recompilar e
        # reinstalar com a janela em uso.
        local staged
        staged="$(mktemp "$dest.XXXXXX")"
        cp "$src" "$staged"
        case "$dest" in
            *.desktop) chmod 644 "$staged" ;;
        esac
        if [[ "$dest" == "$SETTINGS_BIN" || "$dest" == "$SETTINGS_SHIM" ]]; then
            chmod 755 "$staged"
        fi
        mv -f "$staged" "$dest"
    fi
}

settings_app() {
    local mode="$1" tmp tmp_override nodisplay
    if [[ ! -f "$SETTINGS_DIR/src/main.cpp" ]]; then
        warn "modulos/settings não existe: nada a instalar"
        return 0
    fi

    if [[ "$mode" == "apply" ]]; then
        settings_build
    elif [[ ! -x "$SETTINGS_BUILD/atena-settings" ]]; then
        # Sem binário compilado não há como comparar: registro como diferença,
        # senão o --check diria que está tudo certo com o app nem instalado.
        FILES_CHANGED=$((FILES_CHANGED + 1))
        printf '    + atena-settings (ainda não compilado; rode ./install.sh --app)\n'
        return 0
    fi

    # Com o override ativo o menu já mostra a nossa janela com o nome nativo;
    # uma segunda entrada visível só duplicaria o item. O .desktop continua
    # instalado (oculto) porque o app declara esse id para o Wayland.
    if [[ "$OVERRIDE" == "on" ]]; then
        nodisplay=true
    else
        nodisplay=false
    fi

    tmp="$(mktemp)"
    settings_desktop_entry "$nodisplay" > "$tmp"
    install_setting_file "$mode" "$tmp" "$SETTINGS_DESKTOP" "atena-settings.desktop"

    if [[ "$OVERRIDE" == "on" ]]; then
        if [[ ! -f "$SYSTEM_ENTRY" ]]; then
            warn "$SYSTEM_ENTRY não existe: não dá para sombrear o System Settings"
        else
            tmp_override="$(mktemp)"
            settings_override_entry > "$tmp_override"
            install_setting_file "$mode" "$tmp_override" "$SETTINGS_OVERRIDE" "systemsettings.desktop (override)"
            rm -f "$tmp_override"
        fi
    elif [[ -f "$SETTINGS_OVERRIDE" ]]; then
        FILES_CHANGED=$((FILES_CHANGED + 1))
        printf '    - %s (override removido; volta a abrir o System Settings)\n' "$SETTINGS_OVERRIDE"
        if [[ "$mode" == "apply" ]]; then
            snapshot_file "$SETTINGS_OVERRIDE"
            rm -f "$SETTINGS_OVERRIDE"
        fi
    fi

    install_setting_file "$mode" "$SETTINGS_BUILD/atena-settings" "$SETTINGS_BIN" "atena-settings"
    install_setting_file "$mode" "$SETTINGS_DIR/layout.conf" "$SETTINGS_LAYOUT" "layout.conf"

    # O desvio do binário acompanha o do menu: com --no-override o
    # `systemsettings` do PATH continua sendo o do sistema.
    if [[ "$OVERRIDE" == "on" ]]; then
        install_setting_file "$mode" "$SETTINGS_DIR/systemsettings" "$SETTINGS_SHIM" "systemsettings (desvio)"
    elif [[ -f "$SETTINGS_SHIM" ]]; then
        FILES_CHANGED=$((FILES_CHANGED + 1))
        printf '    - %s (desvio removido; volta a chamar o System Settings)\n' "$SETTINGS_SHIM"
        if [[ "$mode" == "apply" ]]; then
            snapshot_file "$SETTINGS_SHIM"
            rm -f "$SETTINGS_SHIM"
        fi
    fi
    rm -f "$tmp"
}

# ---------------------------------------------------------------------------
# Captura: máquina -> repo
# ---------------------------------------------------------------------------

capture() {
    local src rel dest tmp line file group key value cur
    step "capturando o estado atual para configs/"

    if [[ -d "$FILES_DIR" ]]; then
        while IFS= read -r -d '' src; do
            rel="${src#"$FILES_DIR"/}"
            if ! dest="$(target_path "$rel")"; then
                warn "configs/files/$rel: pasta inicial inválida; ignorado"
                continue
            fi
            if [[ -f "$dest" ]]; then
                cp "$dest" "$src"
                ok "$rel (de $dest)"
            else
                warn "$dest não existe na máquina; mantive o do repo"
            fi
        done < <(find "$FILES_DIR" -type f -print0 | sort -z)
    fi

    tmp="$(mktemp)"
    trap 'rm -f "$tmp"' EXIT
    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ -z "$line" || "${line:0:1}" == "#" ]]; then
            printf '%s\n' "$line" >> "$tmp"
            continue
        fi
        IFS='|' read -r file group key value <<< "$line"
        if [[ -z "${file:-}" || -z "${group:-}" || -z "${key:-}" ]]; then
            printf '%s\n' "$line" >> "$tmp"
            continue
        fi
        cur="$(current_value "$CONFIG_HOME/$file" "$group" "$key")"
        if [[ "$cur" == "$UNSET_SENTINEL" ]]; then
            warn "$file [$group] $key não existe na máquina; mantive $value"
            printf '%s\n' "$line" >> "$tmp"
        elif [[ "$cur" != "$value" ]]; then
            printf '    ~ %s [%s] %s: %s -> %s\n' "$file" "$group" "$key" "$value" "$cur"
            printf '%s|%s|%s|%s\n' "$file" "$group" "$key" "$cur" >> "$tmp"
        else
            printf '%s\n' "$line" >> "$tmp"
        fi
    done < "$KEYS_FILE"
    mv "$tmp" "$KEYS_FILE"
    trap - EXIT
    ok "keys.conf atualizado"
}

# ---------------------------------------------------------------------------
# Desfazer
# ---------------------------------------------------------------------------

uninstall() {
    local snap rel f
    step "restaurando o estado anterior"

    if ! snap="$(oldest_snapshot)"; then
        warn "nenhum backup em $STATE_DIR/backup: nada a restaurar"
        note "o que você ajustou à mão continua como está"
        return 0
    fi
    note "backup: $snap"

    if [[ -d "$snap/files" ]]; then
        while IFS= read -r -d '' f; do
            dest="${f#"$snap/files"}"
            mkdir -p "$(dirname "$dest")"
            cp -p "$f" "$dest"
            ok "restaurado: $dest"
        done < <(find "$snap/files" -type f -print0)
    fi

    # Não chamamos plasma-apply-colorscheme aqui de propósito: o kdeglobals
    # restaurado pode ser um esquema que você customizou à mão. Reaplicar pelo
    # nome trocaria as cores pelo padrão do esquema e apagaria essa customização.
    # O KDE relê o arquivo; se alguma janela não acompanhar, saia e entre na sessão.

    if [[ -f "$snap/created.list" ]]; then
        while IFS= read -r dest; do
            if [[ -z "$dest" || ! -e "$dest" ]]; then
                continue
            fi
            # layout.conf instalado é editável: se você mexeu nele, ele é seu.
            if [[ "$dest" == "$SETTINGS_LAYOUT" ]] && ! cmp -s "$dest" "$SETTINGS_DIR/layout.conf"; then
                warn "$dest foi editado; mantive o seu (a versão do repo está em modulos/settings)"
                continue
            fi
            rm -f "$dest"
            ok "removido (não existia antes): $dest"
        done < "$snap/created.list"
    fi

    if have kbuildsycoca6; then
        kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
    fi

    note "backups preservados em $STATE_DIR/backup (apague você mesmo se quiser)"
    note "feche e abra o Dolphin para ver o efeito"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --diff)      MODE="diff" ;;
            --check)     MODE="check" ;;
            --capture)   MODE="capture" ;;
            --app)       MODE="app" ;;
            --no-override)   OVERRIDE="off" ;;
            --drop-override) OVERRIDE="drop"; MODE="app" ;;
            --uninstall) MODE="uninstall" ;;
            -h|--help)   usage; exit 0 ;;
            *)           die "opção desconhecida: $1 (use --help)" ;;
        esac
        shift
    done

    require_kde_tools

    # --app instala só a janela: é o atalho para iterar no código sem reescrever
    # as configs do Dolphin. O apply normal instala as duas coisas.
    if [[ "$MODE" == "app" ]]; then
        step "instalando a janela de configurações"
        settings_app apply
        note "arquivos: $FILES_CHANGED instalados, $FILES_SAME já iguais"
        if have kbuildsycoca6; then
            kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
        fi
        ok "instalado em $SETTINGS_BIN"
        return 0
    fi

    if [[ "$MODE" == "uninstall" ]]; then
        uninstall
        return 0
    fi

    if [[ "$MODE" == "capture" ]]; then
        capture
        return 0
    fi

    if [[ "$MODE" == "apply" ]]; then
        step "aplicando ajustes do KDE"
        # O Dolphin guarda as configurações em memória e regrava o dolphinrc ao
        # fechar: aplicar com ele aberto é escrever e depois ver a instância
        # antiga apagar por cima.
        if have pgrep && pgrep -x dolphin >/dev/null 2>&1; then
            warn "o Dolphin está aberto: ele regrava o dolphinrc ao fechar e pode desfazer isto"
            note "feche todas as janelas do Dolphin e rode de novo"
        fi
    else
        step "simulação (nada é escrito)"
    fi

    each_file "$MODE"
    each_key "$MODE"
    colorscheme "$MODE"
    settings_app "$MODE"

    printf '\n'
    note "chaves: $CHANGED para ajustar, $SAME já corretas"
    note "arquivos: $FILES_CHANGED para substituir, $FILES_SAME já iguais"
    if [[ $SCHEME_CHANGED -gt 0 ]]; then
        if [[ "$MODE" == "apply" ]]; then
            note "esquema de cores: $COLOR_SCHEME aplicado"
        else
            note "esquema de cores: $COLOR_SCHEME para aplicar"
        fi
    else
        note "esquema de cores: $COLOR_SCHEME já ativo"
    fi

    if [[ "$MODE" == "check" && $((CHANGED + FILES_CHANGED + SCHEME_CHANGED)) -gt 0 ]]; then
        printf '%sfora do esperado: rode ./install.sh para aplicar%s\n' "$C_ERR" "$C_OFF"
        return 1
    fi

    if [[ "$MODE" == "diff" || "$MODE" == "check" ]]; then
        return 0
    fi

    if [[ $((CHANGED + FILES_CHANGED + SCHEME_CHANGED)) -eq 0 ]]; then
        ok "nada a fazer: a máquina já está no estado do repo"
        return 0
    fi

    if have kbuildsycoca6; then
        kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
    fi

    ok "aplicado"
    if [[ -n "$SNAP" ]]; then
        note "backup: $SNAP"
    fi
    note "feche e abra o Dolphin (e os apps abertos, para os diálogos de arquivo)"
    note "a janela de configurações aparece no menu como \"Configurações do Atena\""
    note "para desfazer: ./install.sh --uninstall"
}

main "$@"
