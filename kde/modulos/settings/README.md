# atena-settings

Uma janela para as telas de configuração do Plasma, com o agrupamento que a
gente definiu em vez do agrupamento por plugin do System Settings. **Não
reimplementa nenhum ajuste**: carrega os KCMs nativos da máquina.

O que esta janela resolve é a navegação. Hoje o Plasma tem 73 telas soltas, com
assuntos que se repetem em lugares diferentes (luz noturna em dois KCMs,
gerenciamento de janelas em oito). Aqui elas viram 6 grupos, com 35 abas — e,
quando a aba junta mais de um KCM, sub-abas (um chip por tela).

## Requisitos

- Arch Linux com KDE Plasma 6 e os KCMs instalados (`plasma-desktop`,
  `kwin`, `dolphin`)
- Qt 6 e KF6: `kcmutils`, `kcoreaddons`
- `cmake` e `ninja`

## Build e execução

```bash
cd kde/modulos/settings
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build

./build/atena-settings                # abre a janela
./build/atena-settings --list         # mostra o layout e sai
./build/atena-settings --check        # confere o layout contra a máquina
./build/atena-settings --check-icons  # confere se o tema de ícones tem todos os ícones
./build/atena-settings --icons breeze-dark   # prévia de outro tema, só nesta janela
./build/atena-settings --measure 5/3     # tempo até aparecer e até carregar a tela
./build/atena-settings --measure 5/3/1   # a mesma medida, na primeira sub-aba
./build/atena-settings --select 5/3 --screenshot /tmp/aba.png  # captura a aba
./build/atena-settings --select 5/3/2 --screenshot /tmp/sub.png # captura a sub-aba
./build/atena-settings --select 5/3 --size 900x620 --screenshot /tmp/estreita.png  # janela estreita
./build/atena-settings --select 5/2 --hold      # abre a aba e deixa a janela aberta
```

`--select`, `--measure` e `--screenshot` aceitam `grupo/aba`, `grupo/aba/sub`
(contando de 1) ou os nomes (`"Tela/Cores e temas/Cores"`).

`--list` e `--check` não abrem janela, então rodam por SSH ou sem `DISPLAY`.
`--check-icons` e `--measure` também não abrem janela, mas precisam de um display
de verdade (Wayland/X11): `QIcon` não resolve nada sem um, e o KCM de papel de
parede aborta no backend `offscreen`.
`--screenshot` abre a janela, espera os KCMs da aba terminarem de carregar (até
30 s) e salva o PNG; é como conferir cor, espaçamento e alinhamento sem depender
de captura do compositor. `--hold` é o mesmo caminho sem o PNG: monta a aba e
deixa a janela aberta, para olhar no desktop.

Para instalar no sistema (menu, `~/.local/bin`), use o instalador do projeto:

```bash
cd kde
./install.sh --app      # compila e instala só a janela
./install.sh            # aplica as configs do Dolphin e instala a janela
```

## Tema e cores

