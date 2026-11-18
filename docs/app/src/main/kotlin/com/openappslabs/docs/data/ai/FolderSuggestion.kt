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

package com.openappslabs.docs.data.ai

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.Inbox

/**
 * A folder the document could move to.
 *
 * [target] is relative to the library root, `""` meaning the root itself —
 * the same spelling [FolderPath] uses, so nothing has to translate between
 * "suggestion" and "destination".
 */
data class FolderSuggestion(
    val target: String,
    val reason: String,
    /** True when the folder does not exist yet and will be created. */
    val isNew: Boolean,
    /** True when a local model proposed it, false when an existing folder matched. */
    val fromModel: Boolean
)

/**
 * Suggestions from the text alone, without any model.
 *
 * The library already has a shape the user built: pointing at the folder that
 * repeats a word of the file name is the cheapest useful advice, and it never
 * invents a directory. Inventing one is the model's job, and it comes with a
 * confirmation step.
 */
object HeuristicFolders {

    fun suggest(
        doc: Doc,
        text: String,
        existing: List<String>,
        limit: Int = 3,
        /**
         * What the user said each folder is for, keyed by folder path.
         *
         * The description is the user's own definition, so a match against it
         * is worth more than a match against the folder name — and a folder
         * whose name has nothing to do with the file still becomes a candidate
         * when its description mentions it. This is what keeps the suggestion
         * useful without a model.
         */
        descriptions: Map<String, String> = emptyMap()
    ): List<FolderSuggestion> {
        if (existing.isEmpty()) return emptyList()

        val name = HeuristicTagger.fold("${doc.name} ${doc.folder.replace('/', ' ')}")
        val body = HeuristicTagger.fold(text.take(MAX_SCAN_CHARS))
        if (name.isBlank() && body.isBlank()) return emptyList()
        // Built once: every folder is compared against the same document words.
        val documentWords = HeuristicTagger.wordSet("$name $body")

        val current = doc.folder
        return existing
            .asSequence()
            .filter { it.isNotEmpty() && !it.equals(current, ignoreCase = true) }
            // A parent of the current folder is a step *up*, not a place this
            // document belongs: suggesting it moves the file out of the branch
            // the user already chose.
            .filterNot { isAncestorOf(it, current) }
            .distinct()
            .mapNotNull { folder ->
                score(folder, name, body, noteFor(folder, descriptions), documentWords)
                    ?.let { folder to it }
            }
            .sortedWith(compareByDescending<Pair<String, Score>> { it.second.total }.thenBy { it.first })
            .take(limit)
            .map { (folder, score) ->
                FolderSuggestion(
                    target = folder,
                    reason = score.reason,
                    isNew = false,
                    fromModel = false
                )
            }
            .toList()
    }

    private data class Score(val total: Int, val reason: String)

    /** The description written for [folder], folded, or "" when there is none. */
    private fun noteFor(folder: String, descriptions: Map<String, String>): String =
        descriptions.entries
            .firstOrNull { it.key.equals(folder, ignoreCase = true) }
            ?.value
            ?.let(HeuristicTagger::fold)
            .orEmpty()

