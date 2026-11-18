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
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.tags.TagPaths
import java.text.Normalizer

/** One suggestion, with the reason shown to the user before applying it. */
data class TagSuggestion(
    val path: String,
    val reason: String,
    /** True when a local model proposed it, false when the text matched a tag. */
    val fromModel: Boolean,
    /**
     * True when the tag does not exist yet, so applying it would create it.
     *
     * The dialog marks these lines as new and still offers them: confirming is
     * what creates the tag, and a document in `init` is no exception.
     */
    val isNew: Boolean = false
)

/**
 * Suggestions from the text alone, without any model.
 *
 * The app has a vocabulary the user already built; the cheapest useful advice
 * is "this document talks about words that are in these tags". It cannot invent
 * a taxonomy, which is a feature: the labels stay the user's.
 */
object HeuristicTagger {

    fun suggest(
        doc: Doc,
        text: String,
        existing: List<String>,
        limit: Int = 3,
        /**
         * What the user said each tag means, keyed by tag path.
         *
         * A tag's name is a guess about its meaning; its description is the
         * definition. Matching the document against the description is what
         * lets `academico/usp` win for a document that says "matrícula na
         * universidade" without repeating the word `usp`.
         */
        descriptions: Map<String, String> = emptyMap()
    ): List<TagSuggestion> {
        if (existing.isEmpty()) return emptyList()

        val name = fold("${doc.name} ${doc.folder.replace('/', ' ')}")
        val body = fold(text.take(MAX_SCAN_CHARS))
        if (name.isBlank() && body.isBlank()) return emptyList()
        // Whole words only: `nota` must not match inside `anotacao` or an
        // academic "nota parcial" when the tag is `nota-fiscal`.
        // Path matching keeps form labels (`matricula`): an existing tag with
        // that name is intentional; GENERIC_WORDS only blocks inventing one.
        val nameWords = tokenSet(name)
        val bodyWords = tokenSet(body)
        val documentWords = nameWords + bodyWords

        return existing
            .mapNotNull { path ->
                score(path, name, body, nameWords, bodyWords, noteFor(path, descriptions), documentWords)
                    ?.let { path to it }
            }
            .sortedWith(compareByDescending<Pair<String, Score>> { it.second.total }.thenBy { it.first })
            .take(limit)
            .map { (path, score) ->
                TagSuggestion(
                    path = path,
                    reason = score.reason,
                    fromModel = false
                )
            }
    }

    private data class Score(val total: Int, val reason: String)

    /** The description written for [path], folded, or "" when there is none. */
    private fun noteFor(path: String, descriptions: Map<String, String>): String =
        descriptions.entries
            .firstOrNull { it.key.equals(path, ignoreCase = true) }
            ?.value
            ?.let(::fold)
            .orEmpty()

    /**
     * One path level split into subject words (`nota-fiscal` → `[nota, fiscal]`).
     *
     * Hierarchy (`academico/extensao`) stays one segment per level: a document
     * about extensão may match without also saying `academico`. Hyphenated
     * compounds inside a level are the ones that need every part.
     */
    internal fun pathSegments(path: String): List<List<String>> = path.split('/')
        .map { segment ->
            segment.split(' ', '-', '_')
                .map { fold(it) }
                .filter { it.length >= MIN_WORD }
                .map { stem(it) }
                .distinct()
        }
        .filter { it.isNotEmpty() }

    /**
     * True when the document carries the compound as a phrase.
     *
     * `nota fiscal`, `nota-fiscal` and `notafiscal` all count: receipts print
     * the words in every spelling, and a college paper that only says `nota`
     * (grade) must not unlock `nota-fiscal`.
     */
    internal fun hasCompoundPhrase(haystack: String, words: List<String>): Boolean {
        if (words.size < 2 || haystack.isBlank()) return false
        val spaced = words.joinToString(" ")
        val hyphen = words.joinToString("-")
        val compact = words.joinToString("")
        return haystack.contains(spaced) || haystack.contains(hyphen) || haystack.contains(compact)
    }

