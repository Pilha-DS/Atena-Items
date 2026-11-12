# Atena Notes

App de notas offline-first, sem anúncios e sem telemetria. Duas frentes que
compartilham o mesmo modelo de dados:

| Pasta | O que é | Stack |
| --- | --- | --- |
| [`Android/`](Android) | Aplicativo Android | Kotlin, Jetpack Compose, Material 3, Room, Hilt, DataStore |
| [`linux/`](linux) | Port desktop com widget do KDE Plasma 6 | Kotlin, Compose Desktop (JVM), Room/SQLite, QML |

- Material 3 / Material You com temas dinâmicos
- Notas com bloqueio por PIN/biometria, arquivo, lixeira, categorias e busca
- 100% offline-first: os dados vivem no SQLite do aparelho
- Backup/restauração e importação/exportação
- Sincronização opcional entre Android e Linux pelo [`../server`](../server) (*last-write-wins*)
- Notas protegidas trafegam e ficam armazenadas apenas como ciphertext (AES-GCM)
- Widget flutuante para KDE Plasma 6

> **Fork:** o app mantém o nome interno e o pacote originais
> (`com.openappslabs.jotter`) para não quebrar compatibilidade nem o recebimento
> de correções do [Jotter](https://github.com/OpenAppsLabs/Jotter).

---

## 🤖 Android

O Gradle precisa saber onde está o Android SDK (`local.properties`, que não é
versionado):

```properties
sdk.dir=/home/SEU_USUARIO/Android/Sdk
```

```bash
cd Android

./gradlew :app:assembleDebug              # APK em app/build/outputs/apk/debug/
./gradlew :app:installDebug               # instala no aparelho conectado
./gradlew :app:testDebugUnitTest          # testes unitários
```

Build de release assinado, com as chaves em `Android/local.properties`:

```properties
storeFile=/caminho/para/jotter-release.jks
JOTTER_KEYSTORE_PASSWORD=sua-senha-do-keystore
JOTTER_KEY_ALIAS=alias-da-chave
JOTTER_KEY_PASSWORD=sua-senha-da-chave
```

```bash
cd Android && ./gradlew :app:assembleRelease
```

Sem keystore configurado, use o build de debug. Versão atual: **2.5.0**
(`versionCode 13`).

---

## 🐧 Linux (desktop)

Port Compose Desktop, com o widget do painel do KDE Plasma 6.

```bash
cd linux

./gradlew :app:run                        # rodar direto
./gradlew :app:createDistributable        # app image com JRE embutido
./gradlew :app:packageAppImage            # AppImage
```

Saída: `linux/app/build/compose/binaries/main/app/jotter/`.

### Instalar como widget do KDE Plasma

O script instala o app, o lançador, o widget QML e o script do KWin (que esconde
a janela da barra de tarefas):

```bash
cd linux
./scripts/install.sh             # build + instala
./scripts/install.sh uninstall   # remove tudo
```

Depois: botão direito no painel → **Adicionar widgets…** → **Jotter**. Se não
aparecer, recarregue o shell:

```bash
systemctl --user restart plasma-plasmashell
```

O script gera um `.desktop`, ícone em todos os tamanhos do hicolor e o helper
`~/.local/bin/jotter-toggle`. Versão atual: **2.5.0-linux.1**.

### Ícone

Gerado a partir de um SVG (fundo transparente). Depois de editá-lo:

```bash
cd linux/assets/branding
./render-icons.sh --install-into-theme
systemctl --user restart plasma-plasmashell
```

---

## 🔄 Sincronização

Opcional: sem servidor os apps continuam 100% offline. Em **Configurações →
Sincronização** informe o servidor e o token gerados pelo [`../server`](../server).
Os clients validam URL + token (`/api/v1/ping`) antes de salvar; conflitos são
resolvidos por *last-write-wins*.

---

## Licença

GNU GPL v3.0 — veja [`../LICENSE`](../LICENSE).
