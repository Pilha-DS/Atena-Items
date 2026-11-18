/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.openappslabs.docs.data.ai.ModelIssue
import com.openappslabs.docs.data.ai.ModelTrouble
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.ui.RECENT_WINDOW_DAYS

/**
 * User-facing text lives here, never inline in the screens.
 *
 * Only pt-BR ships today; the interface exists so a second language is a new
 * object instead of a search-and-replace across the UI.
 */
interface DocsStrings {
    val appName: String
    val searchPlaceholder: String
    val emptyTitle: String
    val emptyBody: String
    val noResultsTitle: String
    val noResultsBody: String
    val libraryFolder: String
    val open: String
    val openFolder: String
    val revealInFolder: String
    val copyPath: String
    val copiedPath: String
    val refresh: String
    val sortName: String
    val sortRecent: String
    val scanning: String
    val neverIndexed: String
    val openFileFailed: String
    val openFolderFailed: String
    val copyPathFailed: String
    val shortcutHint: String
    val tagsTitle: String
    val allDocuments: String
    val noTags: String
    val tagAction: String
    val tagEditorTitle: String
    val newTagPlaceholder: String
    val tagCreate: String
    val tagHint: String
    val tagInvalid: String
    val tagApplyFailed: String
    val close: String
    val navigateTitle: String
    val recent: String
    val favorites: String
    val foldersTitle: String
    val expandFolder: String
    val collapseFolder: String
    /** Content description of the narrow grip that starts a folder drag. */
    val dragFolderHandle: String
    val favoriteAdd: String
    val favoriteRemove: String
    val recentFilterHint: String
    val favoritesFilterHint: String
    val recentEmptyBody: String
    val readContent: String
    val rereadContent: String
    val readingContent: String
    val suggestAction: String
    val suggestTitle: String
    val suggestApply: String
    val suggestThinking: String
    val suggestEmpty: String
    val suggestModelOffline: String
    val suggestFailed: String
    val suggestTagNew: String
    /** The model answered, but only with tags the document already carries. */
    val suggestOnlyApplied: String
    val apiKeyExposed: String
    val aiMenu: String
    val aiTitle: String
    val aiIntro: String
    val aiLocal: String
    val aiLocalDetail: String
    val aiRemote: String
    val aiRemoteDetail: String
    val aiModel: String
    val aiModelHint: String
    /** Model names the remote service accepts; the field cannot guess them. */
    val aiRemoteModelHint: String
    val aiModelAny: String
    /** Label above the Ollama size quick-picks. */
    val aiModelSuggestions: String
    val aiUrl: String
    val aiKeyPresent: String
    val aiKeyAbsent: String
    val aiKey: String
    val aiKeyKeep: String
    val aiKeyPaste: String
    val aiKeyStored: String
    val aiKeyShow: String
    val aiKeyHide: String
    val aiKeyRemove: String
    val aiKeyRemoved: String
    val aiTest: String
    val aiTestOk: String
    val aiTestFailed: String
    val aiSave: String
    val aiSaved: String
    val aiSaveFailed: String
    val ocrUnavailable: String
    val noTextYet: String
    val suggestFolderAction: String
    val suggestFolderTitle: String
    val suggestFolderThinking: String
    val suggestFolderEmpty: String
    val suggestFolderModelOffline: String
    val suggestFolderNew: String
    /** The model says the document is already in the folder it belongs to. */
    val suggestFolderStay: String
    val suggestFolderList: String
    val suggestFolderApply: String
    val folderRoot: String

    /** Walking the folder tree instead of typing a path. */
    val chooseFolderTitle: String
    val chooseFolderEnter: String
    val chooseFolderEmpty: String
    val chooseFolderNewPlaceholder: String
    val folderMoveAlreadyThere: String
    val folderMoveInvalid: String
    val folderMoveSourceMissing: String
    val folderMoveTargetTaken: String
    val folderMoveFailed: String
    val folderRootReason: String

    /** Importing files chosen on the computer into the inbox. */
    val importAction: String
    val importTitle: String
    val importing: String

    /** Sending a document back to `init`. */
    val moveToInbox: String

    /** Moving a document by walking the folder tree — no AI. */
    val moveDocAction: String
    val moveDocTitle: String
    val moveDocHint: String
    val moveDocApply: String