    /**
     * Hyphenated subjects written in a tag description (`nota-fiscal`).
     *
     * Renaming the tag to `nfe` does not erase those words from the note; they
     * must still behave like a compound path segment, or a lone `nota` (grade)
     * keeps unlocking the invoice label.
     */
    internal fun descriptionCompounds(note: String): List<List<String>> {
        if (note.isBlank()) return emptyList()
        return HYPHEN_COMPOUND.findAll(note).mapNotNull { match ->
            val parts = match.value.split('-', '_')
                .map { fold(it) }
                .filter { it.length >= MIN_WORD }
                .map { stem(it) }
                .distinct()
            parts.takeIf { it.size >= 2 }
        }.toList()
    }

    /**
     * How many of [parts] hit the name / body as whole words.
     *
     * Returns null when a compound segment is only half-matched (no phrase).
     */
    private fun segmentHits(
        parts: List<String>,
        name: String,
        body: String,
        nameWords: Set<String>,
        bodyWords: Set<String>
    ): Pair<Int, Int>? {
        if (parts.size == 1) {
            val word = parts[0]
            // "histórico" in a data-structures exercise is not historico/escolar.
            if (word in AMBIGUOUS_TYPE_PHRASES &&
                !confirmsAmbiguousType(word, name, body)
            ) {
                return null
            }
            val inName = if (word in nameWords) 1 else 0
            val inBody = if (word in bodyWords) 1 else 0
            if (inName + inBody == 0) return null
            return inName to inBody
        }
        var inName = 0
        var inBody = 0
        var matched = 0
        for (word in parts) {
            val hitName = word in nameWords
            val hitBody = word in bodyWords
            if (hitName) inName += 1
            if (hitBody) inBody += 1
            if (hitName || hitBody) matched += 1
        }
        if (matched >= parts.size) return inName to inBody
        // Half a compound is noise; the full phrase is the real subject.
        return when {
            hasCompoundPhrase(name, parts) -> parts.size to 0
            hasCompoundPhrase(body, parts) -> 0 to parts.size
            else -> null
        }
    }

    private fun score(
        path: String,
        name: String,
        body: String,
        nameWords: Set<String>,
        bodyWords: Set<String>,
        note: String,
        documentWords: Set<String>
    ): Score? {
        val segments = pathSegments(path)
        // Word by word, with the plural folded away: `certificado` in the file
        // name and `certificados` in the description are the same subject.
        // Description words that only glue the sentence ("relacionado a…")
        // must not unlock a tag by themselves — that is how nota-fiscal was
        // suggested for a college APS that said "aspectos relacionados".
        val sharedRaw = wordSet(note)
            .filter { it in documentWords && it !in WEAK_DESCRIPTION_WORDS }
        if (segments.isEmpty() && sharedRaw.isEmpty()) return null

        var inName = 0
        var inBody = 0
        var anySegment = false
        for (parts in segments) {
            val hits = segmentHits(parts, name, body, nameWords, bodyWords) ?: continue
            anySegment = true
            inName += hits.first
            inBody += hits.second
        }
        // Path fragments already rejected by the compound matcher ("nota" from
        // nota-fiscal) must not sneak back in through the description. The same
        // trap exists after a rename: tag `nfe` with description "nota-fiscal…"
        // still shares bare `nota` with a college paper's grade.
        val pathWords = segments.flatten().toSet()
        val descriptionCompounds = descriptionCompounds(note)
        val compoundFragments = descriptionCompounds.flatten().toSet()
        val phraseUnlocked = descriptionCompounds
            .filter { parts ->
                hasCompoundPhrase(name, parts) || hasCompoundPhrase(body, parts)
            }
            .flatten()
            .toSet()
        val shared = if (anySegment) {
            sharedRaw
        } else {
            sharedRaw.filter { word ->
                word !in pathWords &&
                    (word !in compoundFragments || word in phraseUnlocked)
            }
        }
        if (!anySegment && shared.isEmpty()) return null

        val noteInName = shared.count { it in nameWords }
        val noteInBody = shared.size - noteInName
        // The file name is what the user typed; a match there is a stronger
        // signal than a word buried in the middle of a 40-page report. The
        // description outranks the tag name: it is the user's own definition.
        val total = inName * 3 + inBody + noteInName * 4 + noteInBody * 2
        if (total == 0) return null
        val reason = when {
            noteInName > 0 && (inName > 0 || inBody > 0) ->
                "o arquivo combina com a descrição da etiqueta"
            noteInName > 0 -> "a descrição da etiqueta cita o nome do arquivo"
            noteInBody > 0 && (inName > 0 || inBody > 0) ->
                "o texto e a descrição da etiqueta combinam"
            noteInBody > 0 -> "a descrição da etiqueta cita o texto"
            inName > 0 && inBody > 0 -> "aparece no nome e no texto"
            inName > 0 -> "aparece no nome"
            else -> "aparece no texto"
        }
        return Score(total, reason)
    }