    private fun score(
        folder: String,
        name: String,
        body: String,
        note: String,
        documentWords: Set<String>
    ): Score? {
        // The last segment is what names the folder; the parents describe the
        // branch and are matched too, with less weight.
        val segments = folder.split(FolderPath.SEPARATOR)
            .map { HeuristicTagger.fold(it) }
            .filter { it.length >= MIN_WORD }
        val leaf = segments.lastOrNull().orEmpty()
        // A match on a parent alone says nothing about *this* folder: for a
        // certificate in `Extensao/Certificados`, the word `extensao` would
        // otherwise point at the sibling `Extensao/Original`, which has nothing
        // to do with the file.
        val leafInName = leaf.isNotEmpty() && name.contains(leaf)
        val leafInBody = leaf.isNotEmpty() && body.contains(leaf)
        // The description is matched word by word, with the plural folded away:
        // `certificado` in the file name and `certificados` in the note are the
        // same subject. A short word (`de`, `a`) would match everything.
        val shared = HeuristicTagger.wordSet(note).filter { it in documentWords }
        if (!leafInName && !leafInBody && shared.isEmpty()) return null

        var inName = 0
        var inBody = 0
        segments.forEachIndexed { index, word ->
            // Deeper segments are more specific: `2026` under `faculdade`
            // says more about the file than `faculdade` alone.
            val weight = if (index == segments.lastIndex) 2 else 1
            if (name.contains(word)) inName += weight
            if (body.contains(word)) inBody += weight
        }
        val noteInName = shared.count { name.contains(it) }
        val noteInBody = shared.size - noteInName
        // A description match outranks a name match: it is the user saying what
        // the folder is for, not the app reading a word off the folder title.
        val total = inName * 3 + inBody + noteInName * 4 + noteInBody * 2
        val reason = when {
            noteInName > 0 && (leafInName || leafInBody) ->
                "o arquivo combina com a descrição da pasta"
            noteInName > 0 -> "a descrição da pasta cita o nome do arquivo"
            noteInBody > 0 && (leafInName || leafInBody) ->
                "o texto e a descrição da pasta combinam"
            noteInBody > 0 -> "a descrição da pasta cita o texto"
            inName > 0 && inBody > 0 -> "o nome e o texto citam essa pasta"
            inName > 0 -> "o nome do arquivo cita essa pasta"
            else -> "o texto cita essa pasta"
        }
        return Score(total, reason)
    }

    /** True when [candidate] is a folder above [current] in the same branch. */
    private fun isAncestorOf(candidate: String, current: String): Boolean =
        current.length > candidate.length &&
            current.startsWith(candidate, ignoreCase = true) &&
            current[candidate.length] == FolderPath.SEPARATOR

    private const val MIN_WORD = 3
    private const val MAX_SCAN_CHARS = 20_000

    /**
     * A folder that does not exist yet, taken from the file name.
     *
     * The counterpart of [HeuristicTagger.proposeNew] for the tree, and the
     * answer to "the library has no folder for this": without a model the app
     * used to say nothing at all. One word of the name — `Contrato de
     * aluguel.pdf` suggests `Contrato` — placed under the folder the document
     * is already in, so confirming does not drag it across the tree.
     *
     * One word only: a folder named after a whole file name is not a place, it
     * is a copy of the file.
     */
    fun proposeNew(
        doc: Doc,
        existing: List<String>,
        limit: Int = 2
    ): List<FolderSuggestion> {
        if (limit <= 0) return emptyList()
        val known = existing.mapNotNull { FolderPath.normalize(it) }.filter { it.isNotEmpty() }
        val segments = known
            .flatMap { it.split(FolderPath.SEPARATOR) }
            .map { it.lowercase() }
            .toSet()
        // Below the document's own folder: a new folder is a place to keep the
        // file, not a reason to move it to another branch.
        val parent = FolderPath.normalize(doc.folder).orEmpty().takeIf { !Inbox.holds(it) }.orEmpty()

        val result = ArrayList<FolderSuggestion>()
        for (word in HeuristicTagger.meaningfulWords(stemOf(doc.name))) {
            if (result.size >= limit) break
            val folded = word.lowercase()
            if (folded in segments) continue
            val target = FolderPath.normalize(FolderPath.join(parent, word)) ?: continue
            if (target.isEmpty() || Inbox.holds(target)) continue
            if (target.equals(doc.folder, ignoreCase = true)) continue
            if (result.any { it.target.equals(target, ignoreCase = true) }) continue
            result += FolderSuggestion(
                target = target,
                reason = "pasta nova: o nome do arquivo sugere",
                isNew = true,
                fromModel = false
            )
        }
        return result
    }

    /** `relatorio.pdf` -> `relatorio`. A dotfile keeps its whole name. */
    private fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}