    /** Creating a folder from the window. */
    val newFolderAction: String
    val newFolderTitle: String
    val newFolderHint: String
    val newFolderPlaceholder: String
    val newFolderCreate: String
    val newFolderInvalid: String
    val newFolderExists: String
    val newFolderFailed: String
    val noFolders: String

    /** Deleting a folder, behind a typed confirmation. */
    val deleteAction: String
    val deleteFolderTitle: String
    val deleteFolderCounting: String
    val deleteFolderConfirm: String
    val deleteFolderInvalid: String
    val deleteFolderMissing: String
    val deleteFolderRoot: String
    val deleteFolderInbox: String

    /**
     * Moving a folder by dropping it on another (or on "Todos" for the root).
     * Separate from [folderMoved] / [folderMoveFailed]: those talk about a
     * *document* landing in a folder.
     */
    val folderTreeMoveInvalid: String
    val folderTreeMoveMissing: String
    val folderTreeMoveRoot: String
    val folderTreeMoveInbox: String
    val folderTreeMoveIntoSelf: String
    val folderTreeMoveTargetTaken: String
    val folderTreeMoveTooDeep: String
    val folderTreeMoveFailed: String

    /** Deleting a tag and everything below it. */
    val deleteTagTitle: String
    val deleteTagConfirm: String
    val deleteTagFailed: String

    /** Renaming a tag path (and its subtree). */
    val renameTagAction: String
    val renameTagTitle: String
    val renameTagPlaceholder: String
    val renameTagConfirm: String
    val renameTagFailed: String
    fun renameTagBody(path: String): String
    fun tagRenamed(from: String, to: String): String

    /**
     * The sentence the user writes about a folder or a tag, which the AI reads
     * before suggesting anything.
     */
    val moreActions: String
    val describeAction: String
    val describeFolderTitle: String
    val describeTagTitle: String
    val describeFolderHint: String
    val describeTagHint: String
    val describeFolderPlaceholder: String
    val describeTagPlaceholder: String
    val describeClearHint: String
    val describeSave: String
    val describeSaved: String
    val describeRemoved: String
    val describeFailed: String

    /** Search nicknames of a tag (`nfe` for `nota-fiscal`). */
    val tagAliasesAction: String
    val tagAliasesTitle: String
    val tagAliasesHint: String
    val tagAliasesPlaceholder: String
    val tagAliasesAdd: String
    val tagAliasesRemove: String
    val tagAliasesEmpty: String
    val tagAliasesInvalid: String
    val tagAliasesFailed: String

    /**
     * Tags a folder passes to every document under it (including subfolders).
     */
    val inheritTagsAction: String
    val inheritTagsTitle: String
    val inheritTagsHint: String
    val inheritTagsSave: String
    val inheritTagsSaved: String
    val inheritTagsCleared: String
    val inheritTagsFailed: String
    val inheritTagNew: String
    /** Adds a tag to the inherit checklist (creates it in the index immediately). */
    val inheritTagAdd: String
    fun inheritTagCreated(tag: String): String
    val tagInheritedHint: String
    val tagInheritedLabel: String
    /** Filters the tag checklist in pickers (inherit / document). */
    val tagListFilter: String
    val tagListFilterEmpty: String

    fun documents(count: Int): String
    fun lastScan(time: String): String
    fun kindLabel(kind: DocKind): String
    fun tagFilterHint(tags: Set<String>): String
    fun tagCreated(tag: String): String
    fun folderFilterHint(folder: String): String
    fun renamedSuffix(count: Int): String
    fun textIndexed(count: Int): String
    fun suggestionsApplied(count: Int): String
    fun folderCreated(path: String): String
    fun importReport(imported: Int, renamed: Int, skipped: Int, failed: Int): String
    fun suggestFolderCurrent(folder: String): String
    fun folderMoved(target: String): String
    /** Snackbar after a whole folder was moved by drag-and-drop. */
    fun folderTreeMoved(path: String): String
    fun chooseFolderDestination(target: String): String
    fun aiKeyWhere(path: String): String

    /** What the folder deletion will take with it. */
    fun deleteFolderBody(path: String, files: Int): String

