# Atena Docs

Biblioteca de documentos do Atena para o Linux: lê a pasta onde os seus
arquivos já estão. A árvore real é a fonte da verdade — o app é o índice e a
janela que abre cada documento no aplicativo que você já usa (Dolphin,
LibreOffice, visualizador de PDF). O que ele escreve nessa árvore é pouco e
sempre a seu pedido: a caixa de entrada `init/` (criada automaticamente), a
pasta nova que você digita, o arquivo que você importa e o movimento que
**você** confirma depois de ver a sugestão. O que ele apaga também: só a pasta
ou a etiqueta que você mandar, e a pasta apenas com o nome dela escrito no
diálogo.

| | |
| --- | --- |
| Stack | Kotlin, Compose Desktop (JVM), JDK 21 |
| Dados | pasta do usuário + índice SQLite em `~/.local/share/docs` |
| Sincronização | nenhuma nesta etapa, de propósito |
| Licença | GNU GPL v3.0 |

> **Estado atual: índice, busca, etiquetas (com herança por pasta), pastas e
> organização com IA.** A janela, o tema do Plasma, o atalho global, o
> instalador, a indexação da pasta (busca por nome e por conteúdo, com OCR), as
> etiquetas hierárquicas e a herança declarada na pasta, os favoritos, a
> importação de arquivos para o `init`, a criação de pastas pela janela e a
> sugestão de etiqueta e de pasta por modelo local estão prontos. Abertura
> embutida e sincronização entram nas próximas etapas — nada aqui finge já
> funcionar.

## O que faz hoje

- Varre a pasta da biblioteca e indexa cada arquivo em SQLite, guardando
  caminho relativo, tamanho, data e tipo. Nenhum byte do conteúdo é lido.
- Na inicialização e a cada vez que a janela volta a aparecer, refaz só o
  diferencial: arquivo novo entra, alterado é atualizado, apagado sai da lista
  (a linha fica como `MISSING`, então etiquetas e favoritos não se perdem se o
  arquivo voltar).
- Reconhece arquivo **renomeado ou movido de pasta**: se o tamanho e a data de
  modificação continuam os mesmos, a linha antiga é reaproveitada com o caminho
  novo — etiquetas, favorito e identidade do documento ficam onde estavam. O
  rodapé mostra quantos foram reconhecidos na última varredura.
- **Busca universal** na barra do topo: encontra por nome do arquivo, nome da
  etiqueta, subnome (apelido) da etiqueta, metadados (tipo, extensão, tamanho,
  data) e, por último, conteúdo. A ordem do resultado segue essa prioridade.
  Imagens e PDFs escaneados passam por OCR (Tesseract); o rodapé mostra quantos
  documentos já têm texto.
- **Subnomes de etiqueta** (`⋮` → *Subnomes…*): apelidos de pesquisa da mesma
  etiqueta (`nota-fiscal` → `nfe`), sem criar hierarquia nova.
- **Sugere etiquetas a partir do texto** com um modelo — local no Ollama, sem
  nada saindo da máquina, ou DeepSeek, quando você configura a chave — e cai
  para as etiquetas que já existem e para as palavras do nome e do texto quando
  o modelo não responde. Você vê a sugestão, marca o que faz sentido e só então
  ela é aplicada; etiqueta que ainda não existe aparece marcada como nova.