/**
 * The prompt handed to the model for a folder, kept apart so the wording is
 * testable and visible.
 *
 * It names the current folder (so the answer is not "stay where you are"),
 * lists the folders that exist **with a sample of what is inside each one**
 * (reuse beats invention, and a name alone cannot tell `Original` from
 * `Certificados`) and asks for one path only — a folder suggestion is a choice,
 * not a list.
 */
object FolderPrompt {

    const val MAX_TEXT_CHARS = 2_500
    const val MAX_FOLDERS = 120

    /** How many documents of a folder are shown as a hint of its contents. */
    const val MAX_CONTENT_HINTS = 3
    const val MAX_HINT_CHARS = 60

    /**
     * How much of a description goes into the prompt.
     *
     * A description is a sentence, not a paragraph: the user writes it in a
     * dialog field, and the cap keeps one verbose note from crowding out the
     * folder list of a large library.
     */
    const val MAX_DESCRIPTION_CHARS = 90

    /** What the model should answer to mean "the top of the library". */
    const val ROOT_ANSWER = "raiz"

    fun build(
        doc: Doc,
        text: String,
        existing: List<String>,
        /**
         * Names of the documents each folder holds, keyed by folder path.
         *
         * The folder list alone is not enough to be coherent: `Original` and
         * `Certificados` are siblings with the same parent, and only their
         * contents tell the model which one holds a certificate.
         */
        contents: Map<String, List<String>> = emptyMap(),
        /**
         * What the user said each folder is for, keyed by folder path.
         *
         * This is the strongest hint in the prompt: it is the person's own
         * definition, not a guess from a name. It is listed right after the
         * path so the model reads it before the sample of files.
         */
        descriptions: Map<String, String> = emptyMap(),
        /** Tipo, tamanho e datas do arquivo: o que sobra quando não há texto. */
        facts: DocFacts? = null
    ): String = buildString {
        appendLine("Você organiza as pastas da biblioteca de documentos de um usuário brasileiro.")
        appendLine("Diga em qual pasta da biblioteca este documento deve ficar.")
        appendLine()
        append("Arquivo: ").appendLine(doc.name)
        append("Pasta atual: ").appendLine(doc.folder.ifEmpty { ROOT_ANSWER })
        facts?.lines()?.forEach { appendLine(it) }
        if (Inbox.holds(doc.folder)) {
            appendLine("O documento está no init, a fila de triagem: escolha um destino fora dela.")
        }
        if (existing.isNotEmpty()) {
            appendLine("Pastas que já existem (são as pastas reais no disco):")
            // A big library cannot fit in the prompt: the folders that share
            // words with the document come first, so truncation drops the least
            // likely candidates instead of the best ones.
            ranked(doc, text, existing).take(MAX_FOLDERS).forEach { folder ->
                append("- ").append(folder).appendLine(hintFor(folder, contents, descriptions))
            }
            if (descriptions.isNotEmpty()) {
                appendLine("A \"descrição\" de uma pasta foi escrita pelo próprio usuário dizendo")
                appendLine("o que ela guarda: siga essa descrição acima do nome da pasta.")
            }
            appendLine("Escolha uma delas sempre que servir — reutilizar a árvore do usuário")
            appendLine("é melhor do que inventar um sinônimo para uma pasta que já existe.")
            appendLine("Se nenhuma servir, proponha uma pasta nova, curta, em português.")
            appendLine("Ela será criada quando eu confirmar.")
        } else {
            appendLine("Não há pastas ainda: proponha uma, no máximo 3 níveis.")
        }
        appendLine()
        if (facts != null) {
            appendLine("As datas acima são do arquivo no computador (quando foi copiado ou")
            appendLine("editado), não do conteúdo: não indicam em que pasta o documento vive.")
            appendLine("Use o tipo e o tamanho como pista do que o arquivo é.")
        }
        val excerpt = text.take(MAX_TEXT_CHARS).trim()
        if (excerpt.isEmpty()) {
            appendLine("O arquivo não tem texto legível; use o nome, a pasta atual e os dados acima.")
        } else {
            appendLine("Trecho do conteúdo:")
            appendLine("<<<")
            appendLine(excerpt)
            appendLine(">>>")
        }
        appendLine()
        appendLine("Não proponha pasta que seja só uma data (2026, mes-11): data de")
        appendLine("arquivo não é assunto.")
        appendLine("Responda só com o caminho da pasta, usando / para subníveis,")
        appendLine("com no máximo 3 níveis, em português.")
        appendLine("Sem explicação, sem numeração, sem aspas, sem marcadores.")
        appendLine("Se a pasta atual já for a melhor, responda com ela.")
        append("Se o melhor lugar for o topo da biblioteca, responda apenas: ")
        appendLine(ROOT_ANSWER)
    }

