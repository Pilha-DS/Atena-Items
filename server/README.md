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
- **Armazenamento universal**: coleções de documentos e arquivos para qualquer
  app novo, sem endpoint novo e sem republicar o servidor
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
 Atena Hub 2.2.0 listening on 0.0.0.0:8080
 data file: /data/jotter-server.db
 tools: jotter 2.5.0, agenda 0.1.0
 store: universal (records + files), no release needed for a new app

 hub token (treat it like a password):
   3791a9b5ad4a45c7bb45534cbaa67a9c

 clients: server URL = http://<this-machine>:8080
 admin screen: /admin (login "admin", password from ADMIN_PASSWORD)
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

Para acompanhar quem está sincronizando (e criar um token por aparelho), defina
o login da tela de administração no `.env`:

```bash
ADMIN_USER=admin
ADMIN_PASSWORD=uma-senha-boa
```

e abra `http://SEU-SERVIDOR/admin`. A tela lista cada aparelho, de onde ele
conectou, quando foi o último sync e quanto enviou/baixou — e é onde ficam os
tokens. Sem essas duas variáveis a tela não existe (responde 404).

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
| `ADMIN_USER` | vazio | Usuário da tela `/admin` (junto com `ADMIN_PASSWORD` a habilita) |
| `ADMIN_PASSWORD` | vazio | Senha da tela `/admin` |
| `ATENA_MAX_RECORD_KB` | `512` | Tamanho máximo de um registro do store universal |
| `ATENA_MAX_BLOB_MB` | `256` | Tamanho máximo de um arquivo enviado ao store |

Os nomes antigos (`JOTTER_PORT`, `JOTTER_HOST_PORT`, `JOTTER_DATA_DIR`,
`JOTTER_SERVER_TOKEN`, `JOTTER_PUBLIC_URL`) continuam funcionando. Os do admin
também aceitam o prefixo (`ATENA_ADMIN_USER`, `ATENA_ADMIN_PASSWORD`).

### Backup do servidor

Tudo está no volume `jotter-data` (o nome foi mantido de propósito: renomear
montaria um volume vazio e pareceria que as notas sumiram). Um dump consistente:

```bash
docker compose exec atena-hub sh -c 'cat /data/atena-hub.db' > atena-hub-backup.db
```

---

## Administração (`/admin`)

Uma página só, HTML do servidor, sem JavaScript e sem cadastro. O login vem de
`ADMIN_USER` e `ADMIN_PASSWORD`; se qualquer um dos dois faltar, `/admin`
responde **404** (não existe tela com senha vazia).

```bash
ADMIN_USER=admin ADMIN_PASSWORD=uma-senha-boa docker compose up -d
```

O que a página mostra:

| Bloco | Conteúdo |
| --- | --- |
| Cartões | dispositivos, notas, links, eventos, categorias, lixeira, registros do store e arquivos |
| Dispositivos | nome, id, IP, último sync (relativo e absoluto), último stream, itens enviados/baixados, volume em KB/MB, token usado, nº de rodadas |
| Últimas sincronizações | uma linha por sync: quando, aparelho, stream (`notas`/`links`/`agenda`/`store:namespace/coleção`), itens enviados/baixados, bytes recebidos/devolvidos, IP e token |
| Armazenamento universal | cada namespace/coleção que algum app criou: registros, removidos, volume, cursor e última escrita |
| Tokens | rótulo, prefixo mascarado, escopo de namespaces, data de criação, estado (ativo/revogado) e botão de revogar |
| Novo token | cria um token com escopo opcional para um cliente e mostra o valor **uma única vez** |

### Tokens

- Não existe conta de usuário para os apps: cada cliente recebe **um token** e
  o envia em `Authorization: Bearer <token>`.
- O token criado aparece completo só na resposta da criação. Depois disso a
  lista mostra apenas `abcd…wxyz`; se perder, revogue e crie outro.
- Revogar vale na hora: a próxima requisição daquele token recebe 401, sem
  restart e sem mexer nos outros aparelhos.
- O token do boot (`ATENA_TOKEN` ou o gerado) continua valendo e aparece na
  lista como **master** — é o que os apps já instalados usam.

### O que fica registrado

Uma linha por rodada de sync, na mesma transação que aplica a mudança: o painel
nunca mostra uma transferência que o servidor não gravou. Ficam guardados 30
dias (limpeza automática), só com: horário, token, id/nome do aparelho, IP,
stream, contagem de itens e bytes. **Nenhum conteúdo de nota ou link** é
registrado.