    /** "Digite o nome da pasta (Certificados) para confirmar". */
    fun deleteFolderType(name: String): String

    /** What the tag deletion will take with it. */
    fun deleteTagBody(name: String, documents: Int, nested: Int): String

    fun folderDeleted(path: String, files: Int): String

    /** A partial delete: [files] went before the filesystem refused. */
    fun folderDeleteFailed(files: Int): String

    fun tagRemoved(name: String, documents: Int): String

    /**
     * One sentence for a model failure, naming what the user has to fix.
     *
     * "IA indisponível" covers an endless list of causes; the whole point of
     * [ModelTrouble] is to say which one.
     */
    fun modelTrouble(trouble: ModelTrouble): String
}

object PtBrDocsStrings : DocsStrings {
    override val appName = "Docs"
    override val searchPlaceholder = "Busca fuzzy: nome, etiqueta, metadados…"
    override val emptyTitle = "Nenhum documento nesta pasta"
    override val emptyBody =
        "Esta pasta é a fonte da verdade: o Docs lê os arquivos que já estão nela e só " +
            "mexe neles quando você confirma uma sugestão de pasta. Despeje o que ainda " +
            "vai organizar em init/ e recarregue."
    override val noResultsTitle = "Nenhum documento com esse termo"
    override val noResultsBody =
        "A busca olha o nome do arquivo e o texto já lido de cada documento. " +
            "Textos ainda não lidos aparecem no rodapé como “lendo conteúdo”."
    override val libraryFolder = "Pasta da biblioteca"
    override val open = "Abrir"
    override val openFolder = "Abrir pasta"
    override val revealInFolder = "Mostrar no Dolphin"
    override val copyPath = "Copiar caminho"
    override val copiedPath = "Caminho copiado"
    override val refresh = "Recarregar"
    override val sortName = "Nome"
    override val sortRecent = "Recentes"
    override val scanning = "Indexando…"
    override val neverIndexed = "nunca indexado"
    override val openFileFailed = "Não foi possível abrir o arquivo"
    override val openFolderFailed = "Não foi possível abrir a pasta"
    override val copyPathFailed = "Não foi possível copiar o caminho"
    override val shortcutHint = "Super+Shift+D abre e fecha esta janela"
    override val tagsTitle = "Etiquetas"
    override val allDocuments = "Todos os documentos"
    override val noTags = "Nenhuma etiqueta ainda"
    override val tagAction = "Etiquetar…"
    override val tagEditorTitle = "Etiquetas do documento"
    override val newTagPlaceholder = "nova etiqueta (use / para subnível)"
    override val tagCreate = "Criar e aplicar"
    override val tagHint = "Marcar a etiqueta pai mostra também tudo o que está abaixo dela."
    override val tagInvalid = "Etiqueta inválida: até 5 níveis, 32 caracteres por nível"
    override val tagApplyFailed = "Não foi possível alterar a etiqueta"
    override val close = "Fechar"
    override val navigateTitle = "Navegar"
    override val recent = "Recentes"
    override val favorites = "Favoritos"
    override val foldersTitle = "Pastas"
    override val expandFolder = "Expandir pasta"
    override val collapseFolder = "Recolher pasta"
    override val dragFolderHandle = "Arrastar pasta"
    override val favoriteAdd = "Favoritar"
    override val favoriteRemove = "Remover dos favoritos"
    override val recentFilterHint = "modificados nos últimos $RECENT_WINDOW_DAYS dias"
    override val favoritesFilterHint = "somente os favoritos"
    override val recentEmptyBody =
        "Nada mudou nos últimos $RECENT_WINDOW_DAYS dias. A data é a do arquivo no disco."
    override val readContent = "Ler conteúdo"
    override val rereadContent = "Reler conteúdo"
    override val readingContent = "lendo conteúdo…"
    override val suggestAction = "Sugerir etiquetas"
    override val suggestTitle = "Etiquetas sugeridas"
    override val suggestApply = "Aplicar selecionadas"
    override val suggestThinking = "Consultando o modelo local…"
    override val suggestEmpty = "Não deu para tirar etiqueta do nome nem do texto deste documento."
    override val suggestModelOffline =
        "IA indisponível ou sem chave; as sugestões vieram do texto e das etiquetas que você já tem."
    override val suggestFailed = "Não foi possível sugerir etiquetas agora."
    override val suggestTagNew = "nova — será criada"
    override val suggestOnlyApplied =
        "O modelo só repetiu etiquetas que o documento já tem; as de baixo seriam novas."
    override val apiKeyExposed =
        "A chave da IA está em ~/.config/docs/ai legível por outros usuários: use chmod 600 nesse arquivo"
    override val aiMenu = "Inteligência artificial…"
    override val aiTitle = "Inteligência artificial"
    override val aiIntro =
        "Onde a IA roda. As sugestões são sempre suas para aceitar."
    override val aiLocal = "Nesta máquina (Ollama)"
    override val aiLocalDetail =
        "Nada sai do computador. Precisa do Ollama rodando; sem ele, a sugestão vem do texto."
    override val aiRemote = "Serviço remoto (DeepSeek)"
    override val aiRemoteDetail =
        "Um trecho do documento é enviado pela rede. Precisa de chave configurada no arquivo."
    override val aiModel = "Modelo"
    override val aiModelHint =
        "Recomendado para etiquetas: qwen2.5:7b. Em branco, o Ollama usa o primeiro instalado."
    override val aiRemoteModelHint =
        "No DeepSeek os nomes válidos são deepseek-flash e deepseek-v4-pro."
    override val aiModelAny = "o primeiro instalado"
    override val aiModelSuggestions = "Atalhos (precisa estar no Ollama)"
    override val aiUrl = "Endereço"
    override val aiKeyPresent = "Chave: configurada."
    override val aiKeyAbsent = "Chave: não configurada."
    override val aiKey = "Chave"
    override val aiKeyKeep = "deixe em branco para manter a que já está salva"
    override val aiKeyPaste = "cole a chave do serviço"
    override val aiKeyStored = "Chave já configurada. Digite outra para substituir."
    override val aiKeyShow = "Mostrar a chave enquanto digito"
    override val aiKeyHide = "Ocultar a chave"
    override val aiKeyRemove = "Remover chave"
    override val aiKeyRemoved = "Chave removida"
    override val aiTest = "Testar"
    override val aiTestOk = "Respondeu"
    override val aiTestFailed = "Sem resposta"
    override val aiSave = "Salvar"
    override val aiSaved = "Configuração da IA salva"
    override val aiSaveFailed = "Não foi possível salvar a configuração da IA"
    override val ocrUnavailable = "OCR indisponível: instale tesseract e tesseract-data-por"
    override val noTextYet = "nenhum texto lido ainda"
    override val suggestFolderAction = "Sugerir pasta…"
    override val suggestFolderTitle = "Pasta sugerida"
    override val suggestFolderThinking = "Consultando o modelo local…"
    override val suggestFolderEmpty = "Não deu para tirar uma pasta do nome nem do texto deste documento."
    override val suggestFolderModelOffline =
        "IA indisponível ou sem chave; as sugestões vieram das pastas e do texto."
    override val suggestFolderNew = "nova — será criada"
    override val suggestFolderStay = "O modelo diz que o documento já está na pasta certa."
    override val suggestFolderList = "Sugestões"
    override val suggestFolderApply = "Mover"
    override val folderRoot = "topo da biblioteca"
    override val chooseFolderTitle = "Ou escolha pela árvore"
    override val chooseFolderEnter = "Entrar na pasta"
    override val chooseFolderEmpty = "Nenhuma subpasta aqui"
    override val chooseFolderNewPlaceholder = "pasta nova aqui (só o nome)"
    override val folderMoveAlreadyThere = "O documento já está nessa pasta"
    override val folderMoveInvalid =
        "Pasta inválida: use no máximo 5 níveis, sem pasta oculta e sem .."
    override val folderMoveSourceMissing =
        "O arquivo não está mais no lugar indexado; recarregue a pasta"
    override val folderMoveTargetTaken = "Já existe um arquivo com esse nome na pasta de destino"
    override val folderMoveFailed = "Não foi possível mover o arquivo"
    override val folderRootReason = "topo da biblioteca"
    override val importAction = "Importar…"
    override val importTitle = "Escolher arquivos para o init"
    override val importing = "importando…"
    override val moveToInbox = "Mover para o init"
    override val moveDocAction = "Mover…"
    override val moveDocTitle = "Mover arquivo"
    override val moveDocHint =
        "Entre na pasta de destino. Digite um nome só se a pasta ainda não existir."
    override val moveDocApply = "Mover"
    override val newFolderAction = "Nova pasta…"
    override val newFolderTitle = "Nova pasta"
    override val newFolderHint =
        "A pasta é criada dentro da biblioteca e pode ter subníveis; nada é criado sem você mandar."
    override val newFolderPlaceholder = "faculdade/2026/semestre-1"
    override val newFolderCreate = "Criar"
    override val newFolderInvalid =
        "Pasta inválida: até 5 níveis, 64 caracteres por nível, sem pasta oculta e sem .."
    override val newFolderExists = "Já existe uma pasta (ou um arquivo) com esse nome"
    override val newFolderFailed = "Não foi possível criar a pasta"
    override val noFolders = "Nenhuma pasta ainda"
    override val deleteAction = "Apagar"
    override val deleteFolderTitle = "Apagar pasta"
    override val deleteFolderCounting = "contando o que tem dentro…"
    override val deleteFolderConfirm = "Apagar pasta"
    override val deleteFolderInvalid = "Não é possível apagar esse caminho"
    override val deleteFolderMissing = "A pasta não existe mais no disco"
    override val deleteFolderRoot = "O topo da biblioteca não pode ser apagado"
    override val deleteFolderInbox =
        "A init é recriada a cada varredura; apague uma subpasta dela, não ela"
    override val folderTreeMoveInvalid =
        "Não é possível mover para esse caminho (até 5 níveis, sem pasta oculta e sem ..)"
    override val folderTreeMoveMissing = "A pasta não existe mais no disco"
    override val folderTreeMoveRoot = "O topo da biblioteca não pode ser arrastado"
    override val folderTreeMoveInbox =
        "A init não se move nem recebe pastas; use uma subpasta dela"
    override val folderTreeMoveIntoSelf =
        "Não dá para soltar uma pasta dentro dela mesma"
    override val folderTreeMoveTargetTaken =
        "Já existe uma pasta (ou um arquivo) com esse nome no destino"
    override val folderTreeMoveTooDeep =
        "A pasta ficaria com mais de 5 níveis"
    override val folderTreeMoveFailed = "Não foi possível mover a pasta"
    override val deleteTagTitle = "Apagar etiqueta"
    override val deleteTagConfirm = "Apagar etiqueta"
    override val deleteTagFailed = "Não foi possível apagar a etiqueta"
    override val renameTagAction = "Renomear…"
    override val renameTagTitle = "Renomear etiqueta"
    override val renameTagPlaceholder = "novo caminho (use / para subnível)"
    override val renameTagConfirm = "Renomear"
    override val renameTagFailed =
        "Não foi possível renomear (caminho inválido ou já existe outra com esse nome)"
    override val moreActions = "Mais ações"
    override val describeAction = "Descrever…"
    override val describeFolderTitle = "Descrever pasta"
    override val describeTagTitle = "Descrever etiqueta"
    override val describeFolderHint =
        "A IA lê esta frase antes de sugerir uma pasta. Diga o que guarda aqui, com suas palavras."
    override val describeTagHint =
        "A IA lê esta frase antes de sugerir etiquetas. Diga o que esta etiqueta significa."
    override val describeFolderPlaceholder = "certificados de cursos de extensão, com data e carga horária"
    override val describeTagPlaceholder = "tudo que é da faculdade, de qualquer semestre"
    override val describeClearHint = "Deixe em branco para apagar a descrição."
    override val describeSave = "Salvar"
    override val describeSaved = "Descrição salva"
    override val describeRemoved = "Descrição apagada"
    override val describeFailed = "Não foi possível salvar a descrição"
    override val tagAliasesAction = "Subnomes…"
    override val tagAliasesTitle = "Subnomes de pesquisa"
    override val tagAliasesHint =
        "Apelidos que a busca também encontra. Não mudam o nome da etiqueta."
    override val tagAliasesPlaceholder = "nfe"
    override val tagAliasesAdd = "Adicionar"
    override val tagAliasesRemove = "Remover subnome"
    override val tagAliasesEmpty = "Nenhum subnome ainda"
    override val tagAliasesInvalid =
        "Subnome inválido: até 32 caracteres, sem / e diferente do nome da etiqueta"
    override val tagAliasesFailed = "Não foi possível salvar o subnome"

