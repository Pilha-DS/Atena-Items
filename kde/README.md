# Atena KDE

Ajustes do KDE Plasma versionados no repo: o que o desktop precisa ter, sem o
padrão verboso de fábrica. Hoje cobre o **esquema de cores** (AtenaDark), os
**ícones**, o **Dolphin** (config aplicada) e a janela de configurações; o mapa
das telas está em [CONFIG-MAP.md](CONFIG-MAP.md) e o que ainda não foi decidido
está lá também.

A ideia não é reescrever o Plasma. É usar a precedência que o próprio KDE já
oferece (`~/.config/*.rc`, `~/.local/share/kxmlgui5/`, `.desktop` com
`NoDisplay=true`) e só criar código quando a config não alcança.

## Requisitos

- Arch Linux com KDE Plasma 6 (`kwin` 6.x, `dolphin` 26.x)
- `kconfig` (`kwriteconfig6`, `kreadconfig6`) e `kservice` (`kbuildsycoca6`)
- `plasma-apply-colorscheme` (`plasma-workspace`) para aplicar o esquema
- Nada de root: tudo é escrito em `~/.config`, `~/.local/share` e
  `~/.local/state`
- Só para a janela de configurações: Qt 6 + KF6 (`kcmutils`, `kcoreaddons`),
  `cmake` e `ninja`

## Usar

```bash
cd kde
./audit.sh                 # inventário: pacotes, applets, KCMs, temas
./install.sh --diff        # simula; mostra chave por chave o que mudaria
./install.sh               # aplica configs + instala a janela de configurações
./install.sh --check       # sai com 1 se algo divergir do repo
./install.sh --app         # só compila e reinstala a janela (sem mexer em config)
./install.sh --capture     # máquina -> repo (depois de ajustar algo à mão)
./install.sh --uninstall   # volta ao estado de antes da primeira aplicação
```

Feche e abra o Dolphin depois de aplicar. Os diálogos de abrir/salvar
(`kdeglobals`) só mudam nos apps abertos depois da aplicação.

## Tema (esquema de cores)

O repo traz o **AtenaDark**, um esquema muito escuro mas que não chega ao preto:
o tom mais escuro é `#07080b` (7,8,11). O conteúdo usa exatamente essa cor;
janela (`#101218`), cabeçalhos (`#13151c`), botões (`#191c24`) e a variante
alternada (`#21242d`) sobem a partir dela, então as superfícies ainda se
distinguem sem sombra. Texto principal em `#e8f0f1` (17,3:1 de contraste);
seleção em `#0d7a8c` com texto branco (5,0:1).

| O que | Onde |
| --- | --- |
| esquema versionado | `configs/files/data/color-schemes/AtenaDark.colors` |
| instalado em | `~/.local/share/color-schemes/AtenaDark.colors` |
| aplicado com | `plasma-apply-colorscheme AtenaDark` (chamado pelo `install.sh`) |

É o esquema da **sessão**, não um estilo só da janela: os KCMs abertos na janela
de configurações leem o esquema do sistema, então só assim todos concordam. O
`install.sh` instala o arquivo, aplica e guarda um backup do `kdeglobals`; o
`--uninstall` volta ao esquema anterior.

Para mudar o tom, edite o `.colors` e rode de novo:

```bash
cd kde
./install.sh --diff     # mostra o que muda
./install.sh            # instala e aplica
```

O `--check` compara o SHA-1 do arquivo com o `ColorSchemeHash` que o Plasma grava
ao aplicar, então pega tanto "não apliquei" quanto "editei o arquivo e não
reapliquei". Como a ferramenta oficial recusa reaplicar um esquema que já está
ativo pelo nome, o `install.sh` limpa só o nome antes de chamá-la. Para voltar
pontualmente a outro esquema, sem desfazer o resto:

```bash
plasma-apply-colorscheme BreezeDark
```

## Ícones

O tema de ícones é da **sessão** (`kdeglobals` → `[Icons] Theme`), não um ajuste
só da janela de configurações: os KCMs desenham os próprios ícones e leriam um
tema diferente do menu, então os dois lados ficam no mesmo tema.

| O que | Onde |
| --- | --- |
| tema aplicado | `Breeze-Noir-White-Blue` (linha `kdeglobals\|Icons\|Theme` em `configs/keys.conf`) |
| de onde vem | `~/.local/share/icons/Breeze-Noir-White-Blue` (instalado à parte) |
| conferir | `cd kde && ./install.sh --diff` |

