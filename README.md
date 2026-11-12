# Atena

Monorepo dos meus projetos de desktop e Android. Cada pasta é um **projeto
independente**, com seu próprio build — dá para compilar, versionar e instalar
qualquer um deles sem tocar nos outros.

| Pasta | Projeto | O que é | Stack |
| --- | --- | --- | --- |
| [`agendas/`](agendas) | **Agenda** | Agenda/calendário — projeto novo, ainda vazio | — |
| [`notes/`](notes) | **Atena Notes** | App de notas offline-first (Android + port Linux com widget do KDE) | Kotlin, Jetpack Compose, Room, Compose Desktop |
| [`server/`](server) | **Atena Hub** | Servidor de sincronização das notas + API de leitura para outras ferramentas | Kotlin, Ktor, SQLite, Docker |
| [`search/`](search) | **@Search** | Launcher de desktop (fork do [Vicinae](https://vicinae.com)) | C++23, Qt 6, QML |

Os projetos se conversam por HTTP: o **Atena Hub** é o ponto de encontro. O app
de notas sincroniza com ele e qualquer outra ferramenta lê as notas por
`/api/v1/tools/jotter/notes` em vez de abrir o banco do app.

```
agendas/                    # projeto independente
notes/
├── Android/                # app Android (Gradle próprio)
└── linux/                  # port desktop + widget Plasma 6 (Gradle próprio)
server/                     # Atena Hub (Gradle próprio + Docker)
search/                     # clone separado do Vicinae (build CMake/Ninja)
LICENSE                     # GNU GPL v3.0
```

## Requisitos

| Projeto | Para buildar |
| --- | --- |
| `notes/Android` | JDK 17+ (recomendado 21), Android SDK API 36 |
| `notes/linux` | JDK 21, KDE Plasma 6 + KWin (só para o widget) |
| `server` | JDK 21 **ou** Docker + Docker Compose |
| `search` | Qt 6, CMake, Ninja, C++23 — ou use o binário oficial pré-compilado |

Os projetos Gradle trazem o Wrapper (`./gradlew`), então não é preciso instalar
o Gradle.

## Começando

```bash
# notas — Android
cd notes/Android && ./gradlew :app:assembleDebug

# notas — desktop Linux (+ widget do painel)
cd notes/linux && ./gradlew :app:run

# servidor
cd server && docker compose up -d --build

# launcher
cd search && make release
```

Cada pasta tem um README com os detalhes: [`notes/`](notes), [`server/`](server),
[`search/`](search), [`agendas/`](agendas).

## Licença

Distribuído sob a **GNU General Public License v3.0** — veja [LICENSE](LICENSE).

Atena Notes é um fork do [Jotter](https://github.com/OpenAppsLabs/Jotter), © Open
Apps Labs; o @Search é um fork do [Vicinae](https://github.com/vicinaehq/vicinae).
Mantenha os créditos e a mesma licença ao usar, modificar ou redistribuir.