- **Sugere a pasta do documento** — uma que já existe **no disco** (vazia
  inclusive) ou uma nova, quando nenhuma serve — e, se você confirmar, cria a
  pasta e move o arquivo para ela. Isso vale também para um documento na caixa
  de entrada `init/`: a sugestão nova aparece como qualquer outra e só é criada
  no seu clique (veja [O init](#o-init-caixa-de-entrada)).
- **Move o documento pela árvore** (*Mover…* no menu `⋮`): sem consultar o
  modelo — você entra na pasta de destino (ou digita o nome de uma pasta nova)
  e confirma. É o caminho curto para esvaziar o `init` quando você já sabe onde
  o arquivo vai.
- **Caixa de entrada `init/`** no topo da biblioteca, criada na primeira
  execução e recriada sozinha a cada varredura: é onde você despeja o que ainda
  vai organizar. Tudo o que entra passa por ela.
- **Importar pelo app**: *Importar…* na barra do topo abre o seletor de arquivos
  do sistema (o mesmo do Dolphin), aceita vários arquivos e os move para o
  `init/`. Nome repetido vira `nome (2).pdf` em vez de sobrescrever.
- **Criar pastas e subpastas pela janela**: *Nova pasta…* cria o caminho
  inteiro (`faculdade/2026/semestre-1`) e já abre a pasta nova — dentro do
  `init` também, porque é você quem está mandando.
- **Apagar e renomear pelo menu `⋮` da barra da esquerda**: ao passar o mouse
  na linha, o menu aparece com *Descrever…*, *Renomear…* (só etiqueta) e
  *Apagar*. Pasta só sai com o nome dela **digitado** no diálogo, porque leva
  os arquivos junto; etiqueta sai com um clique, removendo a subárvore e os
  vínculos com os documentos. Renomear muda o caminho (`nota-fiscal` → `nf`) e
  leva subetiquetas, documentos e subnomes junto — a identidade no banco é o
  id, não o texto do caminho.
- **Descrever pastas e etiquetas** com uma frase sua ("certificados de cursos de
  extensão"), guardada no SQLite: a IA lê essa definição antes do nome da pasta
  e dos exemplos, e a sugestão sem modelo também usa as palavras dela.
- Três visões de um clique na barra da esquerda: **Todos**, **Recentes**
  (modificados nos últimos 7 dias, já em ordem cronológica) e **Favoritos**.
- **Pastas**: a árvore de pastas do próprio disco, com a contagem de cada
  galho; escolher uma pasta mostra também o que está nas subpastas. Pastas
  vazias também aparecem — inclusive as que você criou e ainda não encheu. A
  árvore abre **recolhida** (só as pastas do topo): a setinha na frente do nome
  abre e fecha cada galho quando você quiser, e escolher uma pasta abre o galho
  onde ela está. **Arrastar pelo puxador** à esquerda da pasta e soltar sobre
  outra (ou sobre *Todos*) move a pasta inteira — arquivos, subpastas,
  descrição e etiquetas herdadas — sem diálogo extra; clicar no nome só
  seleciona; a `init` não se arrasta nem recebe o drop.
- Busca fuzzy enquanto você digita (nome, etiqueta, subnome, metadados,
  conteúdo + hábito de abertura), ordenação por nome ou por data, e abre cada
  documento no aplicativo padrão do sistema (LibreOffice, visualizador de PDF,
  Dolphin).
- Etiquetas hierárquicas (`academico/faculdade/2026`), numa barra à esquerda
  com a contagem de documentos de cada galho. Filtrar pela etiqueta pai mostra
  também tudo o que está abaixo dela, e o filtro se combina com a busca e com a
  visão escolhida. Cada etiqueta pode ter **subnomes de pesquisa** (`⋮` →
  *Subnomes…*). Uma pasta pode **herdar etiquetas** para todos os documentos
  nela e nas subpastas (`⋮` → *Herdar etiquetas…*), sem gravar em cada arquivo.
- Ignora arquivos e pastas ocultos e não segue links simbólicos: a biblioteca
  não sai da árvore nem entra em `.git` ou `.cache`.

## Navegar

| Escolha | O que mostra |
| --- | --- |
| Todos os documentos | tudo o que está indexado |
| Recentes | modificados nos últimos 7 dias, do mais novo para o mais velho |
| Favoritos | o que você marcou no menu `⋮ → Favoritar` |
| Pasta | a pasta e tudo o que está abaixo dela |
| Etiqueta | a etiqueta e todos os descendentes (várias ao mesmo tempo = AND) |

Visão, etiquetas e busca são filtros independentes e valem juntos: dá para ver
"Favoritos" filtrado por `mes-9` **e** `nota-fiscal` e pela palavra `contrato`.
Cada clique numa etiqueta **soma** ao filtro; clicar de novo nela tira só aquela.
Clicar de novo em Todos / Recentes / Favoritos (quando já está selecionado) volta
para tudo.

A barra de busca é universal e **fuzzy** (mesma ideia do launcher: campos
ponderados + hábito de abertura). Digitar o nome (ou um subnome) de uma
etiqueta mostra os documentos que a carregam, sem precisar clicar na barra da
esquerda. Pesos aproximados: nome / etiqueta / subnome `1.0`, conteúdo `0.6`,
metadados `0.5`, mais até `+6` pontos de frecency ao abrir o arquivo. Com a
barra vazia e ordenação por nome, a lista sobe pelo hábito de abertura; o chip
*Recentes* continua usando a data de modificação.

Um detalhe honesto: renomear ou arrastar um arquivo para outra pasta não perde
nada, porque a varredura casa o arquivo que sumiu com o que apareceu pelo par
tamanho + data de modificação (é o que `mv` preserva). Se o arquivo foi
**renomeado e editado no mesmo passo**, o par não fecha: a linha antiga fica
`MISSING` com as etiquetas dela e o arquivo entra como documento novo, sem
etiqueta. Os documentos `MISSING` nunca aparecem na lista; se o arquivo voltar
com o mesmo caminho, etiquetas e favorito voltam junto.

## O init (caixa de entrada)

No topo da biblioteca existe uma pasta `init/` — criada pelo app na primeira
execução, recriada a cada varredura se alguém apagar, e que você pode usar de
qualquer lugar (Dolphin, download, `mv` no terminal):

```bash
mv ~/Downloads/*.pdf ~/Documentos/AtenaData/Docs/init/
```

O caminho mais curto, porém, é a própria janela: **Importar…** na barra do topo
abre o seletor de arquivos do sistema, deixa escolher vários e faz o movimento
para o `init/`. A origem pode ser qualquer lugar do computador — `~/Downloads`,
um pendrive, outra pasta da biblioteca. O índice é atualizado em seguida, sem
reload.

O que o import garante:

| Situação | O que acontece |
| --- | --- |
| Vários arquivos de uma vez | todos vão para o `init/` numa tacada |
| Nome já usado no `init` | vira `nome (2).pdf`; o primeiro não é tocado |
| Pasta escolhida por engano | ignorada, o resto do lote segue |
| Arquivo já no `init` | ignorado |
| Arquivo em outra pasta da biblioteca | vai para o `init` e mantém etiquetas, favorito e texto |

Depois de recarregar a janela, esses arquivos aparecem na lista (e `init`
aparece na árvore de pastas, com a contagem). Ali você organiza de dois jeitos:
menu do documento → *Mover…* (escolhe a pasta na árvore, sem IA) ou
*Sugerir pasta…* / *Sugerir etiquetas…* (a IA propõe, você confirma).

**No `init`, nada é criado sem o seu clique.** As sugestões chegam completas e
selecionáveis; o que muda é quem decide:

| No `init` | O que acontece |
| --- | --- |
| *Mover…* | abre a árvore no topo; entrar numa pasta já é escolhê-la |
| *Mover…* com pasta nova | digite só o nome no campo; confirmar cria e move |
| Sugestão de pasta que já existe | aparece e pode ser aplicada |
| Sugestão de pasta nova | aparece com *nova — será criada*; confirmar cria e move |
| Sugestão de etiqueta que já existe | aparece marcada e pode ser aplicada |
| Sugestão de etiqueta nova | aparece com *nova — será criada*; confirmar cria |
| Digitar uma etiqueta nova | cria e aplica |
| Topo da biblioteca | sempre oferecido como destino, para sair do `init` num clique |
| Escolher navegando (na sugestão) | a árvore de pastas abre no topo e entrar numa pasta já é escolhê-la |

A regra é a mesma em qualquer pasta: **a máquina propõe, você confirma**. O `init`
não é uma exceção — é só o lugar onde a tentação de criar por conta própria era
maior. O que o app nunca faz é criar sozinho: toda escrita passa por um diálogo
com um botão.

A criação está no código que escreve (`DocMover`, `FolderCreator` e
`TagRepository`), não no diálogo: qualquer caminho futuro passa por ali. O teste
é duplo — a resposta *e* o disco.

## Etiquetas

| | |
| --- | --- |
| Como criar | menu do documento → *Etiquetar…* → digite e *Criar e aplicar* |
| Hierarquia | `academico/faculdade` — aplicar a folha cria os pais |
| Aplicar pai | filtrar por `academico` mostra o que está em `academico/*` |
| Onde moram | só no índice SQLite (`~/.local/share/docs/docs.db`) |
| Maiúsculas | o caminho é gravado em minúsculas; acento é preservado |
| Limites | 5 níveis, 32 caracteres por nível, 96 no caminho |
| Subnomes | `⋮` da etiqueta → *Subnomes…* — apelidos de busca (`nfe` para `nota-fiscal`) |
| Renomear | `⋮` da etiqueta → *Renomear…* — muda o caminho e a subárvore; vínculos e subnomes ficam |
| Documento no `init` | igual ao resto: etiqueta existente ou nova, aplicada no seu clique |

### Herança por pasta

Uma pasta pode **declarar etiquetas** que todo documento dentro dela — e nas
subpastas — recebe na leitura, sem gravar em cada arquivo.

| | |
| --- | --- |
| Como definir | `⋮` da pasta → *Herdar etiquetas…* (entre Descrever e Apagar); dá para filtrar a lista. *Adicionar* cria a etiqueta na hora; *Salvar* grava quais a pasta herda |
| Exemplo | em `profissional/tebas/notas-fiscais` herdar `#nota-fiscal` → um PDF em `…/notas-fiscais/2024/` aparece com essa etiqueta |
| Cadeia | o documento herda da pasta dele **e dos ancestrais** (`a` + `a/b` + `a/b/c`) |
| Filtro e contagem | filtrar pela etiqueta herdada encontra esses documentos; a barra lateral conta também |
| No documento | o picker mostra a etiqueta como *herdada* e não deixa desmarcar ali — só o diálogo da pasta altera |
| Mover | sair da pasta deixa de herdar; etiqueta aplicada à mão no documento continua |
| Onde mora | tabela `folder_tags` no mesmo `docs.db` (não é escrita na árvore do disco) |
| Sem herança | raiz da biblioteca e a pasta `init` |

Gravar só no banco é rápido e simples, mas o banco **não é reconstruível**: se
ele for apagado, os arquivos continuam na pasta e as etiquetas (e a herança)
não voltam. Quem quiser portabilidade entre máquinas pode exportar o banco;
sincronizar etiquetas entre dispositivos é etapa futura.

## Dentro do arquivo

O nome do arquivo quase nunca diz o que interessa. Depois da varredura, o Docs
lê o texto de cada documento e guarda em `doc_text` (no mesmo `docs.db`), de
onde a busca passa a olhar:

| Tipo | Como é lido | Precisa de quê |
| --- | --- | --- |
| `txt`, `md`, `log`, `csv` e código | leitura direta (até 4 MB) | nada |
| `docx`, `odt`, `xlsx`, `pptx` | XML de dentro do pacote, sem biblioteca extra | nada |
| PDF com camada de texto | `pdftotext`, 60 páginas | `poppler` |
| PDF escaneado | 5 primeiras páginas renderizadas e passadas pelo OCR | `poppler` + `tesseract` |
| imagem (`png`, `jpg`, `tiff`, `webp`…) | OCR | `tesseract` + `tesseract-data-por` |
| áudio, vídeo, compactado | nada | — |

```bash
sudo pacman -S --needed poppler tesseract tesseract-data-por
```

Como funciona, em três regras:

- **Uma leitura por arquivo.** 25 documentos por lote, os mais recentes
  primeiro; a sessão para em 200. O rodapé mostra `texto em N documentos` e
  `lendo conteúdo…` enquanto trabalha.
- **Nada é lido duas vezes.** A linha em `doc_text` guarda o resultado, mesmo
  quando o arquivo não tem texto (uma foto sem letra, um áudio). Editar o
  arquivo apaga a linha e a próxima varredura lê de novo; renomear ou mover
  mantém.
- **Ferramenta ausente não quebra nada.** Sem `tesseract`, imagem vira
  documento sem texto e a busca segue pelo nome. O menu `⋮ → Reler conteúdo`
  joga fora o texto guardado e lê tudo de novo — o caminho para quando você
  instala o Tesseract depois.

Limites honestos: OCR só das 5 primeiras páginas de um PDF escaneado, texto
cortado em 200 mil caracteres por documento e 60 páginas por PDF. A busca por
conteúdo é um `LIKE` no SQLite (sem índice de texto completo): numa biblioteca
de milhares de PDFs grandes isso aparece como um atraso ao digitar — FTS5 é a
próxima etapa.

## Organização com IA

Menu do documento → *Sugerir etiquetas…*. O app manda para o modelo tudo o que
sabe sobre o arquivo:

| O que vai no pedido | Exemplo |
| --- | --- |
| nome e pasta | `extensao_1.pdf`, pasta `init` |
| tipo e tamanho | `Tipo: PDF · 67,5 KB` |
| datas | `Criado no disco em: 08/03/2026`, `Modificado no disco em: 13/11/2026` |
| data do documento | `Data do documento (lida no conteúdo): 2026/mes-11` |
| trecho do conteúdo | até 2.500 caracteres do texto lido do arquivo |
| etiquetas que você já tem | cada uma com o nome de um ou dois documentos que a usam e, quando existe, a descrição que diz o que ela significa ([Descrever pastas e etiquetas](#descrever-pastas-e-etiquetas)) |

A resposta vem como uma lista de caminhos. A biblioteca grande não estoura o
pedido: o vocabulário vai até 80 etiquetas, as que têm palavra em comum com o
documento primeiro, e as amostras cabem num orçamento de texto — passou dele, as
etiquetas seguintes vão sem exemplo. Nada vai aplicar sozinho: o diálogo mostra
cada sugestão com uma caixa de marcar e o motivo, e você escolhe o que fica.

**Se o arquivo nunca foi lido, ele é lido na hora.** A varredura de conteúdo roda
em segundo plano, em lotes de 25, então um documento novo (ou um que a fila
ainda não alcançou) chegava ao modelo como "sem texto" — parecia que o app nunca
abriu o arquivo. Agora a sugestão lê aquele um arquivo, guarda o resultado como
qualquer outra leitura e só então pergunta ao modelo. Um arquivo já lido sem
nada dentro (uma foto sem letra) não é lido de novo: a linha vazia é a resposta,
e repetir o OCR a cada sugestão seria pior do que responder pelo nome.

**Etiqueta serve para achar depois.** O pedido pede três coisas, nesta ordem:
o **assunto** (`extensao`, `aluguel`, `nota-fiscal`), o **tipo de documento**
quando o texto diz (`certificado`, `comprovante`, `contrato`, `declaracao`,
`relatorio`) e a **data do documento**. Palavra de formulário — `aluno`,
`atividade`, `nome`, `matricula`, `data`, `arquivo` — não é proposta: ela está em
todo documento acadêmico e fiscal, então não filtra nada. A mesma lista é usada
para casar a descrição de uma etiqueta com o documento, que é como `nota-fiscal`
deixou de casar com um comprovante de extensão pelo único motivo de os dois
terem a palavra "arquivo".

**A data é a do documento, não a do arquivo.** As duas datas do cabeçalho são do
computador — o dia em que o PDF foi copiado para a biblioteca e o dia em que foi
editado — e um comprovante do 2º semestre copiado em novembro tem 13/11 no disco.
A etiqueta de data vem do que está escrito dentro do documento
(`Data da atividade: 2026 - 2º Semestre`). O `Data e hora: 13/11/2026` do
comprovante de envio é o upload no portal — **não** vira `mes-11`. Quando há
semestre, a etiqueta é `2026/semestre-2` (ou o ano/`mes-N` que já existirem na
biblioteca). Se o texto não declara período nenhum, não há etiqueta de data.

Etiqueta de data só é aceita quando o texto sustenta: o ano precisa aparecer no
conteúdo e, se a etiqueta cita um mês, o mês também. É isso que impede o
`mes-11` da data de cópia de voltar pela resposta do modelo. As etiquetas velhas
que ficaram na biblioteca (`2025`, `2026`, `mes-9`, `mes-10`, `mes-11`) não são
mais oferecidas para reuso nem propostas — você pode apagá-las pelo menu da
etiqueta. Uma etiqueta `ano/mes-N` já criada continua sendo oferecida, porque ela
pode ser reutilizada para outro documento do mesmo mês.

| | |
| --- | --- |
| Onde roda | [Ollama](https://ollama.com) em `http://127.0.0.1:11434` (padrão) ou um serviço no dialeto OpenAI, como o DeepSeek |
| Configuração | Menu `⋮` → **Inteligência artificial…** (provedor, modelo, endereço, chave e teste). A chave também pode ficar só em `~/.config/docs/ai` ou na variável de ambiente |
| Modelo padrão | Ollama: `qwen2.5:7b` (atalhos 7B / 8B / 3B no diálogo); em branco, o primeiro instalado. DeepSeek: `deepseek-flash` |
| Sem modelo | cai para palavras-chave no texto, nas etiquetas existentes e nas descrições que você escreveu |
| Sem texto legível | sugere pelo nome, pasta, tipo, tamanho, datas e etiquetas da vizinhança |
| Data do documento | lida do texto (`13/11/2026`, `2026 - 2º semestre`, `abril de 2025`) e proposta como `ano/mes-N` |
| Etiqueta de data | só quando o texto do documento sustenta o ano e o mês |
| Já aplicadas | a etiqueta que o documento já tem não é sugerida de novo; se o modelo só repetir as que já estão lá, o diálogo diz isso e oferece as novas |
| Etiquetas existentes | mesmo com resposta da IA, as etiquetas da biblioteca que casam com o documento entram primeiro (reuso antes de inventar) |
| Ano e mês separados | se a biblioteca já tem `2018`…`2027` e `mes-1`…`mes-12`, a sugestão reusa esses em vez de criar `2025/mes-11`; `2025/11` da IA também vira esse par |
| Data de criação | vem do disco na hora da sugestão; quando o sistema de arquivos não guarda hora de nascimento, a linha é omitida em vez de inventar uma data |

Com Ollama, tudo fica na máquina. Para testar classificação local, o tamanho
recomendado é **7B** (`qwen2.5:7b`): melhor que 3B em ambiguidades em português
(`nota` acadêmica vs nota fiscal), ainda leve o bastante para desktop.

```bash
ollama pull qwen2.5:7b
printf 'provider=ollama\nmodel=qwen2.5:7b\nurl=http://127.0.0.1:11434\n' > ~/.config/docs/ai
```

No app: `⋮` → **Inteligência artificial…** → *Nesta máquina (Ollama)* → atalho
`qwen2.5:7b` → **Testar** → **Salvar**. Alternativas nos atalhos: `llama3.1:8b`
e `qwen2.5:3b` (mais leve, menos preciso). Trocar para *Serviço remoto* redefine
modelo e endereço para o DeepSeek (`deepseek-flash` / `deepseek-v4-pro`); nomes
de modelo do Ollama não são levados junto. `deepseek-chat` antigo no arquivo é
remapado para `deepseek-flash`.

Com DeepSeek, **o trecho do documento sai pela rede** — é o preço do modelo
grande. O caminho normal é colar a chave no app (veja abaixo); se preferir deixar
tudo pronto pelo terminal, o arquivo é este:

```bash
mkdir -p ~/.config/docs
umask 077
printf 'provider=deepseek\nmodel=deepseek-flash\n' > ~/.config/docs/ai
chmod 600 ~/.config/docs/ai
```

```bash
# ou sem escrever a chave no disco: exporte antes de abrir o app
export DEEPSEEK_API_KEY=SUA_CHAVE
```

Se o arquivo com a chave estiver legível por outros usuários, o app avisa no
rodapé ao abrir. A chave nunca é impressa em log, mensagem ou diálogo.

**O que a janela configura.** No menu `⋮` da janela, *Inteligência artificial…*
escolhe o provedor (Ollama ou DeepSeek), o modelo, o endereço e a **chave**, e
tem o botão **Testar** — que usa a chave digitada na hora (ou a já salva) e não
muda nada no disco. **Testar pede uma resposta de verdade**, não só a lista de
modelos: um nome de modelo que o serviço não conhece passa pela chave e falha
em toda sugestão depois, então o teste também pega esse caso. Salvar aplica na
hora, sem reiniciar o app.

O campo da chave é **mascarado** (o olho ao lado mostra enquanto você confere o
fim do token) e é **de mão única**: com uma chave já gravada, o diálogo só diz
*“Chave já configurada”*, oferece **Remover chave** e deixa o campo para uma
substituição. Deixar o campo em branco mantém a que está no arquivo; a tecla de
salvar nunca apaga a chave por acidente — apagar é o botão explícito. A gravação
é feita pelo app em `~/.config/docs/ai`, criado e mantido `0600`, e o cliente de
modelo é trocado na hora.

Uma chave que veio de `DEEPSEEK_API_KEY` continua **nunca** sendo gravada no
disco só porque você trocou o modelo: só o que você digita no campo é escrito.

Umas quatro regras que valem saber:

- **O provedor não é adivinhado, exceto por um caso.** `provider=` manda; sem
  ele, um `url=` com host **público** significa serviço remoto (ninguém aponta
  para `api.deepseek.com` por acidente). Endereço privado (`192.168.0.10`,
  `ollama.local`, um nome sem ponto) continua sendo Ollama, porque um Ollama em
  outra máquina da casa ainda é dado local.
- **Sem IA, continua funcionando.** Se o modelo não responde, a sugestão vem
  do texto (`HeuristicTagger`) e o diálogo diz **por que** o modelo falhou —
  chave ausente ou recusada, endereço sem resposta, modelo que o serviço não
  conhece (use `deepseek-flash` ou `deepseek-v4-pro`; `deepseek-chat` foi
  aposentado). A mensagem genérica "IA indisponível" só aparece quando
  o cliente não sabe dizer mais do que isso.
- **Biblioteca sem etiqueta também ganha sugestão.** Depois das etiquetas que
  já existem, o app propõe etiquetas **novas** tiradas do nome do arquivo e das
  palavras que o texto repete (`contrato`, `aluguel`), marcadas como novas —
  aceitar cria a etiqueta. Nada é criado sozinho.
- **A sugestão não é gravada até você aceitar.** O banco só muda no botão de
  aplicar, com as mesmas validações do diálogo de etiquetar.

## Organizar em pastas

Dois caminhos no menu `⋮` do documento:

| Ação | Quando usar |
| --- | --- |
| *Mover…* | você já sabe o destino; abre a árvore no topo, sem consultar o modelo |
| *Sugerir pasta…* | quer que a IA proponha; o diálogo traz sugestões **e** a mesma árvore |

*Mover…* é o caminho curto para esvaziar o `init`: entrar numa pasta já a escolhe,
e digitar só o nome cria uma subpasta nova no nível aberto ao confirmar.

*Sugerir pasta…* usa o mesmo modelo das etiquetas, com outro pedido: nome,
pasta atual, tipo, tamanho e datas do arquivo, **todas as pastas que existem no
disco** — vazias inclusive, cada uma com uma amostra dos documentos que já tem
(ou a marca de vazia) **e a descrição que você escreveu** — e um trecho do
conteúdo. O nome da pasta sozinho não basta: duas irmãs como `Extensao/Original`
e `Extensao/Certificados` só se distinguem pelo que está dentro, ou pela frase
que você escreveu sobre cada uma
([Descrever pastas e etiquetas](#descrever-pastas-e-etiquetas)). A resposta é
**um** caminho, e o diálogo mostra o candidato com o motivo — inclusive quando a
pasta ainda não existe — mais a **árvore de pastas para escolher navegando**:
clicar numa pasta entra nela, e a pasta aberta é o destino, como num diálogo de
salvar. Nada de escrever caminho: a trilha no topo (`topo da biblioteca ›
faculdade › 2026`) volta para qualquer nível, e a sugestão do modelo leva a
árvore direto para a pasta dela. O único campo que sobrou pede **só o nome** de
uma subpasta nova no lugar onde você está — `faculdade/2026` aberto + `semestre-1`
cria `faculdade/2026/semestre-1` ao confirmar.

| No diálogo de pasta | O que acontece |
| --- | --- |
| Clicar numa pasta da árvore | entra nela e o destino passa a ser ela |
| Clicar na trilha | volta para aquele nível (o topo inclusive) |
| Clicar numa sugestão | leva a árvore para aquela pasta e a marca como destino |
| Digitar só o nome | a subpasta é criada dentro do nível aberto, no seu clique |

A lista vem da varredura da biblioteca, não do índice: uma pasta criada agora e
ainda vazia aparece para o modelo, e por isso ele não propõe "criar" algo que já
está lá. Com uma árvore grande, as pastas que compartilham palavras com o
documento vão para o começo da lista enviada ao modelo, então o corte (120
pastas) tira as candidatas menos prováveis, não as melhores. Quando nenhuma
pasta existente serve, o diálogo mostra uma pasta nova
tirada do nome do arquivo (`Contrato de aluguel.pdf` → `Contrato`), marcada como
nova; confirmar cria a pasta.

Se o modelo responder **a pasta em que o documento já está**, o diálogo diz
"o documento já está na pasta certa" em vez de trocar a resposta por uma pasta
irmã que o modelo nunca mencionou; as pastas novas tiradas do nome continuam
aparecendo, para quando "já está certo" não for a resposta que você quer.

Ao confirmar, o app:

1. valida o caminho;
2. confere que o arquivo existe e que nada com aquele nome já está no destino;
3. cria a pasta (se preciso) e move o arquivo;
4. atualiza o índice na hora — o mesmo documento, com as mesmas etiquetas,
   favorito e texto lido.

Um detalhe: documento dentro do `init/` segue a mesma regra — a pasta nova
aparece marcada como nova e, confirmando, é criada e o arquivo vai para ela. O
topo da biblioteca aparece sempre na lista, como saída rápida de qualquer pasta
funda. Veja [O init](#o-init-caixa-de-entrada).

| Regra | O que acontece |
| --- | --- |
| Sobrescrever | nunca: nome já usado no destino é recusa |
| Pastas ocultas (`.git`, `.cache`) | recusadas |
| `..`, `~` e separador do Windows | recusados |
| Profundidade | até 5 níveis, 64 caracteres por nível |
| Raiz da biblioteca | destino válido (o modelo responde `raiz`) |
| Documento no `init` | pasta existente ou nova: a nova é criada ao confirmar |
| Falha no movimento | a pasta criada é removida se ficou vazia |
| Sem modelo | sugere pastas pelas palavras do nome, do texto e das descrições; sem nenhuma que sirva, propõe uma pasta nova |
| Arquivo nunca lido | lido na hora, antes de perguntar ao modelo (o resultado fica guardado) |

O movimento é um `rename` dentro do mesmo sistema de arquivos: o arquivo não é
copiado e o conteúdo não muda. O índice é atualizado na hora, sem esperar a
próxima varredura; se essa escrita falhasse, a varredura seguinte reconhece o
par tamanho + data e mantém as etiquetas.

Limites honestos: o app **não desfaz** um movimento (o caminho de volta é o
Dolphin, ou *Mover para o init* no menu do documento) e importar uma **pasta
inteira** ainda não está aqui — o seletor entrega arquivos, e cada um vira um
documento solto no `init`.

## Criar pastas pela janela

*Nova pasta…* aparece no `⋮` da barra do topo e no `+` ao lado de "Pastas" na
barra da esquerda. O campo aceita um caminho com `/`, então
`faculdade/2026/semestre-1` cria os três níveis de uma vez, e o formulário é
preenchido com a pasta que você está olhando (criar uma subpasta é digitar o
nome e confirmar).

| Regra | O que acontece |
| --- | --- |
| Caminho vazio ou a raiz | recusado |
| Pasta que já existe (ou arquivo com o nome) | recusado, sem reaproveitar |
| Dentro do `init` | permitido, como em qualquer pasta |
| Pasta oculta, `..`, `~` | recusados |
| Profundidade | até 5 níveis, 64 caracteres por nível |
| Pasta criada | aparece na árvore na hora e já fica selecionada |

A árvore da esquerda vem do disco, não do índice: uma pasta recém-criada (ou
vazia, esperando o primeiro arquivo) aparece com contagem zero. O número só
aparece quando existe documento; uma pasta vazia é só o nome.

## Mover pastas pela barra

Na árvore da esquerda, cada pasta tem um **puxador** fino à esquerda (ícone
de arrastar). Arraste por esse puxador e solte sobre outra pasta (`a/b` sobre
`c` vira `c/b`); o resto da linha só seleciona. Soltar sobre **Todos** leva a
pasta de volta ao topo da biblioteca. O destino sob o mouse fica destacado;
o drop é a confirmação (igual ao movimento de arquivo).

| Situação | O que acontece |
| --- | --- |
| Soltar sobre outra pasta | a pasta (com tudo dentro) vira filha do destino |
| Soltar sobre *Todos* | a pasta sobe para a raiz da biblioteca |
| `init` | não arrasta e não recebe; subpastas dela são pastas comuns |
| Mesmo nome no destino | recusa — nada é sobrescrito |
| Soltar em si ou num descendente | recusa |
| Mais de 5 níveis | recusa |
| Índice e metadados | `relPath` dos documentos, descrições e herança de etiquetas acompanham o caminho novo |

Fora deste passo: arrastar um **documento** para uma pasta na barra.

## Descrever pastas e etiquetas

Um nome de pasta não diz o que ela guarda. `Extensao/Original` e
`Extensao/Certificados` são iguais para quem só lê o caminho; você sabe qual tem
o certificado assinado. O mesmo vale para etiqueta: `unip` pode ser a
universidade, o convênio ou o plano de saúde.

Na barra da esquerda, o `⋮` da pasta ou da etiqueta tem **Descrever…**, com um
campo de uma frase. O texto é lido pela IA *antes* do nome da pasta e dos
exemplos de documentos, e é a definição do que aquilo significa — não uma
amostra.

| | |
| --- | --- |
| Onde fica | `⋮` na linha da pasta ou da etiqueta, na barra da esquerda — sempre visível (meio apagado até o mouse passar) |
| O que guarda | até 200 caracteres numa linha só; quebras de linha viram espaço |
| Onde mora | pasta em `folder_descriptions` (tabela do `docs.db`); etiqueta numa coluna da própria linha em `tags` |
| Apagar | apagar o campo e salvar: a linha some, nada fica em branco |
| Na barra | a frase aparece embaixo do nome, em letra pequena, uma linha só |
| Nome muda no Dolphin | a descrição fica presa ao **caminho** e não acompanha; renomear ou mover a pasta pelo lado de fora perde o vínculo |
| Mover pela barra | arrastar a pasta na árvore leva a descrição e as etiquetas herdadas junto |
| Pasta apagada pelo app | as descrições **e as etiquetas herdadas** da pasta e das subpastas saem junto |
| Filtro | não muda: a descrição é contexto, não é busca |

Um detalhe que importa para o modelo: a descrição **não** é enviada junto com o
documento, e sim junto da pasta ou da etiqueta, no mesmo pedido que já levaria o
trecho do texto. Com Ollama, nada sai da máquina; com DeepSeek, a frase sai no
mesmo pacote — e só quando você pede a sugestão.

## Apagar pastas e etiquetas

Apagar fica na barra da esquerda: cada pasta ou etiqueta tem um `⋮` na ponta da
linha, sempre visível, que abre *Descrever…* e *Apagar*. A `init` não tem
nenhum dos dois — é recriada a cada varredura (apagá-la seria um clique sem
efeito) e não é destino de sugestão (uma descrição dela nunca chegaria ao
modelo); uma subpasta dela, essa sim, é uma pasta comum.

**Pasta: confirmação escrita.** O diálogo mostra o caminho, conta o que está
dentro e só libera o botão depois que você digita o nome da pasta:

| O que tem dentro | O que a confirmação faz |
| --- | --- |
| vazia | apaga a pasta |
| arquivos | apaga a pasta **e os arquivos**, subpastas inclusive, direto do disco |
| alguma coisa falhou | o rodapé diz quantos arquivos já saíram; o que sobrou fica |

Não há lixeira própria: o que sai, sai. O índice toma conhecimento na varredura
seguinte — os documentos apagados viram linhas `MISSING`, invisíveis na lista, e
guardam as etiquetas caso um backup traga os arquivos de volta.

Recusas, decididas na camada de dados e não só pelo botão escondido: a raiz da
biblioteca, a `init`, um caminho que sai da biblioteca (`..`) e pasta oculta.
Links simbólicos não são seguidos — um atalho dentro da pasta é apagado como
atalho, nunca o que ele aponta.

**Etiqueta: um clique.** Apagar uma etiqueta apaga um rótulo, e os documentos
ficam onde estão; o diálogo só confirma, dizendo de quantos documentos ela sai e
quantas subetiquetas vão junto. A subárvore inteira sai: filhas órfãs dariam um
galho que a barra lateral não explica. Se o filtro ativo era essa etiqueta (ou
algo abaixo dela), ele é limpo e a lista volta a mostrar tudo.

Quem estava olhando a pasta apagada volta para **Todos** — a pasta não existe
mais, e uma lista vazia sob um nome que sumiu não ajudaria em nada.

## Requisitos

- JDK 21 (o instalador do monorepo instala via `pacman` quando falta).
- KDE Plasma 6 para o tema seguir a paleta do sistema (sem ele, a paleta escura
  padrão é usada).
- Nenhuma dependência de runtime além do que a app image já embute.
- Opcionais, só para ler dentro dos arquivos: `poppler` (PDF) e `tesseract` +
  `tesseract-data-por` (OCR). Sem eles o app funciona; a busca fica no nome.
- Opcional, para o seletor de arquivos do KDE: `kdialog`. Sem ele o
  *Importar…* usa o diálogo do Java — mesma função, sem o tema do Plasma.

## Rodar

```bash
cd docs
./gradlew :app:run
```

## Build e instalação

```bash
cd docs

./gradlew :app:createDistributable   # app image com JRE embutido
./gradlew :app:test                  # testes unitários
./scripts/install.sh                 # build + instala (app, atalho, ícone)
./scripts/install.sh uninstall       # remove tudo (preserva seus dados)
```

Ou pelo instalador do monorepo:

```bash
./scripts/install-kde-arch.sh --only docs
```

Depois de instalar, **Super+Shift+D** abre e fecha a janela. O atalho é
registrado via `X-KDE-Shortcuts` no `.desktop`; se for reivindicado por outro
app, ajuste em *Configurações do Sistema → Atalhos*. Super+D não é usado porque
o Plasma o reserva para "Mostrar a área de trabalho".

## Configuração

| Caminho | Para quê |
| --- | --- |
| `~/Documentos/AtenaData/Docs` | pasta padrão da biblioteca (respeita `XDG_DOCUMENTS_DIR`); a caixa de entrada é `init/` dentro dela |
| `~/.config/docs/root` | troca a pasta de documentos: a biblioteca passa a ser `<caminho>/AtenaData/Docs` (uma linha, caminho absoluto ou `~/…`) |
| `~/.local/share/docs/docs.db` | índice, etiquetas, herança por pasta, descrições de pastas e etiquetas e o texto lido. O índice se refaz numa varredura; as etiquetas, a herança e as descrições não |
| `~/.local/share/docs` | dado do app; o instalador nunca apaga |
| `~/.config/docs/ipc-dir` | publica o diretório de handshake usado pelo `docs-toggle` |
| `~/.config/docs/ai` | onde está a IA: `provider=`, `url=`, `model=` e `key=`, uma por linha. Tudo é escrito pelo menu *Inteligência artificial…* (a chave vai pelo campo mascarado), criado e mantido `0600`; editar à mão continua valendo |
| `DOCS_AI_PROVIDER` | `ollama` (padrão) ou `deepseek` |
| `DOCS_AI_URL` / `DOCS_AI_MODEL` | o mesmo que as linhas `url=` e `model=` |
| `DOCS_AI_KEY` / `DEEPSEEK_API_KEY` | a chave, sem escrevê-la no arquivo (a segunda só vale no provedor `deepseek`) |

A pasta padrão fica dentro de `AtenaData` para não misturar os seus documentos
com os arquivos do app. Nada é apagado do disco: desinstalar remove binário,
atalho e ícone, e para aí.

Para apontar a pasta de documentos para outro lugar, basta uma linha — a
biblioteca fica em `AtenaData/Docs` dentro dela:

```bash
echo "/mnt/dados/Documentos" > ~/.config/docs/root   # biblioteca em /mnt/dados/Documentos/AtenaData/Docs
```

## Estrutura

```text
docs/
├── app/
│   ├── assets/branding/docs.svg       # fonte do ícone
│   └── src/main/kotlin/com/openappslabs/docs/
│       ├── Main.kt                    # janela, instância única, toggle
│       ├── data/
│       │   ├── model/                 # Doc, Tag, DocTag, DocText, FolderDescription, FolderTag e DocKind
│       │   ├── scan/                  # varredura da pasta e diff do índice
│       │   ├── tags/                  # regras do caminho da etiqueta e herança por pasta
│       │   ├── move/                  # caminho de pasta, caixa de entrada, movimento, criação, remoção de pasta e importação
│       │   ├── text/                  # leitura do conteúdo (texto, Office, PDF, OCR)
│       │   ├── ai/                    # modelo local (Ollama), prompt e sugeridor
│       │   ├── tree/                  # árvore de caminhos (pastas e etiquetas)
│       │   ├── source/                # banco, DAOs, migrações e tradução do LIKE
│       │   └── repository/            # DocIndexer, DocTextIndexer, TagRepository, FolderTagRepository e DescriptionRepository
│       ├── platform/                  # Xdg, DocsPaths, IpcBridge, FileActions, FilePicker
│       └── ui/                        # tela, barra lateral, diálogos (etiqueta, herança, sugestão, nova pasta, apagar e descrição), tema
├── scripts/{install.sh,docs-toggle}
└── gradle/libs.versions.toml          # catálogo próprio deste projeto
```

O desenho em uma frase: `scan` só descreve o disco, `IndexPlan` só calcula o
que mudou, `DocIndexer` grava, `DocTextIndexer` lê o conteúdo em lotes,
`PathTree` só monta a árvore (a mesma para pastas e etiquetas, inteira em
`flatten` e respeitando os galhos abertos em `visible`),
`Inbox` cria a caixa de entrada na primeira execução (e é quem diz o que está
dentro dela), `FolderPath`/`DocMover`/`FolderCreator`/`FolderRemover` são os
únicos que escrevem na árvore e `LibraryController` observa o banco. Testar cada
peça sem banco e sem janela é consequência disso.

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| Super+Shift+D não faz nada | o app não está instalado, ou o atalho está tomado; confira `~/.local/share/applications/docs.desktop` |
| Super+Shift+D não aparece na lista de atalhos | o Plasma registra `X-KDE-Shortcuts` ao ler o `.desktop`: rode `kbuildsycoca6` e, se preciso, saia e entre na sessão |
| A janela abre com a paleta errada | `~/.config/kdeglobals` ausente ou sem `[Colors:Window]`; o app cai na paleta escura padrão |
| `./gradlew` sem rede falha | o primeiro build baixa Compose de Maven Central e Google; os seguintes usam o cache |
| A pasta não é a esperada | há um `~/.config/docs/root` apontando para outro lugar |
| Um arquivo novo não aparece | a varredura roda ao abrir a janela; use o botão de recarregar (o cabeçalho) |
| O seletor de arquivos não parece o do Dolphin | falta o `kdialog`: `sudo pacman -S kdialog`. Sem ele o app usa o diálogo do Java, que funciona igual e só não segue o tema |
| Importei e o arquivo não foi para o `init` | ele já estava no `init`, é uma pasta (o seletor entrega arquivos) ou o nome estava ocupado — nesse caso ele virou `nome (2).pdf` |
| *Nova pasta…* recusa o caminho | já existe pasta (ou arquivo) com o nome, tem `..`/`~`/pasta oculta ou passa de 5 níveis |
| Uma pasta aparece na árvore com contagem zero | é uma pasta vazia (recém-criada ou esperando arquivos); a árvore vem do disco, não dos documentos |
| Documento listado não abre | não há aplicativo padrão para o tipo; associe-o nas configurações do Plasma |
| As etiquetas sumiram | o `docs.db` foi apagado ou movido; ele não é reconstruível a partir da pasta |
| Criar/aplicar etiqueta não faz nada | o índice em `~/.local/share/docs/` ficou com dono `root` (ex.: app ou shell rodou com sudo); `sudo chown -R "$USER:$USER" ~/.local/share/docs` e reabra o Docs |
| A etiqueta não aparece na árvore | etiqueta sem documento nenhum só existe se já foi criada; crie pelo menu do documento |
| Um arquivo renomeado perdeu as etiquetas | ele foi renomeado **e** editado na mesma ação (tamanho ou data mudaram), então é documento novo; etiquete de novo |
| Renomear deu "documento novo" e a linha antiga ficou `MISSING` | o aplicativo que salvou o arquivo reescreveu a data de modificação; o par tamanho + data não fecha |
| A busca não acha uma palavra que está dentro do PDF | o PDF é um escaneado sem camada de texto e falta `tesseract`, ou a leitura ainda não chegou nele (veja o rodapé) |
| Instalei o Tesseract e nada mudou | o resultado vazio já estava gravado; menu `⋮ → Reler conteúdo` |
| Acentos não casam na busca por conteúdo | o `LIKE` do SQLite ignora maiúsculas só em ASCII; `Declaração` e `declaração` casam, `DECLARAÇÃO` não — digite o termo como está no documento |
| O OCR demora | cada página escaneada leva segundos; são 5 páginas por PDF, em lotes de 25 documentos |
| A sugestão diz "O modelo não respondeu" | o Ollama não está rodando em `http://127.0.0.1:11434` ou o endereço aponta para outro lugar; a sugestão veio do texto |
| A sugestão diz "O serviço não conhece o modelo" | o nome em `model=` não existe no serviço; no DeepSeek use `deepseek-flash` (padrão do app) ou `deepseek-v4-pro`, e confirme com **Testar** |
| **Testar** falha mas a chave parece certa | o teste agora pede uma resposta de verdade: um nome de modelo inválido (ou conta sem crédito) reprova mesmo com a chave boa |
| O DeepSeek responde 401 | `key=` vazio ou inválido, ou o provedor não é DeepSeek; teste com `curl -H "Authorization: Bearer $DEEPSEEK_API_KEY" https://api.deepseek.com/v1/models` |
| O app avisa que a chave está exposta | `~/.config/docs/ai` está legível por outros usuários; rode `chmod 600 ~/.config/docs/ai` |
| Não acho onde configurar a IA | menu `⋮` **da janela** (ao lado do recarregar) → *Inteligência artificial…*; as sugestões ficam no `⋮` de cada documento |
| A IA responde mas a sugestão é genérica | o modelo é pequeno demais para o pedido, ou não há texto lido no documento; troque `model=` por um maior |
| A sugestão de pasta apontava para uma irmã que não tem a ver | a resposta do modelo era a pasta atual e o app trocava por uma irmã; agora o diálogo diz "já está na pasta certa" |
| Nenhuma sugestão aparece | o documento já tem as etiquetas que fariam sentido, ou o nome e o texto não têm palavra aproveitável (tipo `IMG_20240101.pdf`) |
| "Já existe um arquivo com esse nome" ao mover | o destino tem um arquivo com o mesmo nome; o app não sobrescreve — renomeie no Dolphin e tente de novo |
| A pasta do documento novo não aparece na lateral | pasta vazia não entra na árvore; ela aparece quando um documento indexado está dentro |
| A sugestão de pasta nova não é aplicada | o diálogo só escreve no seu clique em *Mover*; se nada aconteceu, veja a mensagem do rodapé (nome ocupado, caminho inválido) |
| Não acho o `init` na barra lateral | pasta vazia não entra na árvore; ela aparece quando você põe um arquivo dentro (ou abra a pasta pelo cabeçalho) |
| Mover disse "o arquivo não está mais no lugar indexado" | o arquivo foi renomeado ou apagado fora do app; use o recarregar do cabeçalho |
| Mover não fez o que eu queria e quero desfazer | o app não desfaz movimento; arraste de volta no Dolphin (etiquetas e favorito continuam no documento) |

## Testes

```bash
cd docs && ./gradlew :app:test
```

Cobrem a resolução da pasta, o parser do `kdeglobals`, a varredura (incluindo
ocultos, links simbólicos e pasta ilegível), o diff do índice (inclusive
reconhecimento de renomeado e de movido entre pastas), a gravação no banco com
etiqueta e favorito preservados, a leitura de texto (limites, arquivo binário,
`docx`/`odt`/`xlsx`/`pptx`, entidades, PDF com e sem camada, OCR, ferramenta
ausente e falha), o índice de conteúdo em lotes, a busca por conteúdo com `%` e
`_` literais, a formatação de tamanho/data, as regras do caminho de etiqueta, a
montagem da árvore com contagem, a árvore mostrando um nível por vez e sabendo
se um caminho existe, a navegação do seletor de pasta (trilha da migalha,
subpasta relativa ao nível aberto e escape da biblioteca recusado), o repositório
de etiquetas, as descrições de
pasta e de etiqueta (gravar, limpar, recusar a raiz e caminho que escapa,
esquecer a subárvore, casar singular e plural com o documento), os dados que vão
no prompt (tipo, tamanho com a mesma régua da lista, datas, data de criação
ausente omitida em vez de virar 1970), a data lida do texto do documento
(`13/11/2026`, `2026 - 2º semestre`, `abril de 2025`, `2024-03-15`, e o
"Data e hora" do envio perdendo para `Data da atividade` / semestre),
a forma de uma etiqueta de data (`2026`, `mes-11`, `11/2026` recusadas;
`unip/2026` mantida; `2025/mes-4` reutilizável), o corte das palavras de
formulário (`aluno`, `atividade`, `matricula`) e do `ata` dentro de `data`, a
leitura do arquivo na hora da sugestão (e a linha vazia que evita reler), as
migrações
1→2, 2→3, 3→4, 4→5 e 5→6, a leitura de JSON sem biblioteca, a heurística de sugestão e o
prompt do modelo (etiqueta e pasta), o cliente do Ollama e o do serviço remoto
(dialeto OpenAI/DeepSeek), a leitura e a escrita do arquivo de IA (a chave
preservada linha a linha, a chave digitada no app gravada `0600`, a remoção, a
linha injetada recusada, a chave fora do `toString`, o arquivo criado `0600`, o
provedor trocado), a troca do cliente a quente, o teste com a chave digitada, a
sugestão com e sem modelo, as regras do caminho de pasta,
o movimento no disco (pasta criada, identidade preservada, recusa de
sobrescrita, de escape da biblioteca e de arquivo ausente), a remoção de pasta
no disco (subárvore inteira, contagem antes de confirmar, raiz e `init`
recusados, escape recusado, link simbólico apagado sem tocar no alvo), a
remoção de etiqueta (subárvore e vínculos, `%` literal, etiqueta inexistente),
a herança de etiquetas por pasta (resolução pelos ancestrais, repositório,
filtro, contagem na barra, picker travado, `init`/raiz recusados, limpeza ao
apagar pasta ou etiqueta),
o movimento de pasta na árvore (filhos no disco, remap de `relPath`,
descrições e `folder_tags`, recusa de `init`/auto-descendente/colisão/profundidade,
seleção que acompanha o caminho novo),
a caixa de entrada
(`init` criado uma vez, o que conta como estar dentro dela, pasta nova criada
ao confirmar a partir do `init`, pasta existente e topo aceitos, e a etiqueta
nova criada só no clique, com o pai junto) e o controller da tela
(busca, ordenação, recência, favoritos, pasta, filtro por etiqueta, criação de
etiqueta, apagar pasta e etiqueta — inclusive o filtro limpo e a volta para
Todos —, descrever pasta e etiqueta, apagar o texto da descrição junto com a
pasta, leitura e releitura do conteúdo, sugestão de etiquetas e de pasta,
movimento e recusa, e o diálogo de IA: abrir sem salvar, testar sem salvar,
salvar aplicando a chave digitada, remover a chave e recusa honesta). O `init`
tem testes próprios: sugestão nova criada no clique e explicada, o `init` fora
da lista de destinos, etiqueta existente e nova aplicadas, e digitação de
etiqueta dentro do `init` valendo como no resto da biblioteca.

## Próximas etapas

1. Etiquetar e mover em lote: selecionar vários documentos e aplicar de uma vez.
2. Renomear e apagar etiquetas, e arrastar documento para a etiqueta ou pasta.
3. Desfazer o último movimento.
4. `FTS5` para a busca por conteúdo, com o trecho que casou na lista.
5. Reconhecer renomeado **e** editado no mesmo passo, comparando o começo do
   conteúdo quando tamanho e data não bastam (hoje essa troca vira documento
   novo).
6. Watcher (`inotify`) com reconciliação por hash de conteúdo.
7. Widget do Plasma e `docs-toggle` no painel.
8. Sincronização com o Android pelo serviço central do monorepo.

## Licença

GNU GPL v3.0 — veja [`../LICENSE`](../LICENSE). Projeto próprio do monorepo
(não é fork), então o pacote e os créditos são nossos.
