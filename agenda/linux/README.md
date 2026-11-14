# Agenda para Linux (KDE Plasma 6)

App de calendário desktop em **Compose Desktop** (Kotlin/JVM) com banco SQLite local e sincronização opcional com o **Atena Hub**. Inclui um widget de painel do **KDE Plasma 6**: um cartão compacto com a hora em cima e a data embaixo, igual ao layout de referência, que abre e recolhe a janela do app.

> Esta pasta faz parte do monorepo **Atena**. Para a visão geral e os outros projetos, veja o [README principal](../../README.md). O app Android é um fork do Fossify Calendar e vive em [`agenda/android`](../android).

## Requisitos

- JDK 21
- Linux com ambiente gráfico (X11 ou Wayland)
- Para o widget: KDE Plasma 6 (`kpackagetool6`, `qmllint` do Qt 6)

O Gradle Wrapper já vem incluído; não é preciso instalar o Gradle.

## Rodar

```bash
./gradlew :app:run
```

## Testes

```bash
./gradlew test
```

## Empacotar

```bash
# Distribuição com JRE embutido
./gradlew :app:createDistributable
# saída: app/build/compose/binaries/main/app/agenda/

# AppImage portátil
./gradlew :app:packageAppImage
# saída: app/build/compose/binaries/main/app/
```

O formato principal é **AppImage** (funciona em Arch/KDE sem os empacotadores deb/rpm).

## Instalar como widget do KDE Plasma

```bash
./scripts/install.sh             # build + instala app, lançador e plasmoid
./scripts/install.sh uninstall   # remove tudo que foi instalado
```

Não rode com `sudo`: o script instala em `~/.local` do seu usuário e recusa
execução como root (com `sudo`, o `$HOME` vira `/root` e o plasmashell nunca
encontra o widget).

O que é instalado:

| Caminho | Conteúdo |
| --- | --- |
| `~/.local/opt/agenda` | app image com JRE embutido |
| `~/.local/bin/agenda-toggle` | helper para mostrar/esconder a janela e ler o status |
| `~/.local/share/applications/agenda.desktop` | entrada no menu de aplicativos |
| `~/.local/share/icons/hicolor/256x256/apps/agenda.png` | ícone |
| `~/.local/share/plasma/plasmoids/org.agenda.widget` | widget do painel |

Depois de instalar, adicione o widget: botão direito no painel → **Adicionar widgets…** → **Agenda**. Se ele não aparecer na lista:

```bash
systemctl --user restart plasma-plasmashell
```

### O widget

- **Representação compacta** (no painel): cartão escuro com a hora (`hh:mm`) na primeira linha e a data (`dd/MM/yy`) na segunda. Clicar mostra/esconde a janela, posicionada logo abaixo do ícone.
- **Comportamento de popup**: clicar fora da janela a esconde (o app continua rodando, pronto para reabrir). Em X11 a janela só se fecha quando o ponteiro está fora dela, sem clique ou digitação recente, e o foco continua perdido depois de um instante — perder o foco com o mouse ainda sobre a janela (o compositor trocando de janela, uma notificação) não fecha nada. No Wayland (o app roda via XWayland) a posição do ponteiro não é confiável, então a decisão fica só com o foco e com a carência de entrada. Enquanto o editor de evento ou os ajustes estiverem abertos ela não se fecha sozinha, para não perder o que foi digitado. **Ajustes → Janela** tem a opção *Fechar ao clicar fora*, e `Esc` fecha a janela (ou o diálogo aberto). `AGENDA_KEEP_OPEN=1` desliga esse fechamento automático (útil para depuração).
- **Representação completa** (quando solto na área de trabalho): data por extenso, eventos de hoje com horário e botões para abrir o app ou sincronizar.
- **Tooltip**: quantidade de eventos de hoje e o próximo evento.
- A hora é atualizada pelo próprio widget; os eventos vêm do JSON publicado pelo app (`agenda.status.json`), então o widget mostra dados mesmo antes da primeira janela abrir (lista vazia).

O helper `agenda-toggle` aceita:

```bash
agenda-toggle                     # mostra/esconde a janela (ou inicia o app)
agenda-toggle anchor X Y W H      # idem, gravando a posição do ícone no painel
agenda-toggle sync                # pede uma sincronização imediata ao app aberto
agenda-toggle status              # imprime o JSON de status lido pelo widget
agenda-toggle prewarm             # sobe o app escondido, sem abrir janela
```

O ícone do painel chama `prewarm` quando o Plasma carrega: o processo já está
quente e o primeiro clique só mostra a janela (~50 ms), em vez de pagar o start
do JVM/Compose (~2 s). O instalador faz o mesmo ao terminar.

## IA pelo Vicinae

O **Conversar com IA** do [Vicinae](../../search/README.md) tem ferramentas de
agenda que editam o banco do app: listar e ler eventos, criar, editar e apagar.
A janela recarrega ao abrir, então o evento criado na conversa aparece assim que
ela é aberta, e a sincronização leva o evento para os outros dispositivos.

| Ferramenta | O que faz | Aprovação |
| --- | --- | --- |
| `agenda_search` | lista os eventos de um intervalo (com busca por texto) | automática |
| `agenda_read` | lê um evento pelo id | automática |
| `agenda_create` | cria um evento | pedida no chat |
| `agenda_update` | muda título, descrição, local, data ou o dia inteiro | pedida no chat |
| `agenda_delete` | apaga o evento (a exclusão também vai para o hub) | pedida no chat |

O caminho do banco fica em **Vicinae → Ajustes → IA → Agenda** e por padrão é
`~/.local/share/agenda/agenda.db`. As datas que o modelo manda são ISO-8601 no
horário local (`2026-11-14`, `2026-11-14T09:30`); uma data sem hora cria um
evento de dia inteiro, e num evento de vários dias o `end` é o último dia,
inclusive.

## Configuração

| Nome | Padrão | Para que serve |
| --- | --- | --- |
| `AGENDA_BIN` | `~/.local/opt/agenda/bin/agenda` | Binário que o `agenda-toggle` executa; útil para rodar uma build local |
| `AGENDA_KEEP_OPEN` | — | Quando definida (`=1`), a janela nunca se fecha sozinha |
| `AGENDA_START_HIDDEN` | — | Quando definida (`=1`, ou `--hidden`), sobe sem janela: processo quente para o primeiro clique ser imediato |
| `AGENDA_TIMING` | — | Quando definida (`=1`), imprime os marcos do start (ms desde o início da JVM) no stderr |
| `XDG_DATA_HOME` | `~/.local/share` | Onde fica `agenda/agenda.db` (SQLite, WAL) |
| `XDG_CONFIG_HOME` | `~/.config` | Onde ficam `agenda/config.json`, `agenda/notify.json`, `agenda/window.json` e o `ipc-dir` publicado pelo app |
| `XDG_CACHE_HOME` | `~/.cache` | Diretório de runtime alternativo quando não há `XDG_RUNTIME_DIR` |
| `XDG_RUNTIME_DIR` | — | Arquivos de IPC (`agenda.toggle`, `agenda.sync`, `agenda.status.json`, `agenda.anchor`) |

A sincronização é opcional e configurada dentro do app, em **Ajustes → Sincronização**:

| Campo | Exemplo | Observação |
| --- | --- | --- |
| URL do servidor | `http://192.168.0.10:8080` | Endereço do Atena Hub |
| Token | `seu-token` | O hub imprime o token no log ao subir |
| Nome deste computador | `desktop` | Só para identificar o dispositivo no hub |

As credenciais ficam em `~/.config/agenda/config.json` (texto simples, permissão do usuário) — não versione esse arquivo.

## Sincronização

O app é **local-first**: sem servidor configurado, tudo funciona e fica no SQLite local. Com o hub configurado, o `AgendaSyncEngine` envia eventos locais e puxa os remotos em `POST /api/v1/tools/agenda/sync`, usando:

- conflito por **last-write-wins** no `updatedTime` do evento;
- exclusões como tombstones (o hub guarda por 30 dias);
- cursor próprio da Agenda (`agenda_sync_seq` no servidor), separado do cursor das notas.

O formato dos DTOs e as regras de conflito estão em [`server/README.md`](../../server/README.md).

## Notificações

Com o app rodando — inclusive escondido, como o widget e o autostart o deixam —, uma thread do próprio processo (`NotificationService`) olha o dia a cada 30 s e avisa:

- **Resumo de manhã**, no horário configurado (padrão `07:00`), com a lista dos eventos do dia;
- **Antes de cada evento**, `N` minutos antes (padrão `10`), com horário, local e a primeira linha da descrição.

Os dois são configurados em **Ajustes → Notificações**. Antes do resumo, o app sincroniza quando o hub está configurado, então um evento criado no celular entra no resumo do desktop.

O envio usa `notify-send` e, se ele não existir, `gdbus` direto no `org.freedesktop.Notifications` — nenhum dos dois é dependência obrigatória: sem eles o app continua funcionando, só não notifica. O que já foi mostrado fica em `~/.config/agenda/notify.json` (`lastDigestDay` e `sent`), o que evita repetir o resumo do dia ou o aviso de um evento.

O `install.sh` também registra `~/.config/autostart/agenda.desktop`, que executa `agenda-toggle prewarm` no login: o app sobe **sem janela**, só para o processo (e as notificações) já estarem de pé. Remova esse arquivo para não iniciar junto com a sessão.

## Estrutura

```
linux/
├── app/src/main/kotlin/com/openappslabs/agenda/
│   ├── data/       # modelo, SQLite e cliente de sincronização
│   ├── platform/   # XDG, IPC, status do widget e posicionamento da janela
│   └── ui/         # Compose Desktop (tema KDE, mês, eventos, formulários)
├── app/src/test/   # testes de domínio, sync e posicionamento
├── kde/agenda-widget/  # plasmoid: metadata.json + contents/ui/main.qml
└── scripts/        # install.sh e agenda-toggle
```

## Problemas comuns

- **Widget não aparece em “Adicionar widgets…”**: rode `./scripts/install.sh` **sem sudo** (usa `kpackagetool6`) e reinicie o `plasma-plasmashell`. Sob `sudo`, o widget é instalado em `/root/.local` e fica invisível para a sua sessão.
- **Widget mostra “error loading”**: rode `qmllint` no `main.qml`; um tipo de Plasma 5 (`PlasmaCore.IconItem`) no QML quebra o carregamento no Plasma 6.
- **Clicar não abre nada**: rode `~/.local/bin/agenda-toggle status` e `pgrep -a agenda`. Se houver uma instância antiga de outra sessão (teste, outro `HOME`), mate com `pkill -x agenda`: ela segura o lock de instância única e o helper só toca o arquivo de toggle que *ela* observa. O helper só considera “rodando” um processo que use o mesmo diretório de IPC.
- **Segunda janela não abre**: é intencional. O app é single-instance; a segunda execução vira um pedido de mostrar/esconder.
- **Primeiro clique não abre**: era a corrida com o boot. O widget pré-aquece o app no carregamento do Plasma; se o clique chegasse durante esses ~2 s, o processo existia mas ainda não escutava, e o pedido era descartado. O app agora lê o timestamp do `agenda.toggle` antes de subir e abre quando a janela fica pronta. Sair pelo `X` também libera o lock na hora (o teardown do Skiko pode travar e o processo antigo engoliria o clique seguinte).
- **Demora para abrir no primeiro clique**: o start do JVM/Compose leva ~2 s, então o app é mantido quente escondido. Rode `agenda-toggle prewarm` (o widget faz isso quando o Plasma carrega) e confira com `pgrep -a agenda` se o processo já está de pé. Para medir o start, `AGENDA_TIMING=1 ~/.local/opt/agenda/bin/agenda` imprime cada etapa em ms no stderr.
- **Sincronização falha com token recusado**: regerar o token no hub e salvar de novo em **Ajustes → Sincronização**.
- **Nenhuma notificação aparece**: confira `command -v notify-send` e se o servidor responde com `gdbus call --session --dest org.freedesktop.Notifications --object-path /org/freedesktop/Notifications --method org.freedesktop.Notifications.GetServerInformation`. Sem notificação, o próximo ciclo tenta de novo (nada é marcado como enviado). Lembre também de desligar o modo Não Perturbe do Plasma.

## Licença

GPL-3.0-or-later. Veja [LICENSE](LICENSE) e os créditos no [README principal](../../README.md).
