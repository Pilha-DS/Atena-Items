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

package com.openappslabs.docs.ui

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.openappslabs.docs.data.ai.AiController
import com.openappslabs.docs.data.ai.AiProvider
import com.openappslabs.docs.data.ai.AiSettings
import com.openappslabs.docs.data.ai.FolderSuggester
import com.openappslabs.docs.data.ai.KeyChange
import com.openappslabs.docs.data.ai.TagModel
import com.openappslabs.docs.data.ai.TagSuggester
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.model.FolderDescription
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.move.DocMover
import com.openappslabs.docs.data.move.FolderCreator
import com.openappslabs.docs.data.move.FolderMover
import com.openappslabs.docs.data.move.FolderRemover
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.move.InboxImport
import com.openappslabs.docs.data.repository.DescriptionRepository
import com.openappslabs.docs.data.repository.DocIndexer
import com.openappslabs.docs.data.repository.DocTextIndexer
import com.openappslabs.docs.data.repository.FolderTagRepository
import com.openappslabs.docs.data.repository.TagRepository
import com.openappslabs.docs.data.source.DocsDatabase
import com.openappslabs.docs.data.text.Harvest
import com.openappslabs.docs.data.text.TextHarvester
import com.openappslabs.docs.data.text.TextSource
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.i18n.PtBrDocsStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the screen controller against an in-memory database and a throwaway
 * folder. The flows the UI reads are the same ones asserted here, so a broken
 * filter shows up as a failing test instead of an empty window.
 */
class LibraryControllerTest {

    private lateinit var database: DocsDatabase
    private lateinit var root: Path
    private lateinit var controller: LibraryController

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            // Unconfined so a write is visible to the collector before the test
            // asserts; the production build keeps this on IO.
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        root = createTempDirectory("docs-ui")
        controller = LibraryController(
            root = root,
            dao = database.docDao(),
            tagDao = database.tagDao(),
            tags = TagRepository(database.tagDao()),
            indexer = DocIndexer(root, database.docDao()),
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private suspend fun addDoc(relPath: String, modifiedTime: Long = 1000): Long =
        database.docDao().insert(
            Doc(
                relPath = relPath,
                name = relPath.substringAfterLast('/'),
                kind = DocKind.fromFileName(relPath),
                size = 10,
                modifiedTime = modifiedTime
            )
        )

    private suspend fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    /**
     * Waits until the controller's state stops moving.
     *
     * The readers write the state from their own threads (Room's and the IO
     * pool), and a write started before the test acted can still land after it,
     * replacing the whole state with the one it had read. Waiting for a quiet
     * tick keeps these tests about the deletion and not about that race.
     */
    private suspend fun settle(reader: LibraryController) {
        var previous = reader.state
        while (true) {
            delay(20)
            val current = reader.state
            if (current == previous) return
            previous = current
        }
    }

    /** A real file behind an indexed document, folder and all. */
    private fun putFile(relPath: String, body: String = "conteudo") {
        val file = root.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, body)
    }

    /**
     * A file in the inbox, creating the folder the way the app does at launch.
     *
     * Writing without it would fail: the index knows the path but nothing on
     * disk would exist, and the moves under test look at the disk.
     */
    private fun putInInbox(name: String, body: String = "conteudo") {
        Inbox.ensure(root)
        putFile("${Inbox.FOLDER}/$name", body)
    }

    @Test
    fun `lists what the database already has`() = runBlocking {
        addDoc("a.pdf")
        addDoc("b.docx")
        await { controller.state.docs.size == 2 }

        val state = controller.state
        assertEquals(listOf("a.pdf", "b.docx"), state.docs.map { it.name })
        assertEquals(2, state.total)
        assertFalse(state.isScanning)
        assertNull(state.lastScan)
    }

    @Test
    fun `search filters the list and is echoed back to the field`() = runBlocking {
        addDoc("Notas de Cálculo.docx")
        addDoc("Contrato.pdf")

        controller.search("notas")

        await { controller.state.docs.size == 1 }
        assertEquals("notas", controller.state.query)
        assertEquals(listOf("Notas de Cálculo.docx"), controller.state.docs.map { it.name })
    }

    @Test
    fun `clearing the search brings everything back`() = runBlocking {
        addDoc("a.pdf")
        addDoc("b.pdf")
        controller.search("a")
        await { controller.state.docs.size == 1 }

        controller.search("")

        await { controller.state.docs.size == 2 }
    }

    @Test
    fun `sort switches between name and recency without losing the filter`() = runBlocking {
        addDoc("antigo.pdf", modifiedTime = 100)
        addDoc("recente.pdf", modifiedTime = 900)
        addDoc("outro.docx", modifiedTime = 500)
        controller.search("pdf")
        await { controller.state.docs.size == 2 }
        assertEquals(listOf("antigo.pdf", "recente.pdf"), controller.state.docs.map { it.name })

        controller.sortBy(SortOrder.RECENT)

        await { controller.state.docs.firstOrNull()?.name == "recente.pdf" }
        assertEquals(SortOrder.RECENT, controller.state.sort)
        assertEquals(listOf("recente.pdf", "antigo.pdf"), controller.state.docs.map { it.name })
    }

    @Test
    fun `query reaches the database with the wildcards escaped`() = runBlocking {
        addDoc("50%_desconto.pdf")
        addDoc("50x-desconto.pdf")

        controller.search("0%_d")

        await { controller.state.docs.size == 1 }
        assertEquals(listOf("50%_desconto.pdf"), controller.state.docs.map { it.name })
    }

    @Test
    fun `filtering by a parent tag shows its children`() = runBlocking {
        val prova = addDoc("prova.pdf")
        val resumo = addDoc("resumo.pdf")
        addDoc("receita.pdf")
        controller.createTag(prova, "academico/faculdade", "inválida")
        controller.createTag(resumo, "academico/escola", "inválida")
        await { controller.state.assignments.size == 2 }

        controller.filterByTag("academico")

        await { controller.state.docs.size == 2 }
        assertEquals(listOf("prova.pdf", "resumo.pdf"), controller.state.docs.map { it.name }.sorted())
        assertEquals(setOf("academico"), controller.state.tagFilters)
    }

    @Test
    fun `filtering by a leaf shows only that leaf`() = runBlocking {
        val prova = addDoc("prova.pdf")
        val resumo = addDoc("resumo.pdf")
        controller.createTag(prova, "academico/faculdade", "inválida")
        controller.createTag(resumo, "academico/escola", "inválida")
        await { controller.state.assignments.size == 2 }

        controller.filterByTag("academico/escola")

        await { controller.state.docs.size == 1 }
        assertEquals(listOf("resumo.pdf"), controller.state.docs.map { it.name })
    }