A janela **não define paleta própria**: herda o esquema de cores do KDE, que no
repo é o `AtenaDark` (veja [Tema](../../README.md#tema-esquema-de-cores)). Isso
não é preguiça: os KCMs carregados — QML inclusive — leem o esquema do sistema,
não o `QPalette` da janela. Forçar cores no host deixaria as abas com cores
diferentes do menu e do resto do desktop.

Aberto por um shell sem `QT_QPA_PLATFORMTHEME`, o Qt ignora o `kdeglobals` e cai
no cinza padrão do Fusion. Por isso o `main()` pede o plugin `kde` antes de criar
o `QApplication` (só quando a variável está vazia e o plugin existe).

## Como o layout é definido

Tudo que a janela mostra vem de [`layout.conf`](layout.conf), não do código.
O menu da esquerda é cada `[grupo]`; as abas de cima são as linhas sem
indentação; cada linha indentada é um KCM. Aba com mais de um KCM ganha um
terceiro nível: os chips abaixo das abas.

```ini
[Tela] @ preferences-desktop-display
Tela e monitor = kcm_kscreen

[Entrada e saída] @ preferences-desktop-peripherals
Mouse e touchpad @ input-mouse
    Mouse = kcm_mouse
    Touchpad = kcm_touchpad
```

Regras do formato:

| Formato | Significado |
| --- | --- |
| `[Nome]` | abre um grupo (entrada no menu da esquerda) |
| `Aba` ou `Aba = kcm_id` | abre uma aba; a forma com `=` já traz o único KCM |
| `  Rótulo = kcm_id` | KCM dentro da aba aberta acima |
| `… @ icone` | ícone de tema da linha (grupo, aba ou KCM) |
| `# …` | comentário |

Uma aba que junta dois ou mais KCMs vira **sub-abas**: cada KCM ganha um chip
abaixo das abas e a tela mostra um de cada vez. Empilhar assuntos diferentes na
mesma página foi justamente o que a janela veio resolver.

Sobre os ícones: são nomes do tema (`folder`, `input-mouse`), não caminhos, e
`QIcon::fromTheme` resolve pelo tema ativo. A janela preenche tema e caminhos de
busca quando o ambiente não traz nenhum (caso de abrir por um shell sem
`QT_QPA_PLATFORMTHEME`), então o mesmo layout funciona dentro e fora do Plasma.
O tema vem do desktop (`[Icons] Theme` do `kdeglobals`, aplicado pelo
`install.sh` do `kde/`), porque os KCMs desenham os próprios ícones: trocar só
nesta janela deixaria a tela em desacordo com o resto da sessão. Para comparar
candidatos sem mexer no desktop, `--icons TEMA` vale só para esta janela.
Só é preciso escrever o `@` quando não há como herdar:

- **grupo** sempre precisa (não existe KCM de onde emprestar);
- **aba com um único KCM** herda o ícone do plugin, então não precisa;
- **aba com vários KCMs** precisa, porque não existe "ícone da aba";
- **KCM** herda o ícone que o plugin declara — o `@` serve para discordar dele
  (foi assim que saíram um relógio em "Arquivos recentes" e um ícone de idioma
  em "Renderizador do Plasma") e para não repetir o mesmo desenho de outro chip
  da mesma aba: "Gerenciamento de janelas" tinha três telas com o mesmo ícone.

`--list` mostra o ícone em uso de cada linha e quantas ficaram sem nenhum —
bom para achar linha nova esquecida. Nome que o tema não tem não aparece no
`--list`; para isso existe o `--check-icons`, que falha listando os nomes sem
arquivo no tema em uso (com `--icons TEMA`, confere o candidato).

Um id só aparece na janela se estiver no arquivo. Por isso o `--check` compara
os dois lados e falha em três casos:

- id no layout que não existe nesta máquina (`ausente`);
- id repetido em duas abas (`duplicado`);
- KCM instalado que não está no layout (`fora`) — é o mais importante, porque
  um KCM fora do layout desaparece da janela sem ninguém ter decidido isso.

Para encontrar o arquivo de layout em uso, rode `--check`: a primeira linha da
saída mostra o caminho. A busca é: `--layout ARQUIVO`, depois ao lado do
executável, depois `../layout.conf` (o diretório de build), depois
`~/.local/share/atena-settings/layout.conf`.

## Estrutura

```
settings/
├── layout.conf      # grupos, abas e KCMs (é aqui que se mexe)
├── src/main.cpp     # host: menu, abas, sub-abas, carregamento e aplicar
└── CMakeLists.txt
```

## Como a janela funciona

- **Coluna única com largura máxima** (980 px, centralizada): com a janela
  maximizada, um formulário esticado separa o rótulo do controle. A coluna
  também define o respiro da página (12 px nas laterais e 16 px embaixo).
- **A tela é o KCM**, sem cartão em volta. Um KCM já pinta o próprio fundo
  (`Base`) e, quando é QML, já traz a própria área de rolagem; embrulhá-lo num
  contorno arredondado criava uma terceira moldura e uma tela parecia três
  caixas uma dentro da outra. A separação agora é só dos chips, e cada KCM ocupa
  a área de conteúdo inteira (esticado, não flutuando no topo).
- **Sub-abas (terceiro nível)**: quando a aba tem mais de um KCM, cada um vira
  um chip (ícone + rótulo) numa fileira abaixo das abas, e a página mostra um
  KCM por vez em vez de empilhar os dois formulários. Chip é `QAbstractButton`
  desenhado pelo `QPalette` (não é um segundo `QTabBar`): um segundo jogo de
  abas nativas faz o usuário perder onde está. A fileira quebra em mais linhas
  quando a janela é estreita — "Cores e temas" tem nove chips.
- **Fios de 1 px** fecham a fileira de abas e o rodapé: navegação e ações são
  moldura, não conteúdo.
- **Esqueleto durante o carregamento**: cada KCM começa como um bloco
  arredondado translúcido com "Carregando…", no lugar de uma linha de texto. Se
  o plugin falhar, o mesmo bloco mostra o erro na cor `NegativeText` do esquema.
- **Carregamento preguiçoso**: a aba só monta quando é aberta, e monta apenas a
  sub-aba visível — os outros KCMs esperam o clique no chip (nada de montar os
  nove de "Cores e temas" para mostrar um). Montar as 73 telas de uma vez seria
  lento e frágil.
- **Carregamento progressivo dentro da aba**: abrir a aba monta o esqueleto
  (um "Carregando…" por tela) e devolve o clique em poucos milissegundos; o
  KCM da sub-aba visível entra num ciclo do event loop. Medido com `--measure`
  em "Cores e temas": aba visível em 2 ms, tela carregada em ~280 ms. Em
  `--select` (teste) todos os KCMs da aba são montados, para o teste de fumaça
  cobrir todas as telas.
- **Pausa ao sair**: se você troca de aba antes de ela terminar, o
  carregamento para ali e continua de onde estava quando você volta. Nenhum
  KCM de aba que você não está vendo consome CPU.
- **Ícones**: menu da esquerda, abas de cima e chips usam o ícone do tema
  definido no `layout.conf` (ou o do plugin, quando o layout não diz nada). O
  rodapé usa `edit-undo` em "Restaurar padrões" e `dialog-ok-apply` em
  "Aplicar".
- **Aplicar** salva todos os módulos com `needsSave()`, inclusive os de abas que
  você já visitou — não só a aba visível.
- **Restaurar padrões** carrega os padrões da aba visível — na sub-aba aberta,
  quando a aba tem várias — e fica desabilitado enquanto ela ainda está
  carregando (senão metade dos KCMs ficaria de fora).
- As abas já montadas continuam em memória ao trocar de grupo; voltar para elas
  é instantâneo.

## Decisões de API

- `KCModuleProxy` **não existe no KF6** (era KF5). O host usa
  `KCModuleLoader::loadModule()` + `KCModule::widget()`, que é o que permite
  vários módulos na mesma página e funciona para KCM QML e QWidget.
- `KCMultiDialog` aceita **um** KCM por página, então não serve para agrupar.
- `Qt::AA_ShareOpenGLContexts` é pedido antes do `QApplication`. Os KCMs QML
  (papel de parede, animação, tela) chegam como `QQuickWidget`; com o tema de
  plataforma do KDE ativo, o Qt abre um segundo contexto OpenGL e, sem
  compartilhamento, a superfície QML nunca chega à janela — a página fica em
  branco, embora o módulo renderize no próprio framebuffer (visível em
  `grabFramebuffer()`, não na tela). Sem o tema de plataforma o problema não
  aparece, o que faz o bug parecer intermitente.
- Ler **as duas** pastas de plugins é obrigatório:
  `plasma/kcms/systemsettings` (QML) e `plasma/kcms/systemsettings_qwidgets`
  (QWidget). Ler só a primeira esconde metade dos módulos do KWin.
- O parent passado a `loadModule()` é `nullptr` de propósito: KCMs QWidget
  instalam o próprio layout no widget recebido, e isso colide com o layout do
  nosso container (`QLayout: … already has a layout`). O widget é reparentado
  ao entrar no layout.
- O host usa `defaults()`, não `loadDefaults()`: no KF6 o método do
  `KCModule`/`KAbstractConfigModule` é `defaults()`.

## Limitações conhecidas

- Não tem busca ainda — o desenho do menu ainda vai ser definido.
- O comando `systemsettings` continua abrindo o app original: o override do
  `kde/install.sh` age só na entrada do menu. Ver "Pendências" no
  [CONFIG-MAP.md](../../CONFIG-MAP.md).
- **"Permissões de aplicativos"** só existe com o pacote `flatpak-kcm`, que não
  está instalado nesta máquina.
- **"Armazenamento"** não tem KCM no Plasma; a aba aponta para `kcm_desktoppaths`
  (pastas de Desktop, Documentos, Downloads).
- O KCM "Configurações rápidas" (`kcm_landingpage`) é a grade de tiles do Plasma.
  Os tiles navegam pelo System Settings original; dentro desta janela o clique
  não troca de aba.
- Ao abrir algumas abas o stderr recebe mensagens que **não são nossas**:
  `KPackageStructure … "KWin/Script"` (kcm_kwin_effects),
  `OverlaySheet: Binding loop` (kcm_pulseaudio), `TypeError` em
  `kcm_kscreen/Panel.qml` e `qt.svg: Invalid path data` (tema instalado).
  O `systemsettings` original emite as mesmas.

## Licença

GNU GPL v3.0, a mesma do monorepo — veja [../../../LICENSE](../../../LICENSE).