    /**
     * Folded tokens of [raw], without dropping form labels.
     *
     * Used when matching an *existing* tag path against the document: a tag
     * called `matricula` should still fire when that word appears.
     */
    internal fun tokenSet(raw: String): Set<String> = raw
        .split(WORD_SEPARATOR)
        .filter { it.length >= MIN_WORD && it !in STOPWORDS }
        .mapTo(HashSet()) { stem(it) }

    /**
     * The words of [raw] as a set, folded and with the plural cut.
     *
     * Used to compare a description against the document without caring about
     * word order: a description is a sentence, and only its subject words are
     * meaningful here. Form labels are removed — they live in every document.
     */
    internal fun wordSet(raw: String): Set<String> {
        val words = tokenSet(raw).toMutableSet()
        words.removeAll(GENERIC_WORDS)
        return words
    }

    /**
     * `certificados` -> `certificado`, so a plural in one place still matches
     * the singular in the other. Only a trailing `s` goes, and only on words
     * where it is safe — a crude rule on purpose, not a Portuguese stemmer.
     */
    internal fun stem(word: String): String =
        if (word.length > 4 && word.endsWith('s') && !word.endsWith("ss")) word.dropLast(1) else word

    /** Lower case without accents: `Extensão` and `extensao` are the same word. */
    internal fun fold(raw: String): String {
        if (raw.isEmpty()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (char in decomposed) {
            if (Character.getType(char) == Character.NON_SPACING_MARK.toInt()) continue
            builder.append(char.lowercaseChar())
        }
        return builder.toString()
    }

    private const val MIN_WORD = 3
    private const val MAX_SCAN_CHARS = 20_000

    private val HYPHEN_COMPOUND = Regex("""\p{L}[\p{L}\p{N}]{1,}(?:[-_]\p{L}[\p{L}\p{N}]{1,})+""")

    /** Longest excerpt scanned for repeated words when proposing new tags. */
    private const val MAX_PROPOSAL_CHARS = 6_000

    /** Words that never deserve a tag of their own. */
    private val STOPWORDS = setOf(
        "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "em", "na", "no",
        "nas", "nos", "para", "pra", "por", "com", "sem", "sobre", "ao", "aos", "um",
        "uma", "uns", "umas", "que", "se", "the", "of", "and", "to", "in", "on", "at", "for"
    )

    /** File-name noise: type, version, scanner and status words, not subjects. */
    private val NOISE_WORDS = setOf(
        "img", "image", "imagem", "foto", "fotos", "scan", "scanner", "arquivo",
        "arquivos", "file", "files", "documento", "documentos", "untitled", "copia",
        "copy", "versao", "version", "rev", "final", "novo", "nova", "sem",
        "atualizado", "atualizada", "revisado", "revisada", "digitalizado",
        "assinado", "assinada", "definitivo", "completo", "completa", "ok"
    )

    /**
     * Words that every form, receipt and certificate repeats.
     *
     * They are the labels of fields, not the subject of the file: `aluno`,
     * `atividade`, `nome`, `matricula` appear in every academic document there
     * is, so a tag made of one of them filters nothing — searching `aluno`
     * returns the whole library. They are cut from the proposals *and* from the
     * words used to match a tag description, which is how `nota-fiscal` used to
     * match an extension certificate through the shared word `arquivo`.
     */
    internal val GENERIC_WORDS = setOf(
        "aluno", "aluna", "matricula", "nome", "curso", "serie", "turma", "polo",
        "atividade", "data", "hora", "entrega", "envio", "emissao", "autenticacao",
        "protocolo", "versao", "pagina", "sistema", "acesso", "login", "usuario",
        "senha", "cpf", "cnpj", "endereco", "telefone", "email", "celular", "valor",
        "total", "codigo", "observacao", "descricao", "tipo", "referente",
        "informacao", "conteudo", "texto", "campo", "formulario", "responsavel",
        "assinatura", "assinado", "digital", "original", "anexo", "numero", "via",
        "arquivo", "documento", "folha", "unidade", "secretaria", "setor", "geral",
        "interessado", "requerente", "titular", "beneficiario", "emitente",
        "issqn", "aliquota", "cfop", "cst", "ncm", "calculo", "base",
        "destinatario", "prestador", "tomador", "discriminacao", "municipio",
        "logradouro", "bairro", "inscricao", "estadual", "municipal", "orgao",
        "emissor"
    )

    /**
     * Glue words that appear in tag descriptions without naming the subject.
     *
     * "É relacionado a nota-fiscal…" sharing only `relacionado` with a paper
     * that says "aspectos relacionados" must not suggest the invoice tag.
     */
    internal val WEAK_DESCRIPTION_WORDS = setOf(
        "relacionado", "relacionada", "relacionados", "relacionadas",
        "relativo", "relativa", "relativos", "relativas",
        "ligado", "ligada", "ligados", "ligadas",
        "refere", "referem", "respeito", "acerca", "quanto", "trata", "tratam",
        "diz", "dizem", "serve", "servem", "usado", "usada", "usados", "usadas",
        "pertinente", "pertinentes", "concernente", "concernentes",
        "envolve", "envolvem", "envolvido", "envolvida",
        "sobre", "como", "quando", "onde", "este", "esta", "esse", "essa",
        "aquele", "aquela", "todo", "toda", "qualquer", "mesmo", "mesma"
    )

    /**
     * Words that name a kind of document.
     *
     * This is the strongest kind of search tag a library has: "mostre todos os
     * comprovantes" or "os contratos de 2024" is a real question, and the word
     * is usually printed right at the top of the file. It is not a subject, so
     * it is not in the name either — it has to be read from the text.
     */
    internal val DOCUMENT_TYPES = setOf(
        "certificado", "comprovante", "declaracao", "diploma", "historico",
        "contrato", "termo", "ata", "recibo", "boleto", "fatura", "extrato",
        "laudo", "atestado", "procuracao", "requerimento", "edital", "regulamento",
        "portaria", "resolucao", "oficio", "memorando", "relatorio", "artigo",
        "tese", "monografia", "resumo", "prova", "gabarito", "cronograma",
        "orcamento", "proposta", "curriculo", "carteira", "apolice", "escritura",
        "peticao", "sentenca", "certidao", "autorizacao", "guia", "checklist",
        "manual", "catalogo", "inventario", "balanco", "demonstrativo"
    )

    /**
     * Document-type words that also mean something else in Portuguese.
     *
     * A bare hit is not enough: "histórico de operações" in a data-structures
     * assignment must not unlock `historico/escolar` (the university transcript).
     * Each entry lists folded phrases that confirm the *document* sense.
     */
    internal val AMBIGUOUS_TYPE_PHRASES = mapOf(
        "historico" to listOf(
            "historico escolar",
            "historico academico",
            "historico de escolaridade",
            "impressao historico",
            "historico do aluno",
            "historico da aluna",
            "consultar historico",
            "emissao de historico"
        ),
        "termo" to listOf(
            "termo de adesao",
            "termo adesao",
            "termo de compromisso",
            "termo compromisso",
            "termo de responsabilidade",
            "termo responsabilidade",
            "termo de uso",
            "termo de confidencialidade",
            "termo de quitacao"
        ),
        "guia" to listOf(
            "guia de pagamento",
            "guia gps",
            "guia darf",
            "guia da previdencia",
            "guia de recolhimento"
        )
    )

    /** True when [type] is unambiguous, or the document sense is confirmed. */
    internal fun confirmsAmbiguousType(type: String, name: String, body: String): Boolean {
        val phrases = AMBIGUOUS_TYPE_PHRASES[type] ?: return true
        val haystack = "$name $body"
        return phrases.any { haystack.contains(it) }
    }

    /**
     * The kind of document the text declares, if any.
     *
     * Looks at the name and the beginning of the text, where the title lives —
     * scanning a whole report for the word `relatorio` finds it on page 40 even
     * when it is a different subject. The spelling kept is the one used by the
     * user, when the file name already has the word.
     */
    internal fun documentTypes(doc: Doc, text: String): List<String> {
        val foldedName = fold(doc.name)
        val nameWordsFolded = nameWords(doc).mapTo(HashSet()) { stem(fold(it)) }
        val head = fold(text.take(MAX_PROPOSAL_CHARS))
        // Whole words, not substrings: `ata` lives inside `data` and `tratamento`,
        // and a folder called `ata` would be proposed for every form ever filled.
        val tokens = splitWords(head).mapTo(HashSet()) { stem(it) }
        return DOCUMENT_TYPES
            .filter { type ->
                val stemmed = stem(type)
                val hit = stemmed in tokens || stemmed in nameWordsFolded
                hit && confirmsAmbiguousType(type, foldedName, head)
            }
            .distinct()
    }

    /**
     * Tags that do not exist yet, taken from the document itself.
     *
     * This answers the case the app used to leave empty: a library with no
     * vocabulary at all. `HeuristicTagger.suggest` can only point at tags that
     * already exist, so a first document got no suggestion and looked broken.
     * The name `Contrato de aluguel 2026.pdf` already says `contrato` and
     * `aluguel`; the dialog marks them as new, and creating one is the user's
     * click, not the app's decision.
     *
     * Single words on purpose: guessing `contrato/aluguel/2026` from a file name
     * would be inventing a taxonomy, which is the model's job when it is there.
     */
    fun proposeNew(
        doc: Doc,
        text: String,
        existing: Collection<String>,
        limit: Int = 2
    ): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        val seen = existing.mapTo(HashSet()) { fold(it) }
        // `faculdade` is not a new tag when `academico/faculdade` exists: the
        // word is already in the user's vocabulary, at another depth.
        val words = existing
            .flatMap { it.split(TagPaths.SEPARATOR) }
            .mapTo(HashSet()) { fold(it) }
        val offers = ArrayList<Pair<String, String>>()

        // The document's own date first: a date range is the filter people use
        // most, and it is the one the app can prove from the text.
        DocDate.of(text)?.let { stamp ->
            offers += stamp.tag to "nova: a data do documento é ${stamp.label}"
        }
        // Then the kind of document, which is a real question ("os comprovantes
        // de 2025") and is written in the title.
        documentTypes(doc, text).take(2).forEach { type ->
            offers += type to "nova: é um tipo de documento"
        }
        nameWords(doc).take(3).forEach { word ->
            offers += word to "nova: o nome do arquivo sugere"
        }
        // A repeated word inside code or markup is an identifier (`algorithm`,
        // `xmlns`), not a subject: only prose gets this last resort.
        if (doc.kind != DocKind.CODE) {
            repeatedWords(text).forEach { word ->
                offers += word to "nova: o texto repete essa palavra"
            }
        }

        val result = ArrayList<TagSuggestion>()
        for ((raw, reason) in offers) {
            if (result.size >= limit) break
            val path = TagPaths.normalize(raw) ?: continue
            if (existing.any { it.equals(path, ignoreCase = true) }) continue
            val folded = fold(path)
            if (folded in words) continue
            if (!seen.add(folded)) continue
            result += TagSuggestion(path = path, reason = reason, fromModel = false, isNew = true)
        }
        return result
    }

