# Atena Hub

Servidor central das ferramentas do Atena. Hoje ele guarda e sincroniza as notas
do Jotter (Android e Linux) e serve essas notas, por uma API de leitura, para
outras ferramentas que não devem ter uma cópia do banco.

Os clients são **local-first**: a nota nasce e vive no SQLite do aparelho. O
servidor é uma cópia de convergência — se ele estiver desligado, o app continua
funcionando normalmente e sincroniza quando voltar.

- Kotlin + [Ktor](https://ktor.io) + SQLite (um único container, um volume)
- Autenticação por **token único**, gerado no primeiro boot
- Conflito resolvido por **LWW** (`updatedTime` do cliente vence)
- Notas criptografadas trafegam e ficam armazenadas **apenas como ciphertext**
- Nada de telemetria, nada de terceiros

O nome antigo (`jotter-server`) continua aceito: o hub reaproveita o banco
`jotter-server.db` que já existir e lê as variáveis `JOTTER_*`, então subir a
versão 2.0.0 não muda nada para os apps que já estão sincronizando.

---

## Subir com Docker

```bash
cd server
docker compose up -d --build
docker compose logs -f atena-hub
```

No log aparece o token na primeira subida:

```
================================================================
 Atena Hub 2.0.0 listening on 0.0.0.0:8080
 data file: /data/jotter-server.db
 tools: jotter 1

 hub token (treat it like a password):
   3791a9b5ad4a45c7bb45534cbaa67a9c
================================================================
```

O token fica gravado no banco (`/data/atena-hub.db` ou, num servidor que já
existia, `/data/jotter-server.db`), então ele não muda a cada restart. Para
fixar o seu, crie um `.env` (veja `.env.example`):

```bash
ATENA_TOKEN=meu-token-secreto
```

Se a porta 8080 já estiver em uso na máquina, publique em outra:

```bash
ATENA_HOST_PORT=8090 docker compose up -d --build
```

(Para não repetir isso, coloque `ATENA_HOST_PORT=8090` no `.env`.)

Depois abra `http://IP-DA-MAQUINA:8080` para confirmar que está no ar.

### Nos apps

Em **Configurações → Sincronização**, só existem dois campos:

| Campo | Exemplo |
| --- | --- |
| Servidor | `notas.exemplo.com` ou `192.168.0.10:8090` |
| Token | o que apareceu no log |

Pode colar com ou sem `https://` / `http://`:

- **domínio** sem esquema → o app assume `https://` (é o caso normal atrás de
  um reverse proxy com TLS);
- **IP privado** (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`) → assume
  `http://`.

Ao tocar em **Conectar**, o app valida URL + token contra o servidor
(`/api/v1/ping`) **antes de salvar**. Deu certo? ele pede um nome para o
aparelho — já vem preenchido com o modelo do celular ou o hostname do
computador — e sincroniza na hora. Deu errado? mostra o motivo e nada é salvo.

Depois disso a sincronização é automática: mudança local sobe em ~4 s e o app
busca novidades a cada 5 minutos. Não há mais um botão de "ligar/desligar".
O nome do aparelho aparece em `http://SEU-SERVIDOR/`, junto com o IP e a hora
do último sync de cada um.

> **Celular e Linux na mesma rede:** use o IP da máquina que roda o container
> (`hostname -I`), não `localhost`. `localhost` no celular é o próprio celular.

### Variáveis de ambiente

| Variável | Padrão | Para que serve |
| --- | --- | --- |
| `ATENA_PORT` | `8080` | Porta de escuta |
| `ATENA_HOST` | `0.0.0.0` | Endereço de bind |
| `ATENA_HOST_PORT` | `8080` | Porta publicada no host pelo Docker (mude se 8080 estiver ocupada) |
| `ATENA_DATA_DIR` | `/data` | Diretório do banco SQLite |
| `ATENA_TOKEN` | gerado | Token fixo (sobrepõe o do banco) |
| `ATENA_PUBLIC_URL` | vazio | Só um texto de ajuda na página inicial |

Os nomes antigos (`JOTTER_PORT`, `JOTTER_HOST_PORT`, `JOTTER_DATA_DIR`,
`JOTTER_SERVER_TOKEN`, `JOTTER_PUBLIC_URL`) continuam funcionando.

### Backup do servidor

Tudo está no volume `jotter-data` (o nome foi mantido de propósito: renomear
montaria um volume vazio e pareceria que as notas sumiram). Um dump consistente:

```bash
docker compose exec atena-hub sh -c 'cat /data/atena-hub.db' > atena-hub-backup.db
```

---

## Como a sincronização funciona

Cada nota tem um `uuid` igual em todos os lugares. O servidor guarda ainda um
`sync_seq` global e crescente, usado como cursor.

Uma chamada `POST /api/v1/sync` faz **push e pull na mesma transação**:

1. **Push** — para cada nota enviada:
   - não existe no servidor → insere;
   - existe e `updatedTime` enviado **maior** → atualiza;
   - existe e `updatedTime` igual/menor → ignora (o servidor vence).
   - deleção definitiva → remove a linha e grava um *tombstone*.
2. **Pull** — devolve tudo com `sync_seq > cursor` (notas, tombstones e
   categorias) e o novo `cursor`.

Consequências práticas:

- editar nos dois lados: vence o `updatedTime` maior, e o perdedor já recebe a
  versão vencedora na resposta do próprio push;
- apagar no celular: o tombstone viaja e o Linux apaga também;
- editar no Linux uma nota que foi apagada no celular há mais tempo: a edição
  vence e a nota volta (edição mais nova que a deleção);
- apagar uma nota criada offline que nunca chegou ao servidor: o tombstone
  garante que ela não reapareça na próxima sincronização.

### Relógio

O LWW usa o relógio do aparelho. Se o relógio de um deles estiver muito
errado, ele pode "perder" edições. A resposta traz `serverTime` e o app avisa
quando a diferença passa de 5 minutos.

### Criptografia ponta a ponta

Uma nota com senha própria nunca chega ao servidor legível. O app grava
`title` e `content` vazios no SQLite, guarda só o salt + o payload AES-GCM em
`encrypted_content`, e é exatamente isso que viaja (e fica no servidor). O
servidor reforça a regra: se um cliente enviar `isEncrypted: true` com texto
solto, ele descarta o texto e persiste apenas o ciphertext. A senha nunca sai
do aparelho — nem no backup do servidor ela aparece.

### Limitações conhecidas

- **Uma instância = um conjunto de notas.** O token é a credencial de acesso;
  não há contas separadas por pessoa. Para dois usuários, rode dois containers
  com volumes diferentes.
- **A nota geral (scratchpad) não sincroniza**: ela é local de cada aparelho.
- **Exclusão de categoria não é propagada** (categorias são união de nomes). Uma
  categoria apagada num aparelho pode reaparecer se o outro ainda a tiver.
  Excluir a categoria no segundo aparelho resolve.
- Sem HTTPS embutido. Para expor fora da LAN, coloque atrás de um reverse proxy
  (Caddy, Traefik, Nginx) com TLS.

### Agenda

Os eventos da Agenda (Linux) usam o **mesmo contrato** em
`POST /api/v1/tools/agenda/sync`, com cursor próprio (`agenda_sync_seq`): um
cursor de notas nunca se confunde com um de eventos. O merge é LWW por
`updatedTime`, igual ao das notas — o servidor guarda a versão mais nova que
recebeu, e devolve a vencedora para quem perdeu.

O evento carrega `title`, `description`, `location`, `startTime`, `endTime`,
`allDay` e uma `color` opcional (`#RRGGBB`). Séries recorrentes não são
expandidas: cada ocorrência é uma linha, como no app.

---

## API

Todas as rotas `/api/*` exigem `Authorization: Bearer <token>` (ou
`X-Jotter-Token: <token>`).

| Método | Rota | Descrição |
| --- | --- | --- |
| `GET` | `/health` | Liveness, sem autenticação |
| `GET` | `/api/v1/ping` | Valida URL + token |
| `POST` | `/api/v1/sync` | Push + pull |
| `GET` | `/api/v1/stats` | Contadores + aparelhos que já sincronizaram |
| `GET` | `/api/v1/tools` | Ferramentas que o hub publica (`jotter`, `agenda`) |
| `GET` | `/api/v1/tools/jotter/notes` | Leitura das notas (busca por `q`, `category`, `limit`) |
| `GET` | `/api/v1/tools/jotter/notes/{uuid}` | Uma nota |
| `POST` | `/api/v1/tools/agenda/sync` | Push + pull dos eventos da Agenda |

Exemplo:

```bash
curl -s -X POST http://IP:8080/api/v1/sync \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cursor": 0,
    "deviceId": "instalacao-1",
    "deviceName": "Notebook",
    "notes": [{
      "uuid": "0f8fad5b-d9cb-469f-a165-70867728950e",
      "title": "Do celular",
      "content": "texto",
      "category": "trabalho",
      "createdTime": 1730000000000,
      "updatedTime": 1730000000000,
      "format": "text"
    }],
    "categories": ["trabalho"]
  }'
```

Resposta:

```json
{
  "cursor": 7,
  "serverTime": 1730000001234,
  "notes": [ { "uuid": "0f8fad5b-...", "title": "Do celular", "...": "..." } ],
  "deletions": [ { "uuid": "...", "deletedAt": 1730000000000 } ],
  "categories": ["trabalho"]
}
```

Envie o `cursor` recebido na próxima chamada para baixar só o que mudou.
`cursor: 0` baixa o conjunto inteiro.

### Leitura para outras ferramentas

`GET /api/v1/tools/jotter/notes` existe para quem só precisa **ler** as notas.
A escrita continua sendo exclusiva de `/api/v1/sync`, pelo app dono da nota —
um leitor nunca consegue corromper o estado local do Jotter.

| Parâmetro | Efeito |
| --- | --- |
| `q` | busca por texto; título vale 6, corpo 2 e categoria 1 por termo. Acentos são ignorados (`pao` acha "pão") |
| `category` | filtra por categoria exata |
| `limit` | quantas notas devolver (padrão 20, máximo 500) |
| `updatedAfter` | só notas alteradas depois desse timestamp |
| `includeTrashed` | `1` inclui a lixeira |

Notas criptografadas vêm com `isEncrypted: true` e **sem** `content`: a senha
nunca sai do aparelho, então o servidor não tem como abrir o conteúdo.

```bash
curl -s "http://IP:8080/api/v1/tools/jotter/notes?q=receita&limit=5" \
  -H "Authorization: Bearer $TOKEN"
```

### Desenvolvimento

```bash
cd server
./gradlew run                        # http://localhost:8080
ATENA_DATA_DIR=/tmp/atena ./gradlew run
./gradlew installDist                # binário em build/install/atena-hub
```

Testes manuais de ponta a ponta (auth, LWW, tombstone, criptografia) estão em
[`docs/e2e.sh`](docs/e2e.sh), mas dependem do servidor já rodando:

```bash
ATENA_TOKEN=e2e-test-token ATENA_PORT=8099 ./gradlew run &
BASE=http://127.0.0.1:8099 TOKEN=e2e-test-token bash docs/e2e.sh
```
