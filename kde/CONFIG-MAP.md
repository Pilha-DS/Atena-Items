# Mapa das configurações do KDE

Objetivo: **uma tela por assunto**, não uma tela por plugin. Hoje o Plasma
instala 73 KCMs nesta máquina e vários deles configuram a mesma coisa em lugares
diferentes.

Regra do projeto: **nada é escondido, fundido ou removido sem aprovação
explícita.** A janela nova começa vazia e só recebe o que você autorizar; até
lá o System Settings original continua sendo o caminho.

## Como vamos decidir cada item

Da solução mais barata para a mais cara — só desce um degrau se o anterior não der conta:

1. **Chave de configuração** (`kwriteconfig6`) — o que dá para ajustar sem tocar em nada.
2. **Override de recurso** (`.desktop` com `NoDisplay=true`, `kxmlgui5/*.rc`,
   categorias em `~/.local/share/systemsettings/`) — muda a UI instalada sem código.
3. **Código nosso leve** (QML/C++ em `../modulos/`) — janela hospedeira que carrega
   os KCMs nativos (`KCModuleLoader` + `KCModule`; o `KCModuleProxy` do KF5 não
   existe mais e o `KCMultiDialog` só aceita um KCM por página). Não reimplementa
   configuração.
4. **Fork/patch** — só com uma parede comprovada, e sincronizar com upstream entra
   no custo.

## Domínios

Nada é escondido, fundido ou removido sem aprovação explícita. O status abaixo
reflete o que já está no código.

| Grupo | Abas | KCMs | Status |
| --- | --- | --- | --- |
| Configurações rápidas | 1 | 1 | como hoje (`kcm_landingpage`) |
| Básico | 9 | 18 | definido |
| Avançado | 6 | 14 | definido |
| Entrada e saída | 4 | 11 | definido |
| Tela | 7 | 16 | definido |
| Outros | 8 | 13 | definido |
| **Total** | **35** | **73** | `--check` fecha em 73/73 |

O agrupamento vive em [`modulos/settings/layout.conf`](modulos/settings/layout.conf),
não no código. Mudar de grupo é editar uma linha.

## Extras que eu encaixei

Itens do seu mapa que não têm tela própria no Plasma, e KCMs que existiam mas
não estavam no seu mapa. Todos estão **visíveis**, nada foi tirado; cada um
espera seu "fica" ou "sai".

| Item | O que fiz | Por quê |
| --- | --- | --- |
| Armazenamento | aponta para `kcm_desktoppaths` (Locations) | não existe KCM de armazenamento; este define as pastas pessoais |
| Permissões de aplicativos | não existe | vem do pacote `flatpak-kcm`, não instalado |
| Cores e temas | virou aba com 9 KCMs (tema global, cores, estilo do Plasma, estilo de apps, decoração, ícones, cursores, splash, plymouth) | é o mesmo assunto em 9 telas |
| Gerenciamento de janelas | virou aba com 8 KCMs (comportamento, regras, Alt+Tab, cantos, áreas virtuais, efeitos, scripts, X11) | idem |
| Outros dispositivos | aba em Entrada e saída: mesa digitalizadora, controle, touchscreen, gestos de toque | são dispositivos de entrada sem lugar no seu mapa |
| Aplicativos e sistema | aba em Outros: Dolphin (2 KCMs), serviços em segundo plano, renderizador do Plasma | configuração de app/serviço, sem lugar no seu mapa |
| Rede móvel (4 KCMs) | dentro de Wi-Fi e internet | sem uso num desktop; candidatos fortes a sair |
| Ciclo dia e noite | aba com `kcm_nighttime` + `kcm_nightlight` | mesma função de cor por horário, em dois KCMs |
| Aplicativo padrão | aba com `kcm_componentchooser` + `kcm_filetypes` | "o que abre o quê" é a mesma decisão |

## Como dizer o que tirar

Cada linha do `layout.conf` é uma decisão. Para remover uma tela da janela,
apague a linha e rode `./build/atena-settings --check`: ele acusa o KCM como
`fora` do layout, o que registra que a saída foi intencional. Pacote e System
Settings original ficam intactos.

## Candidatos a pacote (com ganho medido)

Só depois do seu "sim", item por item.

