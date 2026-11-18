# Atena

Monorepo dos meus projetos de desktop e Android. Cada pasta é um **projeto
independente**, com seu próprio build — dá para compilar, versionar e instalar
qualquer um deles sem tocar nos outros.

| Pasta | Projeto | O que é | Stack |
| --- | --- | --- | --- |
| [`agenda/`](agenda) | **Agenda** | Calendário (fork do [Fossify Calendar](https://github.com/FossifyOrg/Calendar)) com port Linux e widget do KDE | Kotlin, Jetpack Compose, Compose Desktop |
| [`jotter/`](jotter) | **Jotter / Atena Notes** | App de notas offline-first (Android + port Linux com widget do KDE) | Kotlin, Jetpack Compose, Room, Compose Desktop |
| [`docs/`](docs) | **Docs** | Biblioteca de documentos sobre a árvore real de arquivos — busca no nome e no conteúdo (OCR), etiquetas hierárquicas, recentes, favoritos e pastas (inclusive as vazias), importar arquivos do PC para o `init`, criar pastas e subpastas pela janela, pasta sugerida por IA (Ollama local ou DeepSeek; o movimento só acontece com confirmação) e a caixa de entrada `init/`, sempre criada e recriada, onde a IA sugere pasta e etiqueta e cria só o que você confirmar | Kotlin, Compose Desktop, Room |
| [`atena-sync/`](atena-sync) | **Atena Sync** | Daemon local: ranking de conexões, sync LAN (mDNS) e fallback para o hub; apps falam em `127.0.0.1:18765` | Kotlin, Ktor, SQLite |
| [`atena-hub/`](atena-hub) | **Atena Hub** | Fallback de internet: sync das notas + armazenamento universal + admin (`/admin`) | Kotlin, Ktor, SQLite, Docker |
| [`vicinae/`](vicinae) | **@Search** | Launcher de desktop (fork do [Vicinae](https://vicinae.com)) | C++23, Qt 6, QML |
| [`kde/`](kde) | **Atena KDE** | Ajustes versionados do Plasma/Dolphin (esquema de cores AtenaDark) e janela de configurações instalável | Bash, config do KDE, C++/Qt 6 |

Os apps são **local-first**. O ponto preferencial de sync é o **Atena Sync**
(LAN entre aparelhos); o **Atena Hub** fica como fallback de internet. Outras
ferramentas podem ler notas por `/api/v1/tools/jotter/notes` no hub.

```
agenda/
├── android/                # app Android (fork do Fossify, Gradle próprio)
└── linux-arch-kde/         # port desktop + widget Plasma 6 (Gradle próprio)
jotter/
├── Android/                # app Android (Gradle próprio)
└── linux-arch-kde/         # port desktop + widget Plasma 6 (Gradle próprio)
docs/                       # biblioteca de documentos (Gradle próprio)
atena-sync/                 # daemon local LAN + fallback hub
atena-hub/                  # hub de internet (Gradle + Docker)
vicinae/                    # fork do Vicinae, build CMake/Ninja
kde/                        # configs do Plasma/Dolphin + janela de configurações
scripts/                    # instalador Linux e assinatura dos APKs
LICENSE                     # GNU GPL v3.0
```

## Requisitos

| Projeto | Para buildar |
| --- | --- |
| `agenda/android` | JDK 17+ (recomendado 21), Android SDK API 36 |
| `agenda/linux-arch-kde` | JDK 21, KDE Plasma 6 (só para o widget) |
| `jotter/Android` | JDK 17+ (recomendado 21), Android SDK API 36 |
| `jotter/linux-arch-kde` | JDK 21, KDE Plasma 6 + KWin (só para o widget) |
| `atena-sync` | JDK 21 |
| `atena-sync/android` | JDK 17+, Android SDK API 35 |
| `docs` | JDK 21, KDE Plasma 6 (só para o tema seguir a paleta); `kdialog` é opcional e dá o seletor de arquivos nativo do KDE |
| `server` | JDK 21 **ou** Docker + Docker Compose |
| `search` | Qt 6, CMake, Ninja, `wayland-protocols`, C++23 (o fork é compilado do código) |
| `kde` | Bash (configs do KDE); Qt 6 + KF6 + CMake/Ninja para a janela de configurações |

Os projetos Gradle trazem o Wrapper (`./gradlew`), então não é preciso instalar
o Gradle.

## 🐧 Instalação no Linux (Arch + KDE Plasma 6)

Um comando instala tudo na máquina — apps, lançadores, autostart e os widgets do
Plasma:

```bash
./scripts/install-kde-arch.sh              # sync + notas + agenda + @Search
./scripts/install-kde-arch.sh --hub        # inclui o Atena Hub (docker compose)
./scripts/install-kde-arch.sh --check      # confere o ambiente, não instala nada
./scripts/install-kde-arch.sh --only sync
./scripts/install-kde-arch.sh --only notes
./scripts/install-kde-arch.sh --only docs
./scripts/install-kde-arch.sh --uninstall  # remove (preserva seus dados)
```

Ele confere distro e Plasma, instala via `pacman` só o que falta (JDK 21, docker,
toolchain e libs Qt do `@Search`, `qt6-tools`…), garante `~/.local/bin` no PATH
da sessão gráfica, chama o `install.sh` de cada projeto, **registra os widgets sem
colocar nada no painel** e verifica o resultado artefato por artefato.

O `@Search` é compilado do nosso fork (`search*/scripts/build-and-install.sh`),
não baixado do release oficial — senão as mudanças do fork nunca chegariam na
máquina instalada.

A busca do `@Search` é por níveis: primeiro **apps/comandos**, depois **arquivos**
pelo índice, que tem **5 s** para responder. Só quando nenhum arquivo é achado ele
cai para **palavras-chave em texto e PDFs** (`rg`, `pdftotext`) e, por fim,
**palavras-chave em imagens** (OCR com `tesseract`) — os dois níveis juntos têm no
máximo **10 s**. As palavras-chave só começam depois que você para de digitar,
fechar a janela ou trocar o texto cancela o nível em andamento, e os resultados
aparecem conforme chegam.

Na barra de busca dá para **arrastar ou colar imagens, PDFs e arquivos de texto**:
eles aparecem abaixo da consulta e seguem **junto com o texto** para a IA quando
você aperta **Tab** (Ask AI). O PDF é convertido em texto com o `pdftotext`, então
até modelos sem visão conseguem ler o conteúdo.

Depois de instalar: **Super+N** abre o Notes, **Super+C** abre a Agenda,
**Super+Shift+D** abre o Docs, e os widgets aparecem em *botão direito no
painel → Adicionar widgets…*.

O que precisa de root (uinput/`setcap` para colar e snippets no `@Search`, grupo
`docker`) é listado no fim como pendência explícita — o script não roda `sudo`
escondido.

> Mudou o que um projeto instala ou exige? A regra
> [`.cursor/rules/bash-installer.mdc`](.cursor/rules/bash-installer.mdc) tem o
> checklist do que atualizar no instalador.

## Começando

```bash
# agenda — Android
cd agenda/android && ./gradlew :app:assembleDebug

# agenda — desktop Linux (+ widget do painel)
cd agenda/linux-arch-kde && ./gradlew :app:run

# notas — Android
cd jotter/Android && ./gradlew :app:assembleDebug

# notas — desktop Linux (+ widget do painel)
cd jotter/linux-arch-kde && ./gradlew :app:run

# docs — desktop Linux
cd docs && ./gradlew :app:run

# sync local (LAN)
cd atena-sync && ./install.sh

# hub (fallback internet)
cd atena-hub && docker compose up -d --build

# launcher
cd search && make release

# ajustes do KDE (esquema de cores + Dolphin) e janela de configurações
cd kde && ./install.sh --diff
```

Cada pasta tem um README com os detalhes: [`agenda/`](agenda), [`jotter/`](jotter),
[`atena-sync/`](atena-sync), [`atena-hub/`](atena-hub), [`vicinae/`](vicinae).

## 📦 APK de release (atualização por cima)

Os apps Android têm um cofre de assinatura próprio, **fora do repositório**, em
`~/.config/atena/android-keys`. Todo build de release passa por
[`scripts/android-release.sh`](scripts/android-release.sh), que confere a
assinatura do APK contra o certificado do cofre antes de entregar:

```bash
./scripts/android-release.sh            # notas + calendário
./scripts/android-release.sh --check    # valida cofre, aliases e SDK
./scripts/android-release.sh --bump     # +1 no versionCode e builda
```

Sem essa chave o Android recusa a atualização e obriga desinstalar — o que apaga
os dados locais do app. Faça backup do cofre com
`./scripts/android-release.sh --backup`; a restauração em outra máquina é
`--restore`. Detalhes em [`scripts/README.md`](scripts/README.md).

## 🧭 Padrões de desenvolvimento

As convenções vivem em [`.cursor/rules/`](.cursor/rules) — valem para qualquer
agente de IA e servem de guia para humanos:

| Rule | Vale para |
| --- | --- |
| `patterns` | sempre — precedência: o guia de cada projeto/fork vence estas regras |
| `project-structure` | sempre — fronteiras do monorepo, build e o que não versionar |
| `git-docs` | sempre — commits e documentação |
| `kotlin-compose` | Kotlin — estilo, erros, corrotinas, estado de UI |
| `gradle-deps` | builds Gradle — versões só no catálogo, bump isolado |
| `server-backend` | servidor — auth, banco, contrato e operação |
| `kde-qml` | QML/Plasma 6 — apresentação, empacotamento e lint |
| `bash-installer` | scripts e instalador — bash seguro, idempotência e sync |

O [`.editorconfig`](.editorconfig) fixa o básico de formatação (UTF-8, LF, 4
espaços em Kotlin/QML, 2 em JSON/YAML).

---

## 📄 Licença

Distribuído sob a **GNU General Public License v3.0** — veja [LICENSE](LICENSE).

Atena Notes é um fork do [Jotter](https://github.com/OpenAppsLabs/Jotter), © Open
Apps Labs; o @Search é um fork do [Vicinae](https://github.com/vicinaehq/vicinae);
a Agenda é um fork do [Fossify Calendar](https://github.com/FossifyOrg/Calendar), ©
FossifyOrg. Mantenha os créditos e a mesma licença ao usar, modificar ou
redistribuir.