    /**
     * Meaningful words of the file name, in the order they appear.
     *
     * The extension is out, as are stopwords, numbers and very short fragments:
     * a tag called `de` or `2026` helps nobody.
     */
    internal fun meaningfulWords(raw: String): List<String> = splitWords(raw)
        .filter { word ->
            val folded = fold(word)
            folded.length >= MIN_WORD &&
                folded !in STOPWORDS &&
                folded !in NOISE_WORDS &&
                folded !in GENERIC_WORDS &&
                word.any { it.isLetter() }
        }
        .distinctBy { fold(it) }

    /** Words of the name, without the extension, in the spelling the user typed. */
    private fun nameWords(doc: Doc): List<String> {
        val dot = doc.name.lastIndexOf('.')
        val stem = if (dot > 0) doc.name.substring(0, dot) else doc.name
        return meaningfulWords(stem)
    }

    /** Words that appear at least twice in the excerpt, most frequent first. */
    private fun repeatedWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return splitWords(fold(text.take(MAX_PROPOSAL_CHARS)))
            .filter {
                it.length >= 5 &&
                    it !in STOPWORDS &&
                    it !in GENERIC_WORDS &&
                    it.any { char -> char.isLetter() }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(2)
            .map { it.key }
    }

    private fun splitWords(raw: String): List<String> =
        raw.split(WORD_SEPARATOR).filter { it.isNotBlank() }

    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
}