    override val inheritTagsAction = "Herdar etiquetas…"
    override val inheritTagsTitle = "Herdar etiquetas"
    override val inheritTagsHint =
        "Documentos nesta pasta e nas subpastas recebem estas etiquetas. " +
            "Sair da pasta deixa de herdar; etiquetas aplicadas à mão no documento continuam."
    override val inheritTagsSave = "Salvar"
    override val inheritTagsSaved = "Herança de etiquetas salva"
    override val inheritTagsCleared = "Herança de etiquetas removida"
    override val inheritTagsFailed = "Não foi possível salvar a herança de etiquetas"
    override val inheritTagNew = "nova"
    override val inheritTagAdd = "Adicionar"
    override fun inheritTagCreated(tag: String): String = "Etiqueta #$tag criada"
    override val tagInheritedHint =
        "Etiquetas marcadas como herdadas vêm da pasta; altere-as no menu da pasta."
    override val tagInheritedLabel = "herdada"
    override val tagListFilter = "filtrar etiquetas…"
    override val tagListFilterEmpty = "Nenhuma etiqueta combina com o filtro"

    override fun documents(count: Int): String =
        if (count == 1) "1 documento" else "$count documentos"

    override fun lastScan(time: String): String = "atualizado às $time"

    override fun tagFilterHint(tags: Set<String>): String =
        "filtrando por " + tags.sorted().joinToString(" · ") { "#$it" }

