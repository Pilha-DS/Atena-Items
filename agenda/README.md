# Agenda

Calendário do monorepo **Atena**, fork do [Fossify Calendar](https://github.com/FossifyOrg/Calendar). O código do upstream fica versionado junto e cada plataforma tem build próprio:

| Pasta | O que é | Stack |
| --- | --- | --- |
| [`android/`](android) | app Android (fork do Fossify) | Kotlin, Jetpack Compose |
| [`linux-arch-kde/`](linux-arch-kde) | port desktop + widget do KDE Plasma 6 | Kotlin, Compose Desktop |

```bash
# Android
cd android && ./gradlew :app:assembleDebug

# desktop Linux (+ widget do painel)
cd linux-arch-kde && ./gradlew :app:run
cd linux-arch-kde && ./scripts/install.sh
```

Os dois lados conversam pelo **Atena Hub** (`server/`): o app sincroniza os eventos via `POST /api/v1/tools/agenda/sync`, com last-write-wins no `updatedTime`. Detalhes em [`linux-arch-kde/README.md`](linux-arch-kde/README.md) e no [README principal](../README.md).

## Sincronização

A sincronização é opcional e configurada dentro do app, em **Ajustes → Sincronização** (Android) e **Ajustes → Sincronização** (desktop): URL do hub e token de acesso. Sem servidor configurado, tudo funciona e fica no banco local.

- O app envia os eventos locais e puxa os remotos em `POST /api/v1/tools/agenda/sync`, com cursor próprio da Agenda e last-write-wins no `updatedTime`.
- Os **assuntos rápidos** andam em stream separado, em `POST /api/v1/tools/agenda/timers/sync` (veja [Assuntos rápidos](#assuntos-rápidos)).
- No Android o envio é disparado pelas edições (com debounce), a cada 30 minutos e pelo botão **Sincronizar agora**; com o hub configurado o ícone de atualizar aparece na barra da agenda, e o gesto de puxar para baixo também sincroniza.
- No desktop o rodapé tem **Sincronizar**, a janela de ajustes tem **Sincronizar agora** e o `↻` no cabeçalho do calendário faz o mesmo; o widget do Plasma tem o botão de refresh na própria lista.
- Ficam de fora os eventos recorrentes, as ocorrências filhas, as tarefas e os eventos de calendários CalDAV ou importados de ICS: esses já têm o próprio mecanismo de sync, e o hub não representa recorrência.

## Assuntos rápidos

Tarefas de tempo curto ("ir ao mercado em 20 minutos", "tirar o bolo do forno em 1h30") não são eventos: são **countdowns**, como o timer do relógio do celular. Cada plataforma tem uma seção própria, separada da agenda:

| Onde | Como abrir |
| --- | --- |
| Android | menu de três pontos da agenda → **Assuntos rápidos** |
| desktop | aba **Assuntos** no rodapé da janela |

O formulário pede o tempo e, opcionalmente, um nome. O tempo aceita o que a pessoa digita: `25`, `25min`, `1h`, `1h30`, `1:30`, `2 horas` — de 1 minuto a 24 horas. O que fica guardado é o **instante de término**, não "N minutos restantes": reiniciar o app, reiniciar a máquina ou receber o timer de outro aparelho não faz o countdown atrasar nem recomeçar.

- **Ao zerar**, avisa: no Android é uma notificação de alarme (canal próprio, som e vibração de alarme, prioridade alta); no Linux é um **toast** próprio no canto superior direito da tela (uns 2 s) mais o som de alarme do sistema. No Android o horário é agendado no `AlarmManager` com alarme exato, então funciona com o app fechado e sobrevive ao boot; no Linux a thread do app quente checa a cada segundo.
- **Cada aparelho toca o seu** uma vez: a marca de "já tocou" é local (`notified`), não viaja no sync.
- **Timer que chegou velho não toca**: um countdown vencido há mais de 10 minutos (por exemplo, puxado do hub com o aparelho desligado) é marcado como concluído em silêncio — alarme de ontem é ruído. Vale também para o limite do que foi perdido enquanto a máquina estava desligada.
- **Editar o término** reagenda o alarme; editar só o nome não faz tocar de novo.

Os timers viajam pelo mesmo hub, mas em **stream separado**: `POST /api/v1/tools/agenda/timers/sync`, com cursor próprio (`agenda_timers_sync_seq` no servidor) e as mesmas regras — last-write-wins no `updatedTime`, tombstone para exclusão. O cursor de timers nunca é lido como cursor de eventos. No banco local são as tabelas `quick_timers` e `quick_timer_tombstones`.

## Notificações

O app roda em segundo plano para avisar sobre o dia, nas duas plataformas. São dois avisos:

- **Resumo de manhã**: a lista dos eventos do dia, no horário escolhido (padrão 07:00).
- **Antes de cada evento**: um aviso com N minutos de antecedência (padrão 10), com horário e local.

Onde configurar: **Ajustes → Lembretes** no Android (chave "Notificações do dia") e **Ajustes → Notificações** no desktop. Dá para desligar cada aviso separadamente.

- **Android**: um `WorkManager` periódico (`AgendaNotifyWorker`, 15 em 15 minutos) faz o trabalho mesmo com o app fechado. Ele **sincroniza antes** de montar o resumo quando o hub está configurado, então um evento criado no desktop também notifica no celular. O agendamento é reaplicado no boot (`BOOT_COMPLETED`), então **liga junto com o aparelho**. No Android 13+ é preciso conceder a permissão de notificações — a tela de Ajustes pede na hora de ligar a chave.
- **Linux**: uma thread própria dentro do processo quente do app (`NotificationService`) verifica o dia a cada 30 s e mostra um **toast do próprio app** — um cartão sem moldura no canto superior direito da tela, com uma barra fina na base que encolhe durante os ~2 s de vida, e que não rouba o foco (`AgendaToasts`). Ele é uma janela separada da janela principal, então aparece igualmente com a agenda fechada; sem ambiente gráfico o app cai para `notify-send`/`gdbus`. O `install.sh` registra `~/.config/autostart/agenda.desktop`, que sobe o app **escondido no login**, para as notificações não dependerem do widget no painel.

O que já foi avisado fica gravado (`lastDigestDay`/`sent` no `notify.json` do desktop e preferências no Android), então reiniciar o app não repete a notificação do dia nem a de um evento.

No desktop, o mesmo toast atende o resto da sessão: `agenda-toast "Título" "Corpo"` mostra o cartão de dentro de qualquer programa (e o `install.sh` instala um shim de `notify-send` que manda os scripts da sessão para ele, com fallback para o binário real). O que **não** dá para converter é o que fala direto com `org.freedesktop.Notifications` — Cursor, apps GTK, KDE Connect —, porque quem recebe essas chamadas é o `plasmashell`; trocar isso é trocar o servidor de notificações do Plasma e perder histórico, Não Perturbe e ações. Detalhes em [linux-arch-kde/README.md](linux-arch-kde/README.md#toasts-de-fora-do-app).

## Licença

GPL-3.0-or-later, a mesma do Fossify Calendar. Veja [`android/LICENSE`](android/LICENSE) e os créditos no [README principal](../README.md).