    @Test
    fun `filter composes with the text search`() = runBlocking {
        val first = addDoc("prova.pdf")
        val second = addDoc("resumo.pdf")
        addDoc("contrato.pdf")
        controller.createTag(first, "academico", "inválida")
        controller.createTag(second, "academico", "inválida")
        await { controller.state.assignments.size == 2 }

        controller.filterByTag("academico")
        controller.search("prova")

        await { controller.state.docs.size == 1 }
        assertEquals(listOf("prova.pdf"), controller.state.docs.map { it.name })
    }

    @Test
    fun `selecting the same tag twice clears that filter`() = runBlocking {
        val doc = addDoc("prova.pdf")
        controller.createTag(doc, "academico", "inválida")
        await { controller.state.tags.isNotEmpty() }
        controller.filterByTag("academico")
        await { controller.state.tagFilters == setOf("academico") }

        controller.filterByTag("academico")

        await { controller.state.tagFilters.isEmpty() }
        assertTrue(controller.state.tagFilters.isEmpty())
    }

    @Test
    fun `two tag filters keep only documents that carry both`() = runBlocking {
        val both = addDoc("nfe-setembro.pdf")
        val onlyMonth = addDoc("recibo-setembro.pdf")
        val onlyNf = addDoc("nfe-outubro.pdf")
        controller.createTag(both, "mes-9", "inválida")
        controller.createTag(both, "nota-fiscal", "inválida")
        controller.createTag(onlyMonth, "mes-9", "inválida")
        controller.createTag(onlyNf, "nota-fiscal", "inválida")
        await { controller.state.assignments.size == 3 }

        controller.filterByTag("mes-9")
        controller.filterByTag("nota-fiscal")

        await {
            controller.state.tagFilters == setOf("mes-9", "nota-fiscal") &&
                controller.state.docs.map { it.id } == listOf(both)
        }
        assertEquals(listOf("nfe-setembro.pdf"), controller.state.docs.map { it.name })
    }

    @Test
    fun `removing one of two tag filters keeps the other`() = runBlocking {
        val both = addDoc("nfe-setembro.pdf")
        val onlyNf = addDoc("nfe-outubro.pdf")
        controller.createTag(both, "mes-9", "inválida")
        controller.createTag(both, "nota-fiscal", "inválida")
        controller.createTag(onlyNf, "nota-fiscal", "inválida")
        await { controller.state.assignments.size == 2 }
        controller.filterByTag("mes-9")
        controller.filterByTag("nota-fiscal")
        await { controller.state.docs.size == 1 }

        controller.filterByTag("mes-9")

        await {
            controller.state.tagFilters == setOf("nota-fiscal") &&
                controller.state.docs.map { it.name }.sorted() ==
                listOf("nfe-outubro.pdf", "nfe-setembro.pdf")
        }
    }

    @Test
    fun `tag tree counts the documents of each subtree`() = runBlocking {
        val first = addDoc("a.pdf")
        val second = addDoc("b.pdf")
        val third = addDoc("c.pdf")
        controller.createTag(first, "academico/faculdade", "inválida")
        controller.createTag(second, "academico/escola", "inválida")
        controller.createTag(third, "academico", "inválida")

        await { controller.state.tags.singleOrNull()?.count == 3 }
        val root = controller.state.tags.single()
        assertEquals("academico", root.path)
        assertEquals(3, root.count)
        assertEquals(mapOf("escola" to 1, "faculdade" to 1), root.children.associate { it.name to it.count })
    }

    @Test
    fun `toggling applies then removes a tag`() = runBlocking {
        val doc = addDoc("prova.pdf")

        controller.toggleTag(doc, "pessoal", "falhou")
        await { controller.state.tagsOf(doc) == listOf("pessoal") }

        controller.toggleTag(doc, "pessoal", "falhou")
        await { controller.state.tagsOf(doc).isEmpty() }
    }

    @Test
    fun `creating a tag with hierarchy applies the leaf and creates the parents`() = runBlocking {
        val doc = addDoc("prova.pdf")

        controller.createTag(doc, "  Acadêmico / USP  ", "inválida")

        await { controller.state.tagsOf(doc) == listOf("acadêmico/usp") }
        assertEquals(listOf("acadêmico", "acadêmico/usp"), flattenPaths())
    }

    @Test
    fun `an invalid tag is a message and changes nothing`() = runBlocking {
        val doc = addDoc("prova.pdf")

        controller.createTag(doc, "", "Etiqueta inválida")
        controller.createTag(doc, "a/b/c/d/e/f", "Etiqueta inválida")

        assertEquals("Etiqueta inválida", controller.state.message)
        assertTrue(controller.state.tagsOf(doc).isEmpty())
        assertTrue(controller.state.tags.isEmpty())
    }

    @Test
    fun `the tag editor opens and closes on a document`() = runBlocking {
        val doc = addDoc("prova.pdf")
        await { controller.state.docs.size == 1 }

        controller.openTagEditor(doc)
        assertEquals(doc, controller.state.tagEditorDocId)

        controller.closeTagEditor()
        assertNull(controller.state.tagEditorDocId)
    }

    @Test
    fun `the folder view lists the folder and its subfolders`() = runBlocking {
        addDoc("faculdade/a.pdf")
        addDoc("faculdade/2026/b.pdf")
        addDoc("pessoal/c.pdf")
        // Plus the inbox, which is always part of the tree.
        await { controller.state.folders.size == 3 }

        controller.selectView(LibraryView.Folder("faculdade"))

        await { controller.state.docs.size == 2 }
        assertEquals(
            listOf("a.pdf", "b.pdf"),
            controller.state.docs.map { it.name }.sorted()
        )
        assertEquals(LibraryView.Folder("faculdade"), controller.state.view)
    }

    @Test
    fun `the folder tree counts the subfolders into the parent`() = runBlocking {
        addDoc("faculdade/a.pdf")
        addDoc("faculdade/2026/b.pdf")
        addDoc("faculdade/2026/c.pdf")
        addDoc("pessoal/d.pdf")

        // `faculdade`, `pessoal` and the always-present inbox.
        await { controller.state.folders.size == 3 }
        val faculdade = controller.state.folders.first { it.path == "faculdade" }

        assertEquals(3, faculdade.count)
        assertEquals(2, faculdade.children.single().count)
    }

    @Test
    fun `the recent view keeps only the last days and switches the order`() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        addDoc("velho.pdf", modifiedTime = now - (30 * day))
        addDoc("novo.pdf", modifiedTime = now - day)
        await { controller.state.docs.size == 2 }

        controller.selectView(LibraryView.Recent)