    override fun tagCreated(tag: String): String = "Etiqueta #$tag aplicada"

    override fun folderFilterHint(folder: String): String = "pasta $folder"

    override fun renamedSuffix(count: Int): String =
        if (count == 1) " · 1 renomeado" else " · $count renomeados"

    override fun textIndexed(count: Int): String =
        if (count == 1) "texto em 1 documento" else "texto em $count documentos"

    override fun suggestionsApplied(count: Int): String =
        if (count == 1) "1 etiqueta aplicada" else "$count etiquetas aplicadas"

    override fun folderCreated(path: String): String = "Pasta criada: $path"

    override fun importReport(imported: Int, renamed: Int, skipped: Int, failed: Int): String =
        buildString {
            append(
                when (imported) {
                    0 -> "Nenhum arquivo foi para o init"
                    1 -> "1 arquivo foi para o init"
                    else -> "$imported arquivos foram para o init"
                }
            )
            if (renamed > 0) {
                append(" · ").append(if (renamed == 1) "1 renomeado" else "$renamed renomeados")
            }
            if (skipped > 0) {
                append(" · ").append(if (skipped == 1) "1 ignorado" else "$skipped ignorados")
            }
            if (failed > 0) {
                append(" · ").append(if (failed == 1) "1 falhou" else "$failed falharam")
            }
        }