/**
 * The prompt handed to the model.
 *
 * Kept apart so the wording is testable and visible: it names the document,
 * the folder, the tags that already exist (reuse beats invention) and a short
 * excerpt — never the whole file, never anything outside the machine.
 */
object TagPrompt {

    const val MAX_TEXT_CHARS = 2_500
    const val MAX_EXISTING_TAGS = 80

    /** How many documents that already carry a tag are shown as an example. */
    const val MAX_EXAMPLE_DOCS = 2
    const val MAX_EXAMPLE_CHARS = 60

    /** How much of a tag description goes into the prompt. */
    const val MAX_DESCRIPTION_CHARS = 90

    /**
     * How much of the prompt the examples may take.
     *
     * The tag list is capped, not its length: eighty tags with two long file
     * names each is a prompt a small local model cannot hold, and a suggestion
     * that never arrives is worse than one without examples. The cut is by
     * budget, so the tags listed first keep theirs.
     */
    const val EXAMPLE_BUDGET_CHARS = 3_000

    fun build(
        doc: Doc,
        text: String,
        existing: List<String>,
        /**
         * One or two documents already carrying each tag, keyed by tag path.
         *
         * A bare list of paths is a vocabulary without meaning: the model
         * cannot tell what `unip` covers without seeing the documents it was
         * applied to. The examples are what make the suggestion coherent.
         */
        examples: Map<String, List<String>> = emptyMap(),
        /**
         * What the user said each tag means, keyed by tag path.
         *
         * The examples say what the tag was applied *to*; the description says
         * what the user meant by it. When both exist, the description wins, and
         * the prompt says so.
         */
        descriptions: Map<String, String> = emptyMap(),
        /**
         * Tipo, tamanho e datas do arquivo.
         *
         * Sent without the text: a scan without OCR has no words, but it still
         * has a type, a weight and a date, and those are what let the model
         * tell a 5 KB certificate from a 4 MB photo album.
         */
        facts: DocFacts? = null,
        /**
         * The date the document is about, read from its own text.
         *
         * Passed in as a tag (`2026/mes-11`) and not as loose words: it is the
         * strongest filter for finding the file later, and the model should
         * reuse that exact shape instead of inventing `novembro2026` or the
         * month of the copy.
         */
        documentDate: DocDate.Stamp? = null
    ): String = buildString {
        appendLine("Você organiza a biblioteca de documentos de um usuário brasileiro.")
        appendLine("As etiquetas servem para achar o documento depois; sugira as que")
        appendLine("funcionam como filtro de pesquisa.")
        appendLine()
        append("Arquivo: ").appendLine(doc.name)
        append("Pasta: ").appendLine(doc.folder.ifEmpty { "(raiz da biblioteca)" })
        facts?.lines()?.forEach { appendLine(it) }
        documentDate?.let { append("Data do documento (lida no conteúdo): ").appendLine(it.tag) }
        if (existing.isNotEmpty()) {
            appendLine("Etiquetas que já existem:")
            // Truncating alphabetically would drop exactly the tags that share a
            // subject with this document; the ranking puts them first.
            var exampleBudget = EXAMPLE_BUDGET_CHARS
            ranked(doc, text, existing).take(MAX_EXISTING_TAGS).forEach { path ->
                append("- ").append(path)
                val note = descriptions.entries
                    .firstOrNull { it.key.equals(path, ignoreCase = true) }
                    ?.value
                    ?.trim()
                    ?.take(MAX_DESCRIPTION_CHARS)
                    .orEmpty()
                if (note.isNotEmpty()) append(" — descrição: ").append(note)
                val example = examplesFor(path, examples)
                    ?.takeIf { it.length <= exampleBudget }
                if (example != null) {
                    exampleBudget -= example.length
                    append(" — usada em: ").append(example)
                }
                appendLine()
            }
            if (descriptions.isNotEmpty()) {
                appendLine("A \"descrição\" de uma etiqueta foi escrita pelo usuário dizendo o que")
                appendLine("ela significa: siga essa descrição acima dos exemplos de documentos.")
            }
            // The vocabulary is the user's; inventing a synonym for an existing
            // tag is how a library ends up with `faculdade`, `universidade` and
            // `estudos` meaning the same thing. Creation is allowed, but only
            // where the existing tags really do not cover the document.
            appendLine("Reutilize uma etiqueta existente sempre que ela descrever o documento.")
            appendLine("Se nenhuma servir, proponha até 2 etiquetas novas, curtas, em português.")
        } else {
            appendLine("Não há etiquetas ainda: proponha de 2 a 3, em português.")
        }
        appendLine()
        if (facts != null) {
            appendLine("As datas de arquivo acima são do computador (quando foi copiado ou")
            appendLine("editado), não do conteúdo: nunca viram etiqueta de data.")
        }
        appendLine("Uma boa etiqueta responde a uma pergunta de busca. Prefira, nesta ordem:")
        appendLine("1. o assunto (extensao, aluguel, imposto de renda, estrutura de dados);")
        appendLine("2. o tipo de documento, quando o arquivo FOR esse tipo (certificado,")
        appendLine("   comprovante, contrato, declaracao, relatorio, nota-fiscal,")
        appendLine("   historico/escolar);")
        appendLine("3. a data do documento: reutilize as etiquetas de ano e de mês que já")
        appendLine("   existem (2025, mes-11); se não houver, use ano/mes-N.")
        appendLine("O documento tem que SER o tipo sugerido, não só citar a palavra.")
        appendLine("Polissemia — NÃO confunda:")
        appendLine("- trabalho/lista/exercício de faculdade ≠ historico nem historico/escolar")
        appendLine("  (histórico escolar é o boletim oficial; \"histórico de operações\" num")
        appendLine("  exercício de estrutura de dados é outro sentido);")
        appendLine("- \"nota\" de prova/parcial ≠ nota-fiscal / nfe;")
        appendLine("- \"termo\" matemático ou \"em termos de\" ≠ termo de contrato/adesão;")
        appendLine("- citar comprovante/contrato de passagem ≠ o arquivo ser um comprovante.")
        if (documentDate != null) {
            appendLine("A data do documento é ${documentDate.tag}: prefira as etiquetas de ano")
            appendLine("e de mês já listadas acima; não invente outra forma da mesma data.")
        } else {
            appendLine("Se o conteúdo declarar uma data ou um período do documento, proponha")
            appendLine("as etiquetas de ano/mês existentes ou ano/mes-N; se não declarar,")
            appendLine("não proponha etiqueta de data.")
        }
        appendLine("Nunca proponha palavra de formulário (aluno, atividade, nome, matricula,")
        appendLine("data, arquivo): elas aparecem em tudo e não filtram nada.")
        val excerpt = text.take(MAX_TEXT_CHARS).trim()
        if (excerpt.isEmpty()) {
            appendLine("O arquivo não tem texto legível; use o nome, a pasta e os dados acima.")
        } else {
            appendLine("Trecho do conteúdo:")
            appendLine("<<<")
            appendLine(excerpt)
            appendLine(">>>")
        }
        appendLine()
        appendLine("Responda só com as etiquetas, uma por linha, em minúsculas,")
        appendLine("usando / para subníveis, no máximo 3.")
        appendLine("Sem explicação, sem numeração, sem aspas, sem marcadores.")
        appendLine("Se nada se aplicar, responda apenas: nenhuma")
    }

