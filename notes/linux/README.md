# Atena Notes para Linux

Port desktop do [Jotter](https://github.com/OpenAppsLabs/Jotter) feito com **Compose Desktop** (Kotlin/JVM), com integração opcional ao **KDE Plasma 6** na forma de um widget flutuante.

> Este diretório faz parte do monorepo **Atena Notes**, um fork do Jotter. Para visão geral, servidor de sincronização e build do app Android, veja o [README principal](../README.md).

## Requisitos

- JDK 21
- Linux com ambiente gráfico (X11 ou Wayland)
- Para o widget: KDE Plasma 6 + KWin (opcional)

O Gradle Wrapper já vem incluído; não é preciso instalar o Gradle.

## Rodar

```bash
./gradlew :app:run
```

## Empacotar

```bash
# Distribuição com JRE embutido
./gradlew :app:createDistributable
# saída: app/build/compose/binaries/main/app/jotter/

# AppImage portátil
./gradlew :app:packageAppImage
# saída: app/build/compose/binaries/main/app/
```

O formato principal é **AppImage** (funciona em Arch/KDE sem os empacotadores deb/rpm). Em máquinas Debian/Ubuntu com `dpkg` disponível, também é possível gerar `.deb` com `:app:packageDeb`.

## Instalar como widget do KDE Plasma

```bash
./scripts/install.sh             # build + instala app, lançador, plasmoid e script do KWin
./scripts/install.sh uninstall   # remove tudo
```

O que é instalado:

| Caminho | Conteúdo |
| --- | --- |
| `~/.local/opt/jotter` | app image com JRE embutido |
| `~/.local/bin/jotter-toggle` | helper para mostrar/esconder a janela |
| `~/.local/share/applications/jotter.desktop` | entrada no menu de aplicativos |
| `~/.local/share/icons/hicolor/256x256/apps/jotter.png` | ícone |
| `~/.local/share/plasma/plasmoids/org.jotter.widget` | widget do painel |
| `~/.local/share/kwin/scripts/jotter-widget` | esconde a janela da barra de tarefas |

Depois de instalar, adicione o widget: botão direito no painel → **Adicionar widgets…** → **Jotter**. Se ele não aparecer na lista:

```bash
systemctl --user restart plasma-plasmashell
```

### Atalho global

A entrada `.desktop` declara `X-KDE-Shortcuts=Meta+N`, então o **Super+N** abre e fecha o Jotter de qualquer lugar, mesmo com a janela escondida. O atalho é registrado pelo KGlobalAccel e aparece em **Configurações do sistema → Atalhos → Aplicativos → Jotter**, onde pode ser trocado. Se o atalho não responder logo após uma reinstalação:

```bash
kbuildsycoca6 --noincremental
```

## Dados

As notas ficam no SQLite local do usuário (Room/SQLite). A sincronização com o servidor é configurada em **Configurações → Sincronização** e é opcional.

## Estrutura

```
linux/
├── app/            # aplicativo Compose Desktop
├── kde/            # widget Plasma (QML) e script do KWin
├── scripts/        # install.sh e jotter-toggle
└── packaging/      # recursos de empacotamento
```

Versão atual: **2.5.0-linux.1**.
