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

## Sincronização

A sincronização é opcional e configurada dentro do app, em **Ajustes → Sincronização** (Android) e **Ajustes → Sincronização** (desktop): URL do hub e token de acesso. Sem servidor configurado, tudo funciona e fica no banco local.

- O app envia os eventos locais e puxa os remotos em `POST /api/v1/tools/agenda/sync`, com cursor próprio da Agenda e last-write-wins no `updatedTime`.
- No Android o envio é disparado pelas edições (com debounce), a cada 30 minutos e pelo botão **Sincronizar agora**; com o hub configurado o ícone de atualizar aparece na barra da agenda, e o gesto de puxar para baixo também sincroniza.
- No desktop o rodapé tem **Sincronizar**, a janela de ajustes tem **Sincronizar agora** e o `↻` no cabeçalho do calendário faz o mesmo; o widget do Plasma tem o botão de refresh na própria lista.
- Ficam de fora os eventos recorrentes, as ocorrências filhas, as tarefas e os eventos de calendários CalDAV ou importados de ICS: esses já têm o próprio mecanismo de sync, e o hub não representa recorrência.

## Licença

GPL-3.0-or-later, a mesma do Fossify Calendar. Veja [`android/LICENSE`](android/LICENSE) e os créditos no [README principal](../README.md).