    /**
     * True when the model said "nothing applies" instead of answering nonsense.
     *
     * The difference matters for the dialog: a polite refusal is an answer, and
     * calling it a bad answer would send the user looking for a bug that is not
     * there.
     */
    fun isRefusal(answer: List<String>): Boolean = answer.any { line ->
        line.trim().lowercase().let { it == "nenhuma" || it == "nenhum" || it == "nada" }
    }

    /**
     * Existing tags ordered by how much they share with the document, first.
     *
     * Used before the [MAX_EXISTING_TAGS] cut: with a large vocabulary, the
     * alphabetical order would throw away precisely the tags about the subject
     * of the file.
     */
    fun ranked(doc: Doc, text: String, existing: List<String>): List<String> {
        if (existing.size <= MAX_EXISTING_TAGS) return existing
        val name = HeuristicTagger.fold(doc.name)
        val body = HeuristicTagger.fold(text.take(MAX_TEXT_CHARS))
        return existing
            .mapIndexed { index, path -> Triple(path, weight(path, name, body), index) }
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { it.second }
                    .thenBy { it.third }
            )
            .map { it.first }
    }

    private fun weight(path: String, name: String, body: String): Int =
        path.split(TagPaths.SEPARATOR).sumOf { segment ->
            val word = HeuristicTagger.fold(segment)
            if (word.length < 3) 0 else {
                (if (name.contains(word)) 2 else 0) + (if (body.contains(word)) 1 else 0)
            }
        }

    /** `ensino superior, faculdade`, or null when the tag has no example. */
    private fun examplesFor(path: String, examples: Map<String, List<String>>): String? {
        val names = examples.entries
            .firstOrNull { it.key.equals(path, ignoreCase = true) }
            ?.value
            .orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_EXAMPLE_DOCS)
        if (names.isEmpty()) return null
        return names.joinToString(", ") { it.take(MAX_EXAMPLE_CHARS) }
    }

    /**
     * Cleans the model answer: one tag per line, no bullets, no duplicates.
     *
     * A comma-separated line (`academico, extensao`) is split too: asking for
     * one per line is not a guarantee, and losing half of a correct answer makes
     * the feature look incoherent.
     */
    fun parse(answer: List<String>): List<String> = answer
        .flatMap { line -> line.split(',', ';') }
        .map { part ->
            cleanAnswerLine(part)
                .substringBefore('—')
                .substringBefore(" - ")
                .trim()
        }
        .filter { it.isNotEmpty() && !it.equals("nenhuma", ignoreCase = true) }
        .mapNotNull { TagPaths.normalize(it) }
        .distinct()
}

/** Numbering and bullets models like to add: `1.`, `2)`, `-`, `•`. */
private val ANSWER_BULLET = Regex("^[\\d]{1,2}[.)]\\s+")

/** A label the model puts in front of the answer: `Etiquetas: …`, `Pasta: …`. */
private val ANSWER_LABEL = Regex(
    "^(etiquetas?|tags?|pastas?|pasta sugerida|caminho|resposta)\\s*:\\s*",
    RegexOption.IGNORE_CASE
)

/**
 * Strips the decoration a model puts around an answer.
 *
 * A suggestion lost to a `1.` prefix, a `**` or a trailing period is a bug the
 * user sees as "the AI is not coherent": the text was right, the parsing threw
 * it away — or worse, kept the period and created a tag called `unip.`.
 */
internal fun cleanAnswerLine(line: String): String = line
    .trim()
    .removePrefix("-")
    .removePrefix("*")
    .removePrefix("•")
    .trim()
    .replace(ANSWER_BULLET, "")
    .replace(ANSWER_LABEL, "")
    .trim()
    .trim('"', '\'', '`', '*', '_')
    .trim()
    .removePrefix("#")
    .trim()
    .trimEnd('.', ',', ';', ':', '·')
    .trim()