    override fun suggestFolderCurrent(folder: String): String =
        "pasta atual: ${folder.ifEmpty { folderRoot }}"

    override fun folderMoved(target: String): String =
        if (target.isEmpty()) "Movido para o $folderRoot" else "Movido para $target"

    override fun folderTreeMoved(path: String): String = "Pasta movida: $path"

    override fun chooseFolderDestination(target: String): String = "vai para: $target"

    override fun aiKeyWhere(path: String): String =
        "Vai para a linha key= em $path (só o seu usuário lê), " +
            "ou use DEEPSEEK_API_KEY exportada antes de abrir."

    override fun deleteFolderBody(path: String, files: Int): String = when (files) {
        0 -> "A pasta $path está vazia. Ela será apagada do disco."
        1 -> "A pasta $path e o arquivo dentro dela serão apagados do disco. Não há como desfazer."
        else -> "A pasta $path e os $files arquivos dentro dela serão apagados do disco. " +
            "Não há como desfazer."
    }

    override fun deleteFolderType(name: String): String =
        "Digite o nome da pasta ($name) para confirmar"

    override fun deleteTagBody(name: String, documents: Int, nested: Int): String = buildString {
        if (documents == 0) {
            append("A etiqueta #").append(name).append(" não está em nenhum documento.")
        } else if (documents == 1) {
            append("A etiqueta #").append(name).append(" será removida de 1 documento.")
        } else {
            append("A etiqueta #").append(name).append(" será removida de ")
            append(documents).append(" documentos.")
        }
        if (nested > 0) {
            append(" ").append(
                if (nested == 1) {
                    "A subetiqueta também será apagada."
                } else {
                    "As $nested subetiquetas também serão apagadas."
                }
            )
        }
    }

