# Agenda

Calendário do monorepo **Atena**, fork do [Fossify Calendar](https://github.com/FossifyOrg/Calendar). O código do upstream fica versionado junto e cada plataforma tem build próprio:

| Pasta | O que é | Stack |
| --- | --- | --- |
| [`android/`](android) | app Android (fork do Fossify) | Kotlin, Jetpack Compose |
| [`linux/`](linux) | port desktop + widget do KDE Plasma 6 | Kotlin, Compose Desktop |

```bash
# Android
cd android && ./gradlew :app:assembleDebug

# desktop Linux (+ widget do painel)
cd linux && ./gradlew :app:run
cd linux && ./scripts/install.sh
```

Os dois lados conversam pelo **Atena Hub** (`server/`): o app sincroniza os eventos via `POST /api/v1/tools/agenda/sync`, com last-write-wins no `updatedTime`. Detalhes em [`linux/README.md`](linux/README.md) e no [README principal](../README.md).

## Licença

GPL-3.0-or-later, a mesma do Fossify Calendar. Veja [`android/LICENSE`](android/LICENSE) e os créditos no [README principal](../README.md).
