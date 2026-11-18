# Atena Sync

Daemon local de sincronização do monorepo Atena. Os apps (Jotter, depois
Agenda/Docs) continuam **local-first** no SQLite deles; este processo só
mantém uma **réplica de retransmissão** e escolhe o melhor canal.

Fase 1: ranking de conexões + **Wi‑Fi LAN** (mDNS) + sync de notas do Jotter.
O [Atena Hub](../atena-hub) fica como **fallback de internet**.

## Portas

| Porta | Bind | Uso |
| --- | --- | --- |
| `18765` | `127.0.0.1` | API para apps locais |
| `18766` | `0.0.0.0` | Peers na LAN |

## Requisitos

- JDK 21+
- Rede local (mDNS/`avahi` ajuda na descoberta; dá para desligar com `ATENA_SYNC_DISABLE_MDNS=1`)

## Instalar (Linux)

```bash
cd atena-sync
./install.sh
```

```bash
./install.sh uninstall          # remove binário/autostart; preserva dados
./install.sh uninstall --purge  # também apaga config/DB
```

Autostart: `~/.config/autostart/atena-sync.desktop`.
Dados: `~/.local/share/atena-sync/`. Binário: `~/.local/opt/atena-sync/`.

## Pareamento

Os dois aparelhos precisam do **mesmo token**. No primeiro boot o daemon gera um
e imprime no log (`~/.local/share/atena-sync/daemon.log`). No outro device:

```bash
ATENA_SYNC_TOKEN=o_mesmo_token ./install.sh
# ou exporte antes de subir o daemon
```

No aparelho local:

```bash
curl -s http://127.0.0.1:18765/v1/setup
```

No Jotter: URL `http://127.0.0.1:18765` + o token (ou o botão de sync local).

## Contrato local (apps → sync)

| Rota | Auth | Papel |
| --- | --- | --- |
| `GET /health` | não | daemon vivo |
| `GET /v1/setup` | só loopback | token + URL |
| `GET /api/v1/ping` | Bearer | compatível com o cliente do Jotter |
| `POST /api/v1/sync` | Bearer | push+pull LWW (réplica jotter) |
| `POST /v1/streams/jotter/sync` | Bearer | mesmo sync |
| `GET /v1/links` | Bearer | canais ranqueados |
| `GET /v1/peers` | Bearer | peers mDNS |
| `GET`/`PUT /v1/hub` | Bearer | fallback internet |

## Variáveis

| Nome | Padrão | Para que serve |
| --- | --- | --- |
| `ATENA_SYNC_LOOPBACK_PORT` | `18765` | API local |
| `ATENA_SYNC_LAN_PORT` | `18766` | peers |
| `ATENA_SYNC_DATA_DIR` | `~/.local/share/atena-sync` | SQLite |
| `ATENA_SYNC_TOKEN` | gerado | pareamento |
| `ATENA_SYNC_HUB_URL` | vazio | fallback hub |
| `ATENA_SYNC_HUB_TOKEN` | vazio | token do hub |
| `ATENA_SYNC_DEVICE_NAME` | hostname | nome no mDNS |
| `ATENA_SYNC_DISABLE_MDNS` | off | desliga discovery |

## Android

O módulo [`android/`](android) empacota o mesmo papel como serviço em primeiro
plano (API loopback + LAN). Build:

```bash
cd atena-sync/android && ./gradlew :app:assembleDebug
```

## O que não entra nesta fase

Wi‑Fi Direct, Bluetooth, Agenda/Docs, compartilhamento de tela, QR pairing.

## Licença

GNU GPL v3.0 — ver [`LICENSE`](../LICENSE) na raiz do monorepo.