    override fun renameTagBody(path: String): String =
        "O caminho #$path muda, e as subetiquetas acompanham. Documentos e subnomes " +
            "continuam ligados a ela."

    override fun tagRenamed(from: String, to: String): String =
        "Etiqueta #$from renomeada para #$to"

    override fun folderDeleted(path: String, files: Int): String = when (files) {
        0 -> "Pasta apagada: $path"
        1 -> "Pasta apagada: $path (1 arquivo)"
        else -> "Pasta apagada: $path ($files arquivos)"
    }

    override fun folderDeleteFailed(files: Int): String = if (files == 0) {
        "Não foi possível apagar a pasta"
    } else if (files == 1) {
        "A pasta não pôde ser apagada por inteiro; 1 arquivo já saiu"
    } else {
        "A pasta não pôde ser apagada por inteiro; $files arquivos já saíram"
    }

    override fun tagRemoved(name: String, documents: Int): String = when (documents) {
        0 -> "Etiqueta #$name apagada"
        1 -> "Etiqueta #$name apagada (1 documento)"
        else -> "Etiqueta #$name apagada ($documents documentos)"
    }

    override fun modelTrouble(trouble: ModelTrouble): String = when (trouble.issue) {
        ModelIssue.NO_KEY ->
            "A IA não vai rodar: falta a chave. Abra ⋮ → Inteligência artificial… e cole a chave."
        ModelIssue.UNREACHABLE ->
            if (trouble.url.isBlank()) {
                "O modelo não respondeu. Se for o Ollama, ele precisa estar rodando (`ollama serve`)."
            } else {
                "O modelo não respondeu em ${trouble.url}. " +
                    "Se for o Ollama, ele precisa estar rodando (`ollama serve`)."
            }
        ModelIssue.UNAUTHORIZED ->
            "A chave foi recusada (HTTP ${trouble.status}). " +
                "Confira em ⋮ → Inteligência artificial… e use Testar."
        ModelIssue.MODEL_REFUSED ->
            "O serviço não conhece o modelo \"${trouble.model}\" (HTTP ${trouble.status})." +
                if (trouble.url.contains("deepseek", ignoreCase = true)) {
                    " No DeepSeek use deepseek-flash ou deepseek-v4-pro."
                } else {
                    " Troque o modelo em ⋮ → Inteligência artificial…"
                }
        ModelIssue.SERVER_ERROR ->
            "O serviço respondeu HTTP ${trouble.status}; tente de novo em instantes."
        ModelIssue.NO_MODEL ->
            "Nenhum modelo instalado em ${trouble.url}. " +
                "Baixe um (`ollama pull llama3.1`) ou escolha outro em ⋮ → Inteligência artificial…"
        ModelIssue.BAD_ANSWER ->
            "O modelo respondeu algo que não virou sugestão; as sugestões abaixo vieram do texto."
    }

    override fun kindLabel(kind: DocKind): String = when (kind) {
        DocKind.PDF -> "PDF"
        DocKind.WORD -> "Documento"
        DocKind.SHEET -> "Planilha"
        DocKind.SLIDES -> "Apresentação"
        DocKind.TEXT -> "Texto"
        DocKind.MARKDOWN -> "Markdown"
        DocKind.IMAGE -> "Imagem"
        DocKind.AUDIO -> "Áudio"
        DocKind.VIDEO -> "Vídeo"
        DocKind.ARCHIVE -> "Arquivo compactado"
        DocKind.CODE -> "Código"
        DocKind.OTHER -> "Arquivo"
    }
}

val LocalDocsStrings = staticCompositionLocalOf<DocsStrings> { PtBrDocsStrings }