Por que ele: aproveita as formas do Breeze (as mesmas que os KCMs e os apps
instalados já esperam) recoloridas em branco com um azul de acento. Fica
monocromático onde a cor não informa nada — menu, abas, chips, ícones de app no
lançador — e não inventa desenho novo, então nada briga com o resto da sessão.
Breeze continua como herança, então nome que o tema não tenha em arquivo próprio
continua resolvendo.

O próprio app usa esse conjunto: a entrada do menu, a janela e o
`systemsettings.desktop` desviado apontam para `Icon=preferences-system`, a
engrenagem com ferramenta do tema. Não é o `systemsettings` do Breeze — esse é
uma placa escura com trilhos azuis, o único ícone do launcher que continuava com
o desenho colorido de fábrica e por isso destoava dos demais. Também não é
`systemsettings` o ícone do binário: `main.cpp` pede o mesmo
`preferences-system`, então o launcher e a barra de tarefas mostram o mesmo
desenho.

O instalador **não instala** o tema: se `Breeze-Noir-White-Blue` não estiver em
`~/.local/share/icons` nem em `/usr/share/icons`, ele avisa e mantém o tema
atual, em vez de deixar a sessão com quadrados vazios.

Conferir que todos os ícones do layout existem no tema em uso (precisa de
display; `--icons` confere um candidato antes de trocar):

```bash
cd kde/modulos/settings
./build/atena-settings --check-icons
./build/atena-settings --icons breeze-dark --check-icons
```

Para ver a diferença em cada tela antes de mexer na sessão:

```bash
cd kde/modulos/settings
./build/atena-settings --icons breeze-dark --screenshot /tmp/icones.png
```

Apps já abertos (Dolphin, painel) só trocam de ícone depois de reabrir.

## A janela de configurações

O `install.sh` compila [`modulos/settings`](modulos/settings) e instala:

| O que | Onde |
| --- | --- |
| binário | `~/.local/bin/atena-settings` |
| layout dos grupos/abas | `~/.local/share/atena-settings/layout.conf` |
| entrada própria | `~/.local/share/applications/atena-settings.desktop` |
| override do System Settings | `~/.local/share/applications/systemsettings.desktop` |

Enquanto o desenho do menu não estiver definido, a entrada do System Settings é
sombreada por uma cópia em `~/.local/share/applications/` que só troca o `Exec` e
o `Icon` (`systemsettings`): o menu (e `Meta+I`) abre a janela nova com o nome
nativo, e o ícone do lançador/barra de tarefas segue o tema aplicado em vez do
`preferences-system` de fábrica. A entrada própria fica `NoDisplay=true` para não
duplicar o item no menu.

Desfazer só o desvio, sem tocar nas configs do Dolphin:

```bash
cd kde
./install.sh --drop-override
```

O arquivo gerado tem o marcador `X-Atena-Override=systemsettings`, é listado em
[CONFIG-MAP.md](CONFIG-MAP.md) como pendência e o `--uninstall` também o remove.
As `Actions` da entrada original (abrir um KCM direto pelo botão direito) não
são copiadas: elas chamariam o System Settings antigo.

Para instalar sem mexer no menu, use `./install.sh --no-override`.

Requisitos para compilar: Qt 6, KF6 (`kcmutils`, `kcoreaddons`), `cmake`, `ninja`
e `gcc`. Sem eles o `--app` falha com mensagem, sem instalar binário velho.

## Como a configuração é aplicada

| Pasta/arquivo | Estratégia | Por quê |
| --- | --- | --- |
| `configs/keys.conf` | `kwriteconfig6`, chave a chave | o `.rc` é compartilhado com o KDE e com você |
| `configs/files/config/` | substitui o arquivo inteiro em `~/.config` | arquivos que são 100% nossos |
| `configs/files/data/` | substitui o arquivo inteiro em `~/.local/share` | idem, para recursos instalados por usuário |

`keys.conf` é uma linha por chave: `arquivo | grupo | chave | valor`. Comentário
começa com `#`. O `--apply` só escreve o que está diferente, então rodar duas
vezes não muda nada.

Antes da primeira escrita de cada arquivo, uma cópia vai para
`${XDG_STATE_HOME:-~/.local/state}/atena/kde/backup/<data>/`. O `--uninstall`
restaura o **backup mais antigo** — o estado anterior a qualquer mudança nossa.