> **Use HTTPS.** Basic auth manda usuário e senha em Base64, que é apenas
> codificação. Em rede aberta, coloque o hub atrás de um reverse proxy com TLS
> (ou de um túnel). A página também responde `Cache-Control: no-store` e recusa
> `POST` de outra origem (`Sec-Fetch-Site: cross-site`).

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
  não há contas separadas por pessoa. No armazenamento universal dá para isolar
  por namespace com o escopo do token, mas `/api/v1/sync`, links e agenda
  continuam compartilhando o mesmo conjunto. Para dois usuários totalmente
  separados, rode dois containers com volumes diferentes.
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

## Armazenamento universal

Um app novo — lista de tarefas, cofre de senhas, galeria, save de jogo, qualquer
coisa — **não precisa de endpoint novo nem de uma nova versão do servidor**. Ele
escolhe um *namespace* (o nome do app) e usa uma das duas primitivas:

| Primitiva | Para quê | Rota |
| --- | --- | --- |
| **registros** | documentos pequenos: JSON, texto, configuração, estado | `/api/v1/store/{namespace}/{coleção}/...` |
| **arquivos** | bytes grandes: imagem, áudio, PDF, backup | `/api/v1/blobs/{namespace}/{nome}` |

O hub não interpreta o payload: um registro é uma string opaca e um arquivo é
um fluxo de bytes. É isso que permite um app novo existir só no cliente. Para
saber se o hub já tem essa camada, `GET /api/v1/ping` responde
`"universalStore": true`.

### Registros

| Método | Rota | Efeito |
| --- | --- | --- |
| `POST` | `/api/v1/store/{ns}/{coleção}/sync` | push + pull na mesma transação, com cursor da coleção |
| `GET` | `/api/v1/store/{ns}/{coleção}/records/{chave}` | um registro |
| `PUT` | `/api/v1/store/{ns}/{coleção}/records/{chave}` | grava o corpo como payload (relógio do servidor) |
| `GET` | `/api/v1/store` | descobre namespaces/coleções que já têm dados |

O `sync` segue o mesmo contrato das notas, porque é o mesmo problema: **LWW**
pelo `updatedTime`, *tombstones* para deleções e um cursor monotônico **por
`namespace/coleção`** (o cursor de `tarefas/items` nunca se mistura com o de
`jogo/saves`).

```bash
# enviar dois itens e receber o que mudou desde o cursor 0
curl -s -X POST http://IP:8080/api/v1/store/tarefas/items/sync \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "cursor": 0,
    "deviceId": "celular-1",
    "deviceName": "Celular",
    "records": [
      { "key": "t1", "payload": "{\"titulo\":\"comprar pão\",\"feito\":false}",
        "createdTime": 1730000000000, "updatedTime": 1730000000000 }
    ],
    "deletions": []
  }'
```

Resposta (mesma forma da API de notas):

```json
{
  "cursor": 1,
  "serverTime": 1730000001234,
  "records": [
    { "key": "t1", "payload": "{\"titulo\":\"comprar pão\",\"feito\":false}",
      "createdTime": 1730000000000, "updatedTime": 1730000000000 }
  ],
  "deletions": []
}
```

- `payload` é devolvido **byte a byte**; o app decide se é JSON, texto ou base64.
- O `cursor` da resposta vai na próxima chamada; `0` baixa a coleção inteira.
- Versão mais velha é ignorada; deleção vira *tombstone* e viaja para os outros
  aparelhos. Editar algo apagado há mais tempo ressuscita o registro, como nas
  notas.
- Limite por registro: `ATENA_MAX_RECORD_KB` (padrão 512 KB). Acima disso, use
  arquivo.

### Arquivos

| Método | Rota | Efeito |
| --- | --- | --- |
| `PUT` | `/api/v1/blobs/{ns}/{nome}` | envia o corpo como arquivo; `Content-Type` é preservado |
| `GET` | `/api/v1/blobs/{ns}/{nome}` | baixa os bytes; `ETag` + `If-None-Match` devolvem **304** |
| `HEAD` | `/api/v1/blobs/{ns}/{nome}` | só metadados (tamanho, tipo, hash) |
| `DELETE` | `/api/v1/blobs/{ns}/{nome}` | marca como removido e libera os bytes |
| `GET` | `/api/v1/blobs?namespace={ns}&includeDeleted=1` | lista os metadados do namespace |

```bash
curl -s -X PUT http://IP:8080/api/v1/blobs/fotos/2026/ferias.jpg \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: image/jpeg" \
  --data-binary @ferias.jpg
```