        await { controller.state.docs.size == 1 }
        assertEquals(listOf("novo.pdf"), controller.state.docs.map { it.name })
        assertEquals(SortOrder.RECENT, controller.state.sort)
    }

    @Test
    fun `the favorites view shows only what was marked`() = runBlocking {
        addDoc("a.pdf")
        addDoc("b.pdf")
        await { controller.state.docs.size == 2 }

        controller.toggleFavorite(controller.state.docs.first { it.name == "b.pdf" })
        await { controller.state.favoriteCount == 1 }

        controller.selectView(LibraryView.Favorites)

        await { controller.state.docs.size == 1 }
        assertEquals(listOf("b.pdf"), controller.state.docs.map { it.name })
        assertTrue(controller.state.docs.single().favorite)
    }

    @Test
    fun `selecting the current view again shows everything`() = runBlocking {
        addDoc("a.pdf")
        await { controller.state.docs.size == 1 }

        controller.selectView(LibraryView.Favorites)
        assertEquals(LibraryView.Favorites, controller.state.view)
        controller.selectView(LibraryView.Favorites)

        assertEquals(LibraryView.All, controller.state.view)
    }

    @Test
    fun `the tag filter still works inside a folder`() = runBlocking {
        val first = addDoc("faculdade/a.pdf")
        val second = addDoc("faculdade/b.pdf")
        addDoc("pessoal/c.pdf")
        controller.createTag(first, "academico", "inválida")
        controller.createTag(second, "pessoal", "inválida")
        await { controller.state.assignments.size == 2 }

        controller.selectView(LibraryView.Folder("faculdade"))
        controller.filterByTag("academico")

        await { controller.state.docs.size == 1 }
        assertEquals(listOf("a.pdf"), controller.state.docs.map { it.name })
    }

    @Test
    fun `refresh indexes the folder and records when it happened`() = runBlocking {
        Files.writeString(root.resolve("relatorio.pdf"), "conteudo")

        controller.refresh()
        awaitIdle()

        val state = controller.state
        assertNotNull(state.lastScan)
        assertEquals(1, state.outcome?.added)
        assertEquals(1, state.total)
        assertEquals(listOf("relatorio.pdf"), state.docs.map { it.name })
    }

    @Test
    fun `a second refresh does not duplicate rows`() = runBlocking {
        Files.writeString(root.resolve("relatorio.pdf"), "conteudo")

        controller.refresh()
        awaitIdle()
        controller.refresh()
        awaitIdle()

        assertEquals(0, controller.state.outcome?.added)
        assertEquals(1, controller.state.total)
    }

    @Test
    fun `refresh reports the folder that went away instead of crashing`() = runBlocking {
        Files.delete(root)

        controller.refresh()
        awaitIdle()

        // A missing root is an empty library, not an error: `scan` returns
        // nothing and the UI shows the empty state.
        assertEquals(0, controller.state.total)
        assertNull(controller.state.error)
    }

    @Test
    fun `a message is consumed only once`() {
        controller.report("Caminho copiado")
        assertEquals("Caminho copiado", controller.state.message)

        controller.messageShown()
        assertNull(controller.state.message)
    }

    @Test
    fun `refresh while scanning is ignored`() {
        controller.refresh()

        controller.refresh()

        // The first scan is still running: the second call must not start a
        // second walk over the same tree.
        assertTrue(controller.state.isScanning)
    }

    private fun withContent(harvester: TextHarvester) = LibraryController(
        root = root,
        dao = database.docDao(),
        tagDao = database.tagDao(),
        tags = TagRepository(database.tagDao()),
        indexer = DocIndexer(root, database.docDao(), database.docTextDao()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        textDao = database.docTextDao(),
        textIndexer = DocTextIndexer(root, database.docTextDao(), harvester)
    )

    @Test
    fun `reading the content makes the text searchable and counts it in the footer`() = runBlocking {
        Files.writeString(root.resolve("declaracao.txt"), "carga horária de extensão")
        val reader = withContent { _, _ -> Harvest("carga horária de extensão", TextSource.TEXT) }

        reader.refresh()
        await { reader.state.withText == 1 && !reader.state.isReading && !reader.state.isScanning }

        assertEquals(1, reader.state.textOutcome?.withText)
        reader.search("extensão")
        await { reader.state.docs.size == 1 }
        assertEquals(listOf("declaracao.txt"), reader.state.docs.map { it.name })
    }

    @Test
    fun `rereading forgets the stored text and reads again`() = runBlocking {
        Files.writeString(root.resolve("declaracao.txt"), "texto")
        var calls = 0
        val reader = withContent { _, _ ->
            calls += 1
            Harvest("texto $calls", TextSource.TEXT)
        }
        reader.refresh()
        await { reader.state.withText == 1 && !reader.state.isReading }
        assertEquals(1, calls)

        reader.rereadContent()
        await { !reader.state.isReading && calls == 2 }

        assertEquals(1, database.docTextDao().count())
        assertEquals(1, reader.state.withText)
    }

    private fun withSuggestions(model: TagModel?) = LibraryController(
        root = root,
        dao = database.docDao(),
        tagDao = database.tagDao(),
        tags = TagRepository(database.tagDao()),
        indexer = DocIndexer(root, database.docDao(), database.docTextDao()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        textDao = database.docTextDao(),
        suggester = TagSuggester(database.docTextDao(), database.tagDao(), model)
    )

    @Test
    fun `suggestions arrive in the state and can be applied`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        Files.writeString(root.resolve("extensao.pdf"), "x")
        database.docTextDao().put(
            DocText(docId = doc, text = "extensão", source = "pdf", charCount = 8, extractedTime = 1)
        )
        database.tagDao().insert(Tag(path = "academico/extensao"))
        val reader = withSuggestions(
            object : TagModel {
                override suspend fun isAvailable() = true
                override suspend fun suggest(prompt: String) = listOf("academico/extensao", "unip/2026")
            }
        )
        await { reader.state.docs.size == 1 }

        reader.openSuggestions(doc)
        await { !reader.state.isSuggesting && reader.state.suggestions.size == 2 }

        assertEquals(doc, reader.state.suggestionDocId)
        assertNull(reader.state.suggestionNote)

        reader.applySuggestions(doc, listOf("academico/extensao"), { n -> "$n aplicada" })

        await { reader.state.tagsOf(doc) == listOf("academico/extensao") && reader.state.message == "1 aplicada" }
        assertNull(reader.state.suggestionDocId)
        assertTrue(reader.state.suggestions.isEmpty())
    }

    @Test
    fun `an offline model leaves a note and the text suggestions`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        Files.writeString(root.resolve("extensao.pdf"), "x")
        database.docTextDao().put(
            DocText(docId = doc, text = "carga horária de extensão", source = "pdf", charCount = 24, extractedTime = 1)
        )
        database.tagDao().insert(Tag(path = "academico/extensao"))
        val reader = withSuggestions(
            object : TagModel {
                override suspend fun isAvailable() = false
                override suspend fun suggest(prompt: String) = emptyList<String>()
            }
        )
        await { reader.state.docs.size == 1 }

        reader.openSuggestions(doc)
        await { !reader.state.isSuggesting }

        assertEquals(listOf("academico/extensao"), reader.state.suggestions.map { it.path })
        assertEquals(SuggestionNote.MODEL_OFFLINE, reader.state.suggestionNote)
    }

    @Test
    fun `closing the dialog forgets the suggestions`() = runBlocking {
        val doc = addDoc("a.pdf")
        Files.writeString(root.resolve("a.pdf"), "x")
        val reader = withSuggestions(
            object : TagModel {
                override suspend fun isAvailable() = true
                override suspend fun suggest(prompt: String) = listOf("pessoal")
            }
        )
        await { reader.state.docs.size == 1 }
        reader.openSuggestions(doc)
        await { reader.state.suggestions.isNotEmpty() }

        reader.closeSuggestions()

        assertNull(reader.state.suggestionDocId)
        assertTrue(reader.state.suggestions.isEmpty())
    }

    private fun withFolders(model: TagModel?) = LibraryController(
        root = root,
        dao = database.docDao(),
        tagDao = database.tagDao(),
        tags = TagRepository(database.tagDao()),
        indexer = DocIndexer(root, database.docDao(), database.docTextDao()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        textDao = database.docTextDao(),
        folderSuggester = FolderSuggester(
            database.docTextDao(),
            database.docDao(),
            model
        ),
        mover = DocMover(root, database.docDao())
    )

    @Test
    fun `a folder suggestion arrives and the move changes the path`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        Files.writeString(root.resolve("extensao.pdf"), "conteudo")
        addDoc("academico/outro.pdf")
        val reader = withFolders(
            object : TagModel {
                override suspend fun isAvailable() = true
                override suspend fun suggest(prompt: String) = listOf("academico")
            }
        )
        await { reader.state.docs.size == 2 }

        reader.openFolderSuggestions(doc)
        await { !reader.state.isSuggestingFolder }

        assertEquals(doc, reader.state.folderDocId)
        assertEquals(listOf("academico"), reader.state.folderSuggestions.map { it.target })
        assertNull(reader.state.folderNote)

        reader.applyFolderSuggestion(doc, "academico") { outcome -> "resultado: $outcome" }

        await {
            reader.state.docs.any { it.id == doc && it.relPath == "academico/extensao.pdf" } &&
                reader.state.message == "resultado: Moved(from=extensao.pdf, to=academico/extensao.pdf)"
        }
        assertNull(reader.state.folderDocId)
        assertTrue(reader.state.folderSuggestions.isEmpty())
        assertTrue(Files.exists(root.resolve("academico/extensao.pdf")))
    }

    @Test
    fun `a refused move explains itself and leaves the document alone`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        Files.writeString(root.resolve("extensao.pdf"), "conteudo")
        val reader = withFolders(null)
        await { reader.state.docs.size == 1 }

        reader.applyFolderSuggestion(doc, "../fora") { outcome -> "resultado: $outcome" }

        await { reader.state.message != null }
        assertEquals("resultado: Rejected(issue=INVALID_FOLDER)", reader.state.message)
        assertEquals("extensao.pdf", reader.state.docs.single().relPath)
        assertTrue(Files.exists(root.resolve("extensao.pdf")))
    }

    @Test
    fun `closing the folder dialog forgets the suggestions`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addDoc("academico/outro.pdf")
        val reader = withFolders(
            object : TagModel {
                override suspend fun isAvailable() = true
                override suspend fun suggest(prompt: String) = listOf("academico")
            }
        )
        await { reader.state.docs.size == 2 }
        reader.openFolderSuggestions(doc)
        await { reader.state.folderSuggestions.isNotEmpty() }

        reader.closeFolderSuggestions()

        assertNull(reader.state.folderDocId)
        assertTrue(reader.state.folderSuggestions.isEmpty())
    }

    @Test
    fun `without a model the existing folders are still suggested`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addDoc("academico/extensao/declaracao.pdf")
        val reader = withFolders(
            object : TagModel {
                override suspend fun isAvailable() = false
                override suspend fun suggest(prompt: String) = emptyList<String>()
            }
        )
        await { reader.state.docs.size == 2 }
        database.docTextDao().put(
            DocText(docId = doc, text = "carga horária de extensão", source = "pdf", charCount = 24, extractedTime = 1)
        )

        reader.openFolderSuggestions(doc)
        await { !reader.state.isSuggestingFolder }

        assertEquals(listOf("academico/extensao"), reader.state.folderSuggestions.map { it.target })
        assertEquals(SuggestionNote.MODEL_OFFLINE, reader.state.folderNote)
    }

    @Test
    fun `a move still works when a search hid the row`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        Files.writeString(root.resolve("extensao.pdf"), "conteudo")
        val reader = withFolders(null)
        await { reader.state.docs.size == 1 }
        reader.search("nao-existe")
        await { reader.state.docs.isEmpty() }

        reader.applyFolderSuggestion(doc, "academico") { "ok" }

        await { Files.exists(root.resolve("academico/extensao.pdf")) }
        assertEquals("academico/extensao.pdf", database.docDao().findById(doc)?.relPath)
    }

    @Test
    fun `from the inbox a suggested new folder is created on confirmation`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        putInInbox("extensao.pdf")
        val reader = withFolders(null)
        await { reader.state.docs.size == 1 }

        reader.applyFolderSuggestion(doc, "academico/2026") { outcome -> "resultado: $outcome" }

        await { reader.state.message != null }
        assertEquals(
            "resultado: Moved(from=init/extensao.pdf, to=academico/2026/extensao.pdf)",
            reader.state.message
        )
        assertTrue(Files.exists(root.resolve("academico/2026/extensao.pdf")))
        assertEquals("academico/2026/extensao.pdf", database.docDao().findById(doc)?.relPath)
    }

    @Test
    fun `the inbox itself is never a folder suggestion`() = runBlocking {
        addDoc("init/extensao.pdf")
        addDoc("academico/outro.pdf")
        val doc = addDoc("extensao.pdf")
        Files.writeString(root.resolve("extensao.pdf"), "conteudo")
        val reader = withFolders(
            object : TagModel {
                override suspend fun isAvailable() = true
                // A waiting room is not a destination, even when the model says so.
                override suspend fun suggest(prompt: String) = listOf("init", "academico")
            }
        )
        await { reader.state.docs.size == 3 }

        reader.openFolderSuggestions(doc)
        await { !reader.state.isSuggestingFolder }

        assertEquals(listOf("academico"), reader.state.folderSuggestions.map { it.target })
    }

    @Test
    fun `in the inbox an existing tag is applied and a new one is created on confirmation`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        putInInbox("extensao.pdf")
        database.docTextDao().put(
            DocText(docId = doc, text = "extensão", source = "pdf", charCount = 8, extractedTime = 1)
        )
        database.tagDao().insert(Tag(path = "academico/extensao"))
        val reader = withSuggestions(
            object : TagModel {
                override suspend fun isAvailable() = true
                override suspend fun suggest(prompt: String) =
                    listOf("academico/extensao", "unip/2026")
            }
        )
        await { reader.state.docs.size == 1 }

        reader.openSuggestions(doc)
        await { !reader.state.isSuggesting && reader.state.suggestions.size == 2 }
        assertEquals(listOf(false, true), reader.state.suggestions.map { it.isNew })

        reader.applySuggestions(
            doc,
            listOf("academico/extensao", "unip/2026")
        ) { applied -> "$applied aplicadas" }

        await { reader.state.message == "2 aplicadas" }
        // Both landed: confirming the new one is what brought it into being.
        assertEquals(listOf("academico/extensao", "unip/2026"), reader.state.tagsOf(doc))
        assertNotNull(database.tagDao().findByPath("unip/2026"))
        assertNotNull(database.tagDao().findByPath("unip"))
        Unit
    }

    @Test
    fun `typing a new tag in the inbox creates it`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        putInInbox("extensao.pdf")
        val reader = withSuggestions(null)
        await { reader.state.docs.size == 1 }

        reader.createTag(doc, "pessoal/contas", "inválida")

        await { reader.state.tagsOf(doc) == listOf("pessoal/contas") }
        assertNotNull(database.tagDao().findByPath("pessoal/contas"))
        Unit
    }

    @Test
    fun `outside the inbox typing a new tag creates it`() = runBlocking {
        val doc = addDoc("academico/extensao.pdf")
        putFile("academico/extensao.pdf")
        val reader = withSuggestions(null)
        await { reader.state.docs.size == 1 }

        reader.createTag(doc, "pessoal/contas", "inválida")

        await { reader.state.tagsOf(doc) == listOf("pessoal/contas") }
        assertNotNull(database.tagDao().findByPath("pessoal/contas"))
        Unit
    }

    @Test
    fun `checking a missing tag in the inbox creates it`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        putInInbox("extensao.pdf")
        val reader = withSuggestions(null)
        await { reader.state.docs.size == 1 }

        reader.toggleTag(doc, "pessoal", "não deu")

        await { reader.state.tagsOf(doc) == listOf("pessoal") }
        assertNotNull(database.tagDao().findByPath("pessoal"))
        Unit
    }

    private fun withFiles() = LibraryController(
        root = root,
        dao = database.docDao(),
        tagDao = database.tagDao(),
        tags = TagRepository(database.tagDao()),
        indexer = DocIndexer(root, database.docDao(), database.docTextDao()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        textDao = database.docTextDao(),
        mover = DocMover(root, database.docDao()),
        folderCreator = FolderCreator(root),
        folderMover = FolderMover(
            root = root,
            dao = database.docDao(),
            descriptions = database.folderDescriptionDao(),
            folderTags = database.folderTagDao()
        ),
        folderRemover = FolderRemover(root),
        importer = InboxImport(root),
        descriptions = DescriptionRepository(database.folderDescriptionDao(), database.tagDao())
    )

    /** The controller of [setUp], with the description repository wired in. */
    private fun describingController() = LibraryController(
        root = root,
        dao = database.docDao(),
        tagDao = database.tagDao(),
        tags = TagRepository(database.tagDao()),
        indexer = DocIndexer(root, database.docDao(), database.docTextDao()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        textDao = database.docTextDao(),
        descriptions = DescriptionRepository(database.folderDescriptionDao(), database.tagDao())
    )

    /** Controller with folder inheritance wired the same way as the window. */
    private fun inheritingController(): LibraryController {
        val tagRepository = TagRepository(database.tagDao(), database.folderTagDao())
        return LibraryController(
            root = root,
            dao = database.docDao(),
            tagDao = database.tagDao(),
            tags = tagRepository,
            indexer = DocIndexer(root, database.docDao(), database.docTextDao()),
            scope = CoroutineScope(Dispatchers.Unconfined),
            textDao = database.docTextDao(),
            folderRemover = FolderRemover(root),
            folderTags = FolderTagRepository(database.folderTagDao(), tagRepository)
        )
    }

    private fun folderPaths(reader: LibraryController): List<String> =
        PathTree.flatten(reader.state.folders).map { it.path }

    @Test
    fun `the folder tree comes from the disk, empty folders included`() = runBlocking {
        Files.createDirectories(root.resolve("academico/2026"))
        Files.createDirectories(root.resolve("vazia"))
        val reader = withFiles()

        await { folderPaths(reader).contains("academico/2026") }

        assertTrue(folderPaths(reader).contains("vazia"))
        assertTrue(folderPaths(reader).contains("init"))
        // No document anywhere: the counts are zero, not absent.
        assertEquals(0, PathTree.flatten(reader.state.folders).first { it.path == "vazia" }.count)
    }

    @Test
    fun `creating a folder adds it to the tree and opens it`() = runBlocking {
        val reader = withFiles()
        await { folderPaths(reader).contains("init") }

        reader.createFolder("faculdade/2026") { outcome -> "resultado: $outcome" }

        await { reader.state.message != null }
        assertEquals("resultado: Created(path=faculdade/2026)", reader.state.message)
        assertTrue(Files.isDirectory(root.resolve("faculdade/2026")))
        await { folderPaths(reader).contains("faculdade/2026") }
        assertEquals(LibraryView.Folder("faculdade/2026"), reader.state.view)
    }

    @Test
    fun `creating a folder inside the inbox works like anywhere else`() = runBlocking {
        val reader = withFiles()
        await { folderPaths(reader).contains("init") }

        reader.createFolder("init/faculdade") { outcome -> "resultado: $outcome" }

        await { reader.state.message != null }
        assertEquals("resultado: Created(path=init/faculdade)", reader.state.message)
        assertTrue(Files.isDirectory(root.resolve("init/faculdade")))
    }

    @Test
    fun `an existing folder is reported instead of silently reused`() = runBlocking {
        Files.createDirectories(root.resolve("academico"))
        val reader = withFiles()
        await { folderPaths(reader).contains("academico") }

        reader.createFolder("academico") { outcome -> "resultado: $outcome" }

        await { reader.state.message != null }
        assertEquals("resultado: Rejected(issue=EXISTS)", reader.state.message)
        assertEquals(LibraryView.All, reader.state.view)
    }

    @Test
    fun `importing chosen files moves them to the inbox and indexes them`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        val file = downloads.resolve("contrato.pdf")
        Files.writeString(file, "conteudo do contrato")
        val reader = withFiles()
        await { folderPaths(reader).contains("init") }

        reader.importToInbox(listOf(file)) { report -> "${report.imported} para o init" }

        await { reader.state.docs.any { it.relPath == "init/contrato.pdf" } }
        assertEquals("1 para o init", reader.state.message)
        assertFalse(reader.state.isImporting)
        assertFalse(Files.exists(file))
        assertTrue(Files.exists(root.resolve("init/contrato.pdf")))
    }

    @Test
    fun `a cancelled chooser (empty selection) does nothing at all`() = runBlocking {
        val reader = withFiles()
        await { folderPaths(reader).contains("init") }

        reader.importToInbox(emptyList()) { report -> "${report.total} arquivos" }

        assertTrue(reader.state.message == null)
        assertFalse(reader.state.isImporting)
    }

    @Test
    fun `moving a document to the inbox changes its path and keeps its tags`() = runBlocking {
        val doc = addDoc("academico/extensao.pdf")
        putFile("academico/extensao.pdf")
        val tagId = database.tagDao().insert(Tag(path = "academico/extensao"))
        database.tagDao().assign(DocTag(doc, tagId))
        val reader = withFiles()
        await { reader.state.docs.size == 1 }

        reader.moveToInbox(doc) { outcome -> "resultado: $outcome" }

        await { reader.state.message != null }
        assertEquals(
            "resultado: Moved(from=academico/extensao.pdf, to=init/extensao.pdf)",
            reader.state.message
        )
        assertTrue(Files.exists(root.resolve("init/extensao.pdf")))
        assertFalse(Files.exists(root.resolve("academico/extensao.pdf")))
        assertEquals("init/extensao.pdf", database.docDao().findById(doc)?.relPath)
        assertEquals(listOf("academico/extensao"), reader.state.tagsOf(doc))
    }

    @Test
    fun `opening the move dialog remembers the document without asking the model`() = runBlocking {
        val doc = addDoc("init/contrato.pdf")
        putFile("init/contrato.pdf")
        val reader = withFiles()
        await { reader.state.docs.size == 1 }

        reader.openMove(doc)

        assertEquals(doc, reader.state.moveDocId)
        assertNull(reader.state.folderDocId)
        assertFalse(reader.state.isSuggestingFolder)

        reader.closeMove()

        assertNull(reader.state.moveDocId)
    }

    @Test
    fun `a manual move from the inbox lands in the chosen folder`() = runBlocking {
        val doc = addDoc("init/contrato.pdf")
        putFile("init/contrato.pdf")
        addDoc("academico/outro.pdf")
        putFile("academico/outro.pdf")
        val reader = withFiles()
        await { reader.state.docs.size == 2 }

        reader.openMove(doc)
        assertEquals(doc, reader.state.moveDocId)

        reader.applyMove(doc, "academico") { outcome -> "resultado: $outcome" }

        await {
            reader.state.docs.any { it.id == doc && it.relPath == "academico/contrato.pdf" } &&
                reader.state.message == "resultado: Moved(from=init/contrato.pdf, to=academico/contrato.pdf)"
        }
        assertNull(reader.state.moveDocId)
        assertTrue(Files.exists(root.resolve("academico/contrato.pdf")))
        assertFalse(Files.exists(root.resolve("init/contrato.pdf")))
    }

    @Test
    fun `opening the move dialog dismisses a folder suggestion still on screen`() = runBlocking {
        val doc = addDoc("init/contrato.pdf")
        putFile("init/contrato.pdf")
        addDoc("academico/outro.pdf")
        putFile("academico/outro.pdf")
        val reader = withFolders(
            object : TagModel {
                override suspend fun isAvailable() = true
                override suspend fun suggest(prompt: String) = listOf("academico")
            }
        )
        await { reader.state.docs.size == 2 }
        reader.openFolderSuggestions(doc)
        await { reader.state.folderSuggestions.isNotEmpty() }

        reader.openMove(doc)

        assertEquals(doc, reader.state.moveDocId)
        assertNull(reader.state.folderDocId)
        assertTrue(reader.state.folderSuggestions.isEmpty())
    }

    @Test
    fun `opening the folder deletion counts what is inside`() = runBlocking {
        putFile("academico/a.pdf")
        putFile("academico/2026/b.pdf")
        val reader = withFiles()
        settle(reader)

        reader.openDeleteFolder("academico")

        await { reader.state.deleteFolderFiles == 2 }
        assertEquals("academico", reader.state.deleteFolderPath)
        // Asking is not deleting.
        assertTrue(Files.exists(root.resolve("academico/a.pdf")))

        reader.closeDeleteFolder()
        assertNull(reader.state.deleteFolderPath)
    }

    @Test
    fun `deleting a folder takes the files and the row out`() = runBlocking {
        putFile("academico/2026/prova.pdf")
        val reader = withFiles()
        reader.refresh()
        await { !reader.state.isScanning && folderPaths(reader).contains("academico/2026") }
        settle(reader)

        reader.deleteFolder("academico") { folderRemoveMessage(it, PtBrDocsStrings) }

        await { reader.state.message != null }
        assertEquals("Pasta apagada: academico (1 arquivo)", reader.state.message)
        assertFalse(Files.exists(root.resolve("academico")))
        await { !folderPaths(reader).any { it.startsWith("academico") } }
    }

    @Test
    fun `deleting the folder being looked at goes back to everything`() = runBlocking {
        putFile("academico/prova.pdf")
        val reader = withFiles()
        reader.refresh()
        await { !reader.state.isScanning && reader.state.docs.size == 1 }
        reader.selectView(LibraryView.Folder("academico"))
        await { reader.state.view == LibraryView.Folder("academico") }
        settle(reader)

        reader.deleteFolder("academico") { folderRemoveMessage(it, PtBrDocsStrings) }

        await { reader.state.view == LibraryView.All }
    }

    @Test
    fun `a refused folder deletion says why and leaves the disk alone`() = runBlocking {
        putFile("init/contrato.pdf")
        val reader = withFiles()
        settle(reader)

        reader.deleteFolder("init") { folderRemoveMessage(it, PtBrDocsStrings) }

        await { reader.state.message != null }
        assertEquals(PtBrDocsStrings.deleteFolderInbox, reader.state.message)
        assertTrue(Files.exists(root.resolve("init/contrato.pdf")))
        assertNull(reader.state.deleteFolderPath)
    }

    @Test
    fun `moving a folder updates the tree and follows the selection`() = runBlocking {
        putFile("origem/sub/nota.pdf")
        Files.createDirectories(root.resolve("destino"))
        val reader = withFiles()
        reader.refresh()
        await { !reader.state.isScanning && folderPaths(reader).contains("origem/sub") }
        reader.selectView(LibraryView.Folder("origem/sub"))
        await { reader.state.view == LibraryView.Folder("origem/sub") }
        settle(reader)

        reader.moveFolder("origem", "destino") { folderTreeMoveMessage(it, PtBrDocsStrings) }

        await { reader.state.message != null }
        assertEquals("Pasta movida: destino/origem", reader.state.message)
        assertTrue(Files.isDirectory(root.resolve("destino/origem/sub")))
        assertFalse(Files.exists(root.resolve("origem")))
        await { reader.state.view == LibraryView.Folder("destino/origem/sub") }
        await { folderPaths(reader).contains("destino/origem/sub") }
        assertEquals(
            "destino/origem/sub/nota.pdf",
            database.docDao().findByPath("destino/origem/sub/nota.pdf")?.relPath
        )
    }

    @Test
    fun `a refused folder move says why and leaves the tree alone`() = runBlocking {
        Files.createDirectories(root.resolve("init/dentro"))
        Files.createDirectories(root.resolve("outra"))
        val reader = withFiles()
        settle(reader)

        reader.moveFolder("init", "outra") { folderTreeMoveMessage(it, PtBrDocsStrings) }

        await { reader.state.message != null }
        assertEquals(PtBrDocsStrings.folderTreeMoveInbox, reader.state.message)
        assertTrue(Files.isDirectory(root.resolve("init/dentro")))
    }

    @Test
    fun `deleting a tag clears the filter and the row`() = runBlocking {
        val doc = addDoc("prova.pdf")
        controller.createTag(doc, "academico/faculdade", "inválida")
        await { controller.state.tags.isNotEmpty() && controller.state.tagsOf(doc).isNotEmpty() }
        controller.filterByTag("academico")
        await { controller.state.tagFilters == setOf("academico") }
        settle(controller)

        controller.deleteTag("academico") { removal ->
            tagRemoveMessage("academico", removal, PtBrDocsStrings)
        }

        await { controller.state.tags.isEmpty() && controller.state.tagFilters.isEmpty() }
        assertEquals("Etiqueta #academico apagada (1 documento)", controller.state.message)
        assertTrue(controller.state.tagsOf(doc).isEmpty())
    }

    @Test
    fun `deleting a tag that is not there reports it`() = runBlocking {
        settle(controller)

        controller.deleteTag("nao-existe") { removal ->
            tagRemoveMessage("nao-existe", removal, PtBrDocsStrings)
        }

        await { controller.state.message != null }
        assertEquals(PtBrDocsStrings.deleteTagFailed, controller.state.message)
    }

    @Test
    fun `describing a folder stores the sentence and shows it in the sidebar`() = runBlocking {
        putFile("faculdade/2026/prova.pdf")
        val reader = withFiles()
        await { folderPaths(reader).contains("faculdade/2026") }
        settle(reader)

        reader.openDescribe(DescribeTarget.Folder("faculdade/2026"))
        assertEquals(DescribeTarget.Folder("faculdade/2026"), reader.state.describing)

        reader.saveDescription("provas e trabalhos do semestre") { saved ->
            if (saved) "salva" else "falhou"
        }

        await { reader.state.folderDescriptions["faculdade/2026"] != null }
        assertEquals(
            "provas e trabalhos do semestre",
            reader.state.folderDescriptions["faculdade/2026"]
        )
        assertEquals("salva", reader.state.message)
        // The dialog closes itself on success, so the next open shows the stored
        // text instead of an empty field.
        assertNull(reader.state.describing)
        assertEquals(
            "provas e trabalhos do semestre",
            database.folderDescriptionDao().find("faculdade/2026")?.description
        )
    }

    @Test
    fun `describing a tag writes on the tag row and into the state`() = runBlocking {
        database.tagDao().insert(Tag(path = "academico"))
        val reader = describingController()
        await { reader.state.tags.isNotEmpty() }

        reader.openDescribe(DescribeTarget.Tag("academico"))
        reader.saveDescription("tudo que é da faculdade") { "salva" }

        await { reader.state.tagDescriptions["academico"] != null }
        assertEquals("tudo que é da faculdade", reader.state.tagDescriptions["academico"])
        assertEquals(
            "tudo que é da faculdade",
            database.tagDao().findByPath("academico")?.description
        )
    }

    @Test
    fun `an empty description erases the stored one`() = runBlocking {
        database.folderDescriptionDao().put(
            FolderDescription(path = "faculdade", description = "tudo da faculdade")
        )
        val reader = describingController()
        await { reader.state.folderDescriptions["faculdade"] != null }

        reader.openDescribe(DescribeTarget.Folder("faculdade"))
        reader.saveDescription("   ") { saved -> if (saved) "apagada" else "falhou" }

        await { reader.state.folderDescriptions.isEmpty() }
        assertEquals("apagada", reader.state.message)
        assertNull(database.folderDescriptionDao().find("faculdade"))
    }

    @Test
    fun `deleting a folder forgets the description it had, subfolders included`() = runBlocking {
        putFile("academico/2026/prova.pdf")
        val reader = withFiles()
        await { folderPaths(reader).contains("academico/2026") }
        database.folderDescriptionDao().put(
            FolderDescription(path = "academico", description = "tudo da faculdade")
        )
        database.folderDescriptionDao().put(
            FolderDescription(path = "academico/2026", description = "este ano")
        )
        await { reader.state.folderDescriptions.size == 2 }
        settle(reader)

        reader.deleteFolder("academico") { folderRemoveMessage(it, PtBrDocsStrings) }

        await { reader.state.folderDescriptions.isEmpty() }
        // The rows are gone too: a folder recreated later must not come back
        // wearing a sentence about the old one.
        assertTrue(database.folderDescriptionDao().all().isEmpty())
    }

    @Test
    fun `without the description repository the dialog does not open`() {
        // The controller of `setUp` has no repository; asking for the dialog is
        // inert instead of opening a field that cannot save.
        controller.openDescribe(DescribeTarget.Folder("academico"))

        assertNull(controller.state.describing)
    }

    private fun withAi(ai: AiController) = LibraryController(
        root = root,
        dao = database.docDao(),
        tagDao = database.tagDao(),
        tags = TagRepository(database.tagDao()),
        indexer = DocIndexer(root, database.docDao()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        ai = ai
    )

    @Test
    fun `the AI dialog opens with the settings in force and closes without saving`() = runBlocking {
        var saves = 0
        val reader = withAi(object : AiController {
            override fun current() = AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash", apiKey = "sk-1")
            override suspend fun test(candidate: AiSettings) = true
            override fun save(candidate: AiSettings, key: KeyChange): Boolean {
                saves += 1
                return true
            }
        })

        reader.openAiSettings()
        assertEquals("deepseek-flash", reader.state.aiSettings?.model)
        assertNull(reader.state.aiTestOk)

        reader.closeAiSettings()

        assertNull(reader.state.aiSettings)
        assertEquals(0, saves)
    }

    @Test
    fun `testing reports the answer without saving anything`() = runBlocking {
        var saves = 0
        val reader = withAi(object : AiController {
            override fun current() = AiSettings()
            override suspend fun test(candidate: AiSettings) = candidate.model == "bom"
            override fun save(candidate: AiSettings, key: KeyChange): Boolean {
                saves += 1
                return true
            }
        })
        reader.openAiSettings()

        reader.testAi(AiSettings(model = "bom"))
        await { !reader.state.isTestingAi && reader.state.aiTestOk == true }

        reader.testAi(AiSettings(model = "ruim"))
        await { !reader.state.isTestingAi && reader.state.aiTestOk == false }

        assertEquals(0, saves)
        assertNotNull(reader.state.aiSettings)
        Unit
    }

    @Test
    fun `saving closes the dialog, reports the result and carries the typed key`() = runBlocking {
        var saved: AiSettings? = null
        var savedKey: KeyChange? = null
        val reader = withAi(object : AiController {
            override fun current() = AiSettings()
            override suspend fun test(candidate: AiSettings) = true
            override fun save(candidate: AiSettings, key: KeyChange): Boolean {
                saved = candidate
                savedKey = key
                return true
            }
        })
        reader.openAiSettings()

        reader.saveAi(
            AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash", apiKey = "sk-nova"),
            KeyChange.Set("sk-nova"),
            "salvo",
            "falhou"
        )

        await { reader.state.message != null }
        assertEquals("salvo", reader.state.message)
        assertNull(reader.state.aiSettings)
        assertEquals("deepseek-flash", saved?.model)
        // The key leaves the dialog as its own instruction, not hidden in the
        // candidate: "keep the file's key" and "write this one" stay distinct.
        assertEquals(KeyChange.Set("sk-nova"), savedKey)
    }

    @Test
    fun `removing the key saves with an explicit removal and reports it`() = runBlocking {
        var savedKey: KeyChange? = null
        val reader = withAi(object : AiController {
            override fun current() = AiSettings(apiKey = "sk-antiga")
            override suspend fun test(candidate: AiSettings) = true
            override fun save(candidate: AiSettings, key: KeyChange): Boolean {
                savedKey = key
                return true
            }
        })
        reader.openAiSettings()

        reader.removeAiKey(AiSettings(apiKey = "sk-antiga"), "removida", "falhou")

        await { reader.state.message != null }
        assertEquals("removida", reader.state.message)
        assertEquals(KeyChange.Remove, savedKey)
        assertNull(reader.state.aiSettings)
    }

    @Test
    fun `a refused save keeps the dialog message honest`() = runBlocking {
        val reader = withAi(object : AiController {
            override fun current() = AiSettings()
            override suspend fun test(candidate: AiSettings) = false
            override fun save(candidate: AiSettings, key: KeyChange) = false
        })
        reader.openAiSettings()

        reader.saveAi(AiSettings(model = "x"), KeyChange.Keep, "salvo", "falhou")

        await { reader.state.message != null }
        assertEquals("falhou", reader.state.message)
    }

    @Test
    fun `ensureTag creates a tag without a document so the sidebar can show it`() = runBlocking {
        val reader = inheritingController()

        reader.ensureTag("termo", "inválida", { "criada:#$it" }, "falhou")

        await {
            PathTree.flatten(reader.state.tags).any { it.path == "termo" } &&
                reader.state.message == "criada:#termo"
        }
        assertEquals(0, PathTree.flatten(reader.state.tags).first { it.path == "termo" }.count)
    }

    @Test
    fun `documents inherit tags declared on their folder and ancestors`() = runBlocking {
        val nested = addDoc("profissional/tebas/notas-fiscais/2024/nfe.pdf")
        val sibling = addDoc("outro/arquivo.pdf")
        val reader = inheritingController()
        await { reader.state.docs.size == 2 }

        reader.openInheritTags("profissional/tebas/notas-fiscais")
        assertEquals("profissional/tebas/notas-fiscais", reader.state.inheritingFolder)
        reader.saveInheritedTags(listOf("nota-fiscal")) { if (it) "ok" else "falhou" }

        await { reader.state.tagsOf(nested) == listOf("nota-fiscal") }
        assertEquals(emptyList(), reader.state.assignments[nested].orEmpty())
        assertEquals(listOf("nota-fiscal"), reader.state.inheritedOf(nested))
        assertEquals(emptyList(), reader.state.tagsOf(sibling))
        assertTrue(reader.state.isInherited(nested, "nota-fiscal"))
    }

    @Test
    fun `filtering by an inherited tag finds documents under the folder`() = runBlocking {
        val nested = addDoc("notas-fiscais/2024/nfe.pdf")
        addDoc("outro/arquivo.pdf")
        val reader = inheritingController()
        await { reader.state.docs.size == 2 }

        reader.openInheritTags("notas-fiscais")
        reader.saveInheritedTags(listOf("nota-fiscal")) { "ok" }
        await { reader.state.folderInherited["notas-fiscais"] == listOf("nota-fiscal") }

        reader.filterByTag("nota-fiscal")
        await { reader.state.docs.map { it.id } == listOf(nested) }
    }

    @Test
    fun `inherited tags count in the sidebar`() = runBlocking {
        addDoc("notas-fiscais/a.pdf")
        addDoc("notas-fiscais/sub/b.pdf")
        val reader = inheritingController()
        await { reader.state.docs.size == 2 }

        reader.openInheritTags("notas-fiscais")
        reader.saveInheritedTags(listOf("nota-fiscal")) { "ok" }

        await {
            PathTree.flatten(reader.state.tags).firstOrNull { it.path == "nota-fiscal" }?.count == 2
        }
    }

    @Test
    fun `toggling cannot remove an inherited tag`() = runBlocking {
        val doc = addDoc("notas-fiscais/a.pdf")
        val reader = inheritingController()
        await { reader.state.docs.size == 1 }

        reader.openInheritTags("notas-fiscais")
        reader.saveInheritedTags(listOf("nota-fiscal")) { "ok" }
        await { reader.state.isInherited(doc, "nota-fiscal") }

        reader.toggleTag(doc, "nota-fiscal", "falhou")
        delay(50)
        assertEquals(listOf("nota-fiscal"), reader.state.tagsOf(doc))
        assertEquals(emptyList(), reader.state.assignments[doc].orEmpty())
    }

    @Test
    fun `inbox and root cannot open inherit tags`() = runBlocking {
        val reader = inheritingController()
        reader.openInheritTags("init")
        assertNull(reader.state.inheritingFolder)
        reader.openInheritTags("")
        assertNull(reader.state.inheritingFolder)
    }

    @Test
    fun `deleting a folder forgets its inherited tags`() = runBlocking {
        Files.createDirectories(root.resolve("notas-fiscais"))
        val doc = addDoc("notas-fiscais/a.pdf")
        Files.writeString(root.resolve("notas-fiscais/a.pdf"), "x")
        val reader = inheritingController()
        await { reader.state.docs.size == 1 }

        reader.openInheritTags("notas-fiscais")
        reader.saveInheritedTags(listOf("nota-fiscal")) { "ok" }
        await { reader.state.folderInherited.containsKey("notas-fiscais") }

        reader.openDeleteFolder("notas-fiscais")
        await { reader.state.deleteFolderFiles != null }
        reader.deleteFolder("notas-fiscais") { "apagou" }

        await { !reader.state.folderInherited.containsKey("notas-fiscais") }
        delay(50)
        assertFalse(reader.state.folderInherited.containsKey("notas-fiscais"))
        assertTrue(reader.state.tagsOf(doc).isEmpty())
        assertEquals(
            com.openappslabs.docs.data.model.DocStatus.MISSING,
            database.docDao().findById(doc)?.status
        )
    }

    private fun flattenPaths(): List<String> {
        val paths = mutableListOf<String>()
        fun visit(nodes: List<com.openappslabs.docs.data.tree.PathNode>) {
            nodes.forEach {
                paths.add(it.path)
                visit(it.children)
            }
        }
        visit(controller.state.tags)
        return paths.sorted()
    }

    private suspend fun awaitIdle() {
        withTimeout(10_000) {
            while (controller.state.isScanning) delay(10)
        }
        // One more tick so the collected flows have delivered the new rows.
        delay(50)
    }
}