    /** ` — 2 documentos: a.pdf, b.pdf`, or ` — vazia` when nothing is indexed there. */
    private fun hintFor(
        folder: String,
        contents: Map<String, List<String>>,
        descriptions: Map<String, String>
    ): String = buildString {
        val names = contents.entries
            .firstOrNull { it.key.equals(folder, ignoreCase = true) }
            ?.value
            .orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
        if (names.isEmpty()) {
            append(" — vazia, sem documento indexado")
        } else {
            val count = if (names.size == 1) "1 documento" else "${names.size} documentos"
            val shown = names.take(MAX_CONTENT_HINTS).joinToString(", ") { it.take(MAX_HINT_CHARS) }
            append(" — $count: ").append(shown)
        }
        val note = descriptions.entries
            .firstOrNull { it.key.equals(folder, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.take(MAX_DESCRIPTION_CHARS)
            .orEmpty()
        // The user's own sentence goes last on the line but is the one the
        // prompt tells the model to follow: it is the definition of the folder.
        if (note.isNotEmpty()) append(" — descrição: ").append(note)
    }

    /**
     * True when the model said "nothing applies" instead of answering nonsense.
     *
     * A refusal is an answer: the dialog must not claim the model failed when it
     * simply had no better place to offer.
     */
    fun isRefusal(answer: List<String>): Boolean = answer.any { line ->
        line.trim().lowercase().let { it == "nenhuma" || it == "nenhum" || it == "nada" }
    }

    /**
     * Folders most likely to matter for this document, first.
     *
     * Same idea as [HeuristicFolders.score] — a segment of the folder that also
     * appears in the name or the text — but here it only decides the order, so
     * the list is not a shortlist: everything outside the window keeps the
     * alphabetical order it came in with.
     */
    fun ranked(doc: Doc, text: String, existing: List<String>): List<String> {
        if (existing.size <= MAX_FOLDERS) return existing
        val name = HeuristicTagger.fold(doc.name)
        val body = HeuristicTagger.fold(text.take(MAX_TEXT_CHARS))
        return existing
            .mapIndexed { index, folder -> Triple(folder, weight(folder, name, body), index) }
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { it.second }
                    .thenBy { it.third }
            )
            .map { it.first }
    }

    private fun weight(folder: String, name: String, body: String): Int =
        folder.split(FolderPath.SEPARATOR).sumOf { segment ->
            val word = HeuristicTagger.fold(segment)
            if (word.length < 3) 0 else {
                (if (name.contains(word)) 2 else 0) + (if (body.contains(word)) 1 else 0)
            }
        }

    /**
     * Cleans the model answer down to folder paths.
     *
     * The root is kept as `""` because it is a real destination; a blank line
     * is not, so it is dropped instead of becoming "move to the root".
     * [FolderPath.normalize] also understands `raiz`/`root`, which is where the
     * prompt sent the model.
     */
    fun parse(answer: List<String>): List<String> = answer
        .flatMap { line -> line.split(',', ';') }
        .map { part ->
            cleanAnswerLine(part)
                .substringBefore('—')
                .substringBefore(" - ")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .filterNot { it.equals("nenhuma", ignoreCase = true) }
        .filterNot { it.equals("nenhum", ignoreCase = true) }
        .mapNotNull { FolderPath.normalize(it) }
        .distinct()
}