- Os bytes ficam em `DATA_DIR/blobs/<2 primeiros do hash>/<sha256>`, fora do
  SQLite: o download é um *stream* do disco, sem carregar linha nem binário na
  memória.
- **Deduplicação real por conteúdo**: o mesmo arquivo enviado por dois apps (ou
  com dois nomes) ocupa um único arquivo. Apagar um nome não quebra o outro; os
  bytes só saem quando o último nome que aponta para eles some.
- O `hash` retornado é o SHA-256 e também é o `ETag`. Um cliente que já tem a
  versão baixada revalida com uma resposta 304 de poucos bytes.
- Arquivos removidos ficam na listagem como `deleted: true` (para o outro
  aparelho saber o que apagar); o conteúdo não é mais servido (404).
- Limite por arquivo: `ATENA_MAX_BLOB_MB` (padrão 256 MB).

### Namespaces e escopo dos tokens

Namespace e coleção são identificadores curtos (`[A-Za-z0-9][A-Za-z0-9._-]*`,
até 64 caracteres); a chave do registro é opaca (até 512). Nada é pré-cadastrado:
o primeiro `sync`/`PUT` de um namespace cria a coleção. O painel `/admin` passa
a listá-la em **Armazenamento universal**.

Cada token criado no `/admin` aceita um **escopo** opcional: lista separada por
vírgula de namespaces, com `*` no final para prefixo. Vazio = todos. Exemplo:
`fotos,notas*,tarefas`. Um token escopado recebe **403** em qualquer outro
namespace, inclusive na listagem e nos arquivos. O token do boot (master) nunca
é restringido.

### Por que não pesa na resposta

- O pull lê só a janela `sync_seq > cursor`, com índice composto
  `(namespace, collection, sync_seq)`; o payload não é lido fora dela.
- WAL já ligado, uma transação por rodada, sem parsing do payload no servidor.
- Arquivos não passam pelo SQLite e o download vai direto do disco; ETag evita
  baixar de novo o que não mudou; conteúdo repetido é gravado uma vez.
- Medido localmente (1 vCPU): 1.000 registros de ~100 B → push em ~0,27 s, pull
  completo em ~0,05 s, pull sem novidade em ~0,01 s; upload de 3 MB em ~0,12 s.

---

## API

Todas as rotas `/api/*` exigem `Authorization: Bearer <token>` (ou
`X-Jotter-Token: <token>`).

| Método | Rota | Descrição |
| --- | --- | --- |
| `GET` | `/health` | Liveness, sem autenticação |
| `GET` | `/api/v1/ping` | Valida URL + token |
| `POST` | `/api/v1/sync` | Push + pull das notas |
| `GET` | `/api/v1/stats` | Contadores + aparelhos que já sincronizaram |
| `GET` | `/api/v1/tools` | Ferramentas que o hub publica (`jotter`, `agenda`) |
| `GET` | `/api/v1/tools/jotter/notes` | Leitura das notas (busca por `q`, `category`, `limit`) |
| `GET` | `/api/v1/tools/jotter/notes/{uuid}` | Uma nota |
| `POST` | `/api/v1/tools/jotter/links/sync` | Push + pull dos links |
| `POST` | `/api/v1/tools/agenda/sync` | Push + pull dos eventos da Agenda |
| `POST` | `/api/v1/store/{ns}/{coleção}/sync` | Push + pull de uma coleção genérica |
| `GET` | `/api/v1/store/{ns}/{coleção}/records/{chave}` | Um registro genérico |
| `PUT` | `/api/v1/store/{ns}/{coleção}/records/{chave}` | Grava um registro (corpo = payload) |
| `GET` | `/api/v1/store` | Namespaces/coleções existentes e seus cursores |
| `PUT` | `/api/v1/blobs/{ns}/{nome}` | Envia um arquivo (binário) |
| `GET` | `/api/v1/blobs/{ns}/{nome}` | Baixa um arquivo (ETag / 304) |
| `HEAD` | `/api/v1/blobs/{ns}/{nome}` | Metadados de um arquivo |
| `DELETE` | `/api/v1/blobs/{ns}/{nome}` | Remove um arquivo |
| `GET` | `/api/v1/blobs?namespace={ns}` | Lista metadados dos arquivos |
| `GET` | `/admin` | Painel de acompanhamento (Basic auth de `ADMIN_USER`) |
| `POST` | `/admin/tokens` | Cria um token (`label`, `scope`) e mostra o valor uma vez |
| `POST` | `/admin/tokens/{id}/revoke` | Revoga um token na hora |
| `GET` | `/admin/logout` | Descarta as credenciais em cache do navegador |

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