| Pacote | Ganho | Quebra se remover |
| --- | --- | --- |
| pacotes `-debug` de builds AUR | ~2 GB | nada em runtime |
| `knighttime` | 1 KCM duplicado (`kcm_nighttime`) | a tela Day-Night Cycle |
| `kdeplasma-addons` | `qt6-quick3d`, vários applets | widgets extras (nenhum no painel) |
| `kaccounts-integration` | `qt6-webengine` (283 MB) | `purpose` → `spectacle` |

Wallpaper Engine **fica**: é escolha estética, não excesso de configuração.

## Decisões registradas

- **Tema**: esquema de cores próprio `AtenaDark`, base `#07080b` — muito escuro,
  sem chegar ao preto. Fica em `configs/files/data/color-schemes/` e é aplicado
  com `plasma-apply-colorscheme` pelo `install.sh`. É esquema de **sessão**, não
  um estilo só da janela: os KCMs (QML inclusive) leem o esquema do sistema, e
  forçar paleta na janela deixaria as abas com cores diferentes do resto.
  Painel, estilo de widgets, decoração e efeitos continuam fora.
- **Ícones**: tema de sessão `Breeze-Noir-White-Blue` (`kdeglobals` → `[Icons] Theme` em
  `configs/keys.conf`). Formas do Breeze recoloridas em branco + um azul de acento:
  monocromático onde a cor não informa nada, sem desenho novo que brigue com o resto
  da sessão. O instalador só aplica se o tema estiver instalado; senão avisa e mantém
  o atual. Breeze continua como herança, então nome ausente no tema não vira quadrado
  vazio. O app (menu, janela e override do System Settings) usa `systemsettings` no
  lugar do `preferences-system` de fábrica. Trocar o tema é uma linha em `keys.conf`;
  ver [README.md](README.md#ícones).
- **Painel/desktop**: fora de escopo por enquanto; será ajustado à mão e depois
  capturado com `./install.sh --capture`.
- **Dolphin**: config + `dolphinui.rc` primeiro. Fork só se travar em algo concreto.
- **Configurações**: uma janela só, com menu à esquerda (grupos), abas em cima
  e, quando a aba junta mais de um KCM, chips de sub-aba (terceiro nível). O
  agrupamento foi definido à mão e mora em `modulos/settings/layout.conf`; o host
  só hospeda os KCMs nativos, não reimplementa ajuste. Total: 6 grupos, 35 abas,
  73 KCMs.
- **Instalação**: `./install.sh --app` compila e instala em `~/.local/bin` com
  entrada de menu. Enquanto o visual do menu não estiver definido, a entrada do
  System Settings é **sombreada** (ver pendências abaixo).
- **Desenho do menu**: ainda em aberto. Por enquanto menu + abas, sem busca e
  sem estilo próprio; a aparência será definida depois.

## Pendências (remover quando o visual estiver pronto)

| O que | Onde | Como desfazer |
| --- | --- | --- |
| Override do System Settings: `~/.local/share/applications/systemsettings.desktop` aponta para a janela nova, então o menu (e `Meta+I`) abre o Atena | instalado pelo `install.sh --app`; marcado com `X-Atena-Override=systemsettings` | `cd kde && ./install.sh --drop-override`, ou `--uninstall` |
| Desvio do binário: `~/.local/bin/systemsettings` chama `atena-settings <kcm_id>`; é o que faz o "Mais opções de efeitos" e outras telas que redirecionam abrirem na janela nova em vez do System Settings | instalado pelo `install.sh --app` (sem `--no-override`) | `cd kde && ./install.sh --drop-override`, ou `--uninstall` |

O motivo do desvio é testar a janela no uso real antes de decidir o desenho do
menu. Ele não altera o pacote `plasma-desktop`: a entrada original continua em
`/usr/share/applications/` e volta a valer assim que o arquivo do usuário sai.
O desvio do binário fica em `~/.local/bin`, que vem antes de `/usr/bin` no PATH
do shell; dentro da própria janela ele é forçado para os processos filhos, que
são os que chamam `systemsettings`. Opções do binário original (`--list`,
`--help`) continuam indo para o `/usr/bin/systemsettings`.
As `Actions` da entrada original (abrir um KCM direto com o botão direito) foram
removidas do override, porque chamariam o System Settings antigo.
- **Pacotes**: remoção item a item, com aprovação prévia.