## Estrutura

```
kde/
├── audit.sh                 # inventário da máquina (somente leitura)
├── install.sh               # aplica/confere/captura/desfaz e instala a janela
├── core/common.sh           # caminhos, cores e checagens compartilhadas
├── configs/
│   ├── keys.conf            # chaves aplicadas com kwriteconfig6
│   └── files/               # arquivos substituídos por inteiro
│       ├── config/          #   caem em ~/.config
│       └── data/            #   caem em ~/.local/share
│           ├── color-schemes/AtenaDark.colors
│           └── kxmlgui5/dolphin/dolphinui.rc
├── modulos/                 # código nosso que estende o KDE (ver modulos/README.md)
│   └── settings/            # janela única de configurações (layout em layout.conf)
└── CONFIG-MAP.md            # mapa das telas de configuração e o que falta decidir```

## O que já mudou no Dolphin
- Barra de ferramentas só com voltar, avançar, migalhas, busca e menu hambúrguer
  (`dolphinui.rc`); o resto continua nos menus e nos atalhos.
- Sem barra de filtro, barra de status, slider de zoom e dicas.
- Sem checkbox de seleção nem barra de colar (coisas de toque).
- Abas só quando há mais de uma; sem split view; painéis travados.
- Diálogos de abrir/salvar sem prévia e sem atalhos laterais.

## Configurações

A tela de configurações do Plasma ganhou uma janela própria:
[`modulos/settings`](modulos/settings) agrupa os 73 KCMs da máquina em 6 grupos
com abas — e sub-abas quando a aba junta mais de um KCM —, em vez de 73 telas
soltas. O agrupamento mora em `layout.conf`, não no código — o mapa das decisões
está em [CONFIG-MAP.md](CONFIG-MAP.md).

```bash
cd kde
./install.sh --app              # compila e instala (menu: "Configurações do Atena")
./install.sh                    # aplica as configs e instala a janela
```

Para trabalhar no código sem instalar, rode direto do build:

```bash
cd kde/modulos/settings
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
./build/atena-settings          # abre a janela
./build/atena-settings --check  # falha se algum KCM da máquina ficou fora
```

## Limitações conhecidas

- **Painéis do Dolphin** (Information `F11`, Places `F9`) não têm chave de
  config: o estado mora num blob do `~/.local/state/dolphinstaterc`. Se quiser
  esconder, aperte `F11`/`F9` uma vez e rode `./install.sh --capture`.
- O `dolphinui.rc` é copiado do Dolphin **26.08.2**. Se o upstream mudar a
  versão do `gui`, o arquivo do usuário é ignorado até ser atualizado; rode
  `./install.sh --diff` depois de atualizar o sistema.
- O projeto mexe no **esquema de cores** (AtenaDark), mas não em painel, estilo
  de widgets, decoração de janela, ícones nem efeitos do Plasma — isso continua
  no `CONFIG-MAP.md`.
- O `modulos/settings` é protótipo: não tem `install.sh` próprio, não tem busca
  e ainda não substitui o System Settings.

## Problemas comuns

- **Toolbar sumiu ou ficou estranha**: `./install.sh --uninstall` e reabra o Dolphin.
- **As cores não voltaram depois do `--uninstall`**: o `kdeglobals` foi
  restaurado, mas janelas abertas podem manter a paleta antiga. Saia e entre na
  sessão; nada é reaplicado por cima do seu esquema (era o que apagaria cores
  que você tinha ajustado à mão).
- **Mudança não apareceu**: o Dolphin estava aberto; feche todas as janelas dele
  (ou `killall dolphin`), rode `./install.sh` de novo e abra.
- **`--check` acusa diferença depois de usar o Dolphin**: o próprio Dolphin
  reescreve `dolphinrc`; rode `--diff` para ver se é uma chave nossa ou algo que
  ele adicionou.
- **Aba de KCM vazia, só com o título** (papel de parede, animação, tela):
  sintoma de `QQuickWidget` sem contexto OpenGL compartilhado. A janela pede
  `Qt::AA_ShareOpenGLContexts` antes do `QApplication` justamente por isso;
  se a linha sumir numa recompilação, essas abas voltam a ficar em branco mesmo
  carregando. Detalhe em
  [modulos/settings/README.md](modulos/settings/README.md#decisões-de-api).

## Licença

GNU GPL v3.0, a mesma do monorepo — veja [../LICENSE](../LICENSE).
