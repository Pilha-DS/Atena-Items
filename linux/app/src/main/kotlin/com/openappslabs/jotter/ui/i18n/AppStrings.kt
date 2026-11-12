/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.ui.i18n

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Languages the UI can be displayed in. */
enum class AppLanguage(val key: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    PORTUGUESE_BR("pt-BR");

    /** Whether this is a concrete language (not "follow the system"). */
    val isConcrete: Boolean get() = this != SYSTEM

    /** Locale used for dates, time and number formatting. */
    fun locale(system: Locale = Locale.getDefault()): Locale = when (this) {
        SYSTEM -> system
        ENGLISH -> Locale.forLanguageTag("en")
        PORTUGUESE_BR -> Locale.forLanguageTag("pt-BR")
    }

    companion object {
        fun fromKey(key: String?): AppLanguage =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Resolves the language to render with, following the system locale when the
 * user did not pick one explicitly.
 */
fun AppLanguage.resolved(system: Locale = Locale.getDefault()): AppLanguage = when {
    isConcrete -> this
    system.language.equals("pt", ignoreCase = true) -> AppLanguage.PORTUGUESE_BR
    else -> AppLanguage.ENGLISH
}

/** Name shown in the language picker. Native names are never translated. */
fun AppLanguage.label(strings: AppStrings): String = when (this) {
    AppLanguage.SYSTEM -> strings.systemDefault
    AppLanguage.ENGLISH -> "English"
    AppLanguage.PORTUGUESE_BR -> "Português (Brasil)"
}

/**
 * Every user-visible string of the app.
 *
 * A plain interface (instead of Android string resources) keeps the desktop and
 * the Android app on the same source of truth, and lets the language change at
 * runtime without recreating the activity or the window.
 */
@Immutable
interface AppStrings {

    val language: AppLanguage
    val locale: Locale

    // ---------------------------------------------------------------- common
    val appName: String
    val untitled: String
    val uncategorized: String
    val uncategorizedUpper: String
    val all: String
    val add: String
    val back: String
    val cancel: String
    val save: String
    val delete: String
    val remove: String
    val edit: String
    val restore: String
    val restoreAll: String
    val unlock: String
    val lock: String
    val pin: String
    val done: String
    val dismiss: String
    val gotIt: String
    val close: String
    val clear: String
    val share: String
    val search: String
    val settings: String
    val processing: String
    val editMode: String
    val viewMode: String
    val startTyping: String
    val typeAnything: String
    val words: String
    val characters: String
    val created: String
    val modified: String
    val category: String
    val none: String
    val default: String

    // ------------------------------------------------------------------ home
    val searchNotes: String
    val profile: String
    val clearSearch: String
    val createNote: String
    val showGeneralNote: String
    val hideGeneralNote: String
    val noNotesFound: String
    val noNotesInCategory: (String) -> String
    val noNotesMatch: (String) -> String
    val notesInCategoryHint: String
    val noNotesYet: String
    val createFirstNoteHint: String
    val noteMovedToTrash: String
    val authenticationFailed: String
    val unlockNote: String
    val authenticateToViewLockedNote: String
    val noteHasOwnPassword: String
    val enableNoteLockInSettings: String
    val encryptedNote: String
    val lockedNote: String
    val unlockToRead: String
    val pinnedLabel: String
    val lockedLabel: String
    val noteSettingsDescription: String

    // ----------------------------------------------------------------- trash
    val trash: String
    val trashSubtitle: String
    val emptyTrash: String
    val nothingInTrash: String
    val notesYouDeleteAppearHere: String
    val emptyTrashQuestion: String
    val emptyTrashMessage: String
    val restoreAllNotesQuestion: String
    val restoreAllNotesMessage: (Int) -> String
    val noteCount: (Int) -> String

    // ------------------------------------------------------------ categories
    val manageCategories: String
    val createCategoriesToOrganize: String
    val categoryName: String
    val updateName: String
    val existingCategories: String
    val categoryAlreadyExists: (String) -> String
    val deleteCategoryQuestion: String
    val deleteCategoryMessage: (String) -> String
    val chooseCategory: String

    // ----------------------------------------------------------- note editor
    val noteSettings: String
    val pinNote: String
    val unpinNote: String
    val lockNote: String
    val unlockNoteAction: String
    val removeNotePassword: String
    val encryptWithPassword: String
    val moveToTrash: String
    val openIn: String
    val openInDefault: String
    val openInEdit: String
    val openInRead: String
    val actions: String
    val noteCopiedToClipboard: String
    val discardChangesQuestion: String
    val discardChangesMessage: String
    val discard: String
    val restoreNoteQuestion: String
    val restoreNoteMessage: String
    val encryptThisNote: String
    val protectedByOwnPassword: String
    val independentFromAppLock: String
    val thisNoteIsEncrypted: String
    val enterItsOwnPassword: String
    val editCategory: String

    // ------------------------------------------------------------ note format
    val format: String
    val formatName: (String) -> String
    val formatPlainText: String
    val formatShownAs: (String) -> String
    val formatAsTyped: String

    // ------------------------------------------------------- note password
    val minPasswordCharacters: (Int) -> String
    val passwordsDoNotMatch: String
    val couldNotEncryptNote: String
    val wrongPassword: String
    val encryptNoteTitle: String
    val unlockNoteTitle: String
    val removeEncryptionTitle: String
    val encryptNoteDescription: String
    val enterNotePassword: String
    val enterNotePasswordToRemove: String
    val encryptAction: String
    val password: String
    val confirmPassword: String

    // ------------------------------------------------------------ app lock
    val jotterLocked: String
    val enterPinToContinue: String
    val authenticateToOpenApp: String
    val enterYourPin: String
    val createPin: String
    val createPinSubtitle: String
    val wrongPin: String
    val useAtLeast4Digits: String
    val confirmIdentity: String
    val enterPinToDisableNoteLock: String
    val enterPinToDisableAppLock: String

    // ------------------------------------------------------------- settings
    val appearanceTitle: String
    val lookAndFeel: String
    val miscellaneous: String
    val categoriesAndPreferences: String
    val security: String
    val securitySubtitle: String
    val dataManagement: String
    val manageYourData: String
    val privacyPolicy: String
    val privacyPolicySubtitle: String
    val about: String
    val aboutSubtitle: String
    val couldNotOpenLink: String

    // ------------------------------------------------------------- language
    val appLanguage: String
    val appLanguageSubtitle: String
    val systemDefault: String
    val systemLanguageHint: String

    // ----------------------------------------------------------- appearance
    val appTheme: String
    val selectAppAppearance: String
    val trueDarkMode: String
    val pureBlackForOled: String
    val dynamicColors: String
    val adaptToWallpaper: String
    val defaultOpenMode: String
    val defaultOpenModeSubtitle: String
    val defaultViewMode: String
    val gridView: String
    val listView: String
    val defaultTimeFormat: String
    val clock24: String
    val clock12: String
    val dateFormat: String
    val changeHowDatesAppear: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String

    // ------------------------------------------------------------- security
    val noteLock: String
    val noteLockSubtitle: String
    val appLock: String
    val appLockSubtitle: String
    val requireAuthToOpen: String
    val authenticateToDisableNoteLock: String
    val secureScreen: String
    val disableScreenshots: String
    val disableNoteLockQuestion: String
    val disableNoteLockMessage: String
    val disableLock: String

    // ------------------------------------------------------------- misc
    val categories: String
    val categoriesSubtitle: String
    val showAddCategoryButton: String
    val showOrHidePlus: String
    val hapticFeedback: String
    val vibrateOnTouch: String
    val showSortButton: String
    val showOrHideSortButton: String

    // ------------------------------------------------------ data management
    val exportNotes: String
    val exportNotesSubtitle: String
    val importNotes: String
    val importNotesSubtitle: String
    val clearAllLocalData: String
    val clearAllLocalDataSubtitle: String
    val clearAllDataQuestion: String
    val clearAllDataMessage: String
    val clearAllDataAction: String
    val invalidBackup: String

    // ------------------------------------------------------------------ sync
    val sync: String
    val syncSubtitle: String
    val syncServerUrl: String
    val syncServerUrlHint: String
    val syncToken: String
    val syncTokenHint: String
    val syncDeviceName: String
    val syncDeviceNameTitle: String
    val syncDeviceNameHint: String
    val syncDevice: String
    val syncChecking: String
    val syncEdit: String
    val syncNow: String
    val syncConnect: String
    val syncDisconnect: String
    val syncLastSync: String
    val syncNever: String
    val syncNotConnected: String
    val syncResult: (Int, Int) -> String
    val syncRunning: String
    val syncLocalFirst: String
    val syncErrorNotConfigured: String
    val syncErrorUnauthorized: String
    val syncErrorNetwork: String
    val syncErrorServer: String
    val syncErrorResponse: String
    val syncClockSkew: (String) -> String
    val syncDisconnectTitle: String
    val syncDisconnectMessage: String

    // ------------------------------------------------------- backup dialog
    val noDataFound: String
    val backupSaved: String
    val restoreComplete: String
    val operationFailed: String
    val noDataToExportMessage: String
    val exportSuccessMessage: String
    val importSuccessMessage: String
    val unknownError: String

    // ---------------------------------------------------------------- sort
    val sortNotes: String
    val ascending: String
    val descending: String
    val sortAlphabetical: String
    val sortDateCreated: String
    val sortLastUpdated: String

    // --------------------------------------------------------------- about
    val labelApp: String
    val labelVersion: String
    val labelOrganization: String
    val labelSourceCode: String
    val labelSupport: String
    val labelLicense: String
    val labelMadeWithLove: String
    val developerRole: String

    // ----------------------------------------------------------- desktop shell
    val generalNote: String
    val generalNoteTitle: String
    val closeGeneralNote: String
    val showWidget: String
    val hideWidget: String
    val alwaysOnTop: String
    val quit: String

    // ------------------------------------------------------- privacy policy
    val privacyIntroTitle: String
    val privacyIntroBody: String
    val privacyCollectTitle: String
    val privacyCollectBody: String
    val privacyUseTitle: String
    val privacyUseBody: String
    val privacyThirdPartyTitle: String
    val privacyThirdPartyBody: String
    val privacyContactTitle: String
    val privacyContactBody: String
    val privacyLastUpdated: String
}

/** Shared fallbacks so a missing translation never renders as a blank label. */
abstract class BaseStrings : AppStrings {
    override val formatName: (String) -> String = { it }
    override val noNotesInCategory: (String) -> String = { it }
}

private val EnLocale: Locale = Locale.forLanguageTag("en")

object EnglishStrings : BaseStrings() {
    override val language = AppLanguage.ENGLISH
    override val locale: Locale = EnLocale

    override val appName = "Jotter"
    override val untitled = "Untitled"
    override val uncategorized = "Uncategorized"
    override val uncategorizedUpper = "UNCATEGORIZED"
    override val all = "All"
    override val add = "Add"
    override val back = "Back"
    override val cancel = "Cancel"
    override val save = "Save"
    override val delete = "Delete"
    override val remove = "Remove"
    override val edit = "Edit"
    override val restore = "Restore"
    override val restoreAll = "Restore all"
    override val unlock = "Unlock"
    override val lock = "Lock"
    override val pin = "PIN"
    override val done = "Done"
    override val dismiss = "Dismiss"
    override val gotIt = "Got it"
    override val close = "Close"
    override val clear = "Clear"
    override val share = "Share"
    override val search = "Search"
    override val settings = "Settings"
    override val processing = "Processing..."
    override val editMode = "Edit mode"
    override val viewMode = "View mode"
    override val startTyping = "Start typing..."
    override val typeAnything = "Type anything…"
    override val words = "Words"
    override val characters = "Characters"
    override val created = "Created"
    override val modified = "Modified"
    override val category = "Category"
    override val none = "None"
    override val default = "Default"

    override val searchNotes = "Search notes"
    override val profile = "Profile"
    override val clearSearch = "Clear search"
    override val createNote = "Create note"
    override val showGeneralNote = "Show general note"
    override val hideGeneralNote = "Hide general note"
    override val noNotesFound = "No notes found"
    override val noNotesInCategory = { category: String -> "No notes in $category" }
    override val noNotesMatch = { query: String -> "No notes match \"$query\"" }
    override val notesInCategoryHint = "Notes added to this category will appear here."
    override val noNotesYet = "No notes yet"
    override val createFirstNoteHint = "Tap the + button to create your first note."
    override val noteMovedToTrash = "Note moved to trash"
    override val authenticationFailed = "Authentication failed"
    override val unlockNote = "Unlock note"
    override val authenticateToViewLockedNote = "Authenticate to view this locked note"
    override val noteHasOwnPassword = "This note has its own password"
    override val enableNoteLockInSettings =
        "Enable Note Lock in Settings to use this feature"
    override val encryptedNote = "Encrypted note"
    override val lockedNote = "Locked note"
    override val unlockToRead = "Unlock to read"
    override val pinnedLabel = "Pinned"
    override val lockedLabel = "Locked"
    override val noteSettingsDescription = "Note settings"

    override val trash = "Trash"
    override val trashSubtitle = "Notes you delete appear here"
    override val emptyTrash = "Empty trash"
    override val nothingInTrash = "Nothing in the trash"
    override val notesYouDeleteAppearHere = "Notes you delete will appear here."
    override val emptyTrashQuestion = "Empty trash?"
    override val emptyTrashMessage =
        "Are you sure you want to permanently delete all notes in trash? This action cannot be undone."
    override val restoreAllNotesQuestion = "Restore all notes?"
    override val restoreAllNotesMessage = { count: Int ->
        "Restore $count ${if (count == 1) "note" else "notes"} to your main list?"
    }
    override val noteCount = { count: Int -> if (count == 1) "1 note" else "$count notes" }

    override val manageCategories = "Manage categories"
    override val createCategoriesToOrganize = "Create categories to organize your notes."
    override val categoryName = "Category name"
    override val updateName = "Update name"
    override val existingCategories = "Existing categories"
    override val categoryAlreadyExists = { name: String ->
        "Category \"$name\" already exists"
    }
    override val deleteCategoryQuestion = "Delete category?"
    override val deleteCategoryMessage = { name: String ->
        "Are you sure you want to delete \"$name\"? Notes in this category will become uncategorized."
    }
    override val chooseCategory = "Choose category"

    override val noteSettings = "Note settings"
    override val pinNote = "Pin"
    override val unpinNote = "Unpin"
    override val lockNote = "Lock"
    override val unlockNoteAction = "Unlock"
    override val removeNotePassword = "Remove note password"
    override val encryptWithPassword = "Encrypt with password"
    override val moveToTrash = "Move to trash"
    override val openIn = "Open in"
    override val openInDefault = "Default"
    override val openInEdit = "Edit"
    override val openInRead = "Read"
    override val actions = "Actions"
    override val noteCopiedToClipboard = "Note copied to clipboard"
    override val discardChangesQuestion = "Discard changes?"
    override val discardChangesMessage =
        "You have unsaved changes. If you discard them now, your changes will be lost."
    override val discard = "Discard"
    override val restoreNoteQuestion = "Restore note?"
    override val restoreNoteMessage =
        "Are you sure you want to restore this note to your main list?"
    override val encryptThisNote = "Encrypt this note"
    override val protectedByOwnPassword = "Protected by its own password"
    override val independentFromAppLock = "Independent from the app lock"
    override val thisNoteIsEncrypted = "This note is encrypted"
    override val enterItsOwnPassword = "Enter its own password to read and edit it."
    override val editCategory = "Edit category"

    override val format = "Format"
    override val formatName = { name: String -> name }
    override val formatPlainText = "Plain text"
    override val formatShownAs = { name: String ->
        "Shown as $name in read mode. The text itself is unchanged."
    }
    override val formatAsTyped = "Shown exactly as typed."

    override val minPasswordCharacters = { count: Int -> "Use at least $count characters" }
    override val passwordsDoNotMatch = "Passwords do not match"
    override val couldNotEncryptNote = "Could not encrypt this note"
    override val wrongPassword = "Wrong password"
    override val encryptNoteTitle = "Encrypt this note"
    override val unlockNoteTitle = "Unlock note"
    override val removeEncryptionTitle = "Remove encryption"
    override val encryptNoteDescription =
        "This note gets its own password, independent from the app lock.\n\n" +
            "There is no recovery: if you forget it, the note cannot be read again."
    override val enterNotePassword = "Enter the password of this note."
    override val enterNotePasswordToRemove =
        "Enter the password of this note to store it unprotected again."
    override val encryptAction = "Encrypt"
    override val password = "Password"
    override val confirmPassword = "Confirm password"

    override val jotterLocked = "Jotter is locked"
    override val enterPinToContinue = "Enter your PIN to continue"
    override val authenticateToOpenApp = "Authenticate to open the app"
    override val enterYourPin = "Enter your PIN"
    override val createPin = "Create a PIN"
    override val createPinSubtitle = "Use at least 4 digits to protect locked notes"
    override val wrongPin = "Wrong PIN"
    override val useAtLeast4Digits = "Use at least 4 digits"
    override val confirmIdentity = "Confirm identity"
    override val enterPinToDisableNoteLock = "Enter your PIN to disable note lock"
    override val enterPinToDisableAppLock = "Enter your PIN to disable app lock"

    override val appearanceTitle = "Appearance"
    override val lookAndFeel = "Look & feel"
    override val miscellaneous = "Miscellaneous"
    override val categoriesAndPreferences = "Categories & preferences"
    override val security = "Security"
    override val securitySubtitle = "Lock notes, protect your content"
    override val dataManagement = "Data management"
    override val manageYourData = "Manage your data"
    override val privacyPolicy = "Privacy policy"
    override val privacyPolicySubtitle = "How I handle your data"
    override val about = "About"
    override val aboutSubtitle = "Info & support"
    override val couldNotOpenLink = "Could not open the link"

    override val appLanguage = "App language"
    override val appLanguageSubtitle = "Language used across the app"
    override val systemDefault = "System default"
    override val systemLanguageHint = "Follows the system language"

    override val appTheme = "App theme"
    override val selectAppAppearance = "Select app appearance"
    override val trueDarkMode = "True dark mode"
    override val pureBlackForOled = "Pure black for OLED displays"
    override val dynamicColors = "Dynamic colors"
    override val adaptToWallpaper = "Adapt to wallpaper"
    override val defaultOpenMode = "Default open mode"
    override val defaultOpenModeSubtitle = "View or edit. Notes can override it"
    override val defaultViewMode = "Default view mode"
    override val gridView = "Grid view"
    override val listView = "List view"
    override val defaultTimeFormat = "Default time format"
    override val clock24 = "24-hour clock"
    override val clock12 = "12 hour (AM/PM)"
    override val dateFormat = "Date format"
    override val changeHowDatesAppear = "Change how dates appear"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"

    override val noteLock = "Note lock"
    override val noteLockSubtitle = "Require your PIN to open locked notes"
    override val appLock = "App lock"
    override val appLockSubtitle = "Require your PIN to open Jotter"
    override val requireAuthToOpen = "Require authentication to open"
    override val authenticateToDisableNoteLock = "Authenticate to disable note lock"
    override val secureScreen = "Secure screen"
    override val disableScreenshots = "Disable screenshots"
    override val disableNoteLockQuestion = "Disable note lock?"
    override val disableNoteLockMessage =
        "This will remove the lock from all currently locked notes. They will become accessible without authentication."
    override val disableLock = "Disable lock"

    override val categories = "Categories"
    override val categoriesSubtitle = "Add, edit or remove categories"
    override val showAddCategoryButton = "Show add category button"
    override val showOrHidePlus = "Show or hide '+' on home screen"
    override val hapticFeedback = "Haptic feedback"
    override val vibrateOnTouch = "Vibrate on touch interactions"
    override val showSortButton = "Show sort button"
    override val showOrHideSortButton = "Show or hide sort button"

    override val exportNotes = "Export notes"
    override val exportNotesSubtitle = "Export notes to file"
    override val importNotes = "Import notes"
    override val importNotesSubtitle = "Import notes from file"
    override val clearAllLocalData = "Clear all local data"
    override val clearAllLocalDataSubtitle = "Delete all data permanently"
    override val clearAllDataQuestion = "Clear all data?"
    override val clearAllDataMessage =
        "Are you sure you want to clear all data? This action cannot be undone."
    override val clearAllDataAction = "Clear all data"
    override val invalidBackup = "Invalid or corrupted backup file"

    override val sync = "Sync"
    override val syncSubtitle = "Notes on every device, with a single URL and token"
    override val syncServerUrl = "Server"
    override val syncServerUrlHint = "notas.exemplo.com or 192.168.0.10:8080"
    override val syncToken = "Token"
    override val syncTokenHint = "Printed once in the server log"
    override val syncDeviceName = "Device name"
    override val syncDeviceNameTitle = "What should we call this device?"
    override val syncDeviceNameHint = "Shown in the server, so you know which one is which"
    override val syncDevice = "Device"
    override val syncChecking = "Checking the connection…"
    override val syncEdit = "Change server"
    override val syncNow = "Sync now"
    override val syncConnect = "Connect"
    override val syncDisconnect = "Disconnect"
    override val syncLastSync = "Last sync"
    override val syncNever = "Never"
    override val syncNotConnected = "Not connected"
    override val syncResult = { pushed: Int, pulled: Int -> "$pushed sent · $pulled received" }
    override val syncRunning = "Syncing…"
    override val syncLocalFirst =
        "Notes are saved on this device first; the server is only a copy."
    override val syncErrorNotConfigured = "Set the server URL and token first"
    override val syncErrorUnauthorized = "The server rejected the token"
    override val syncErrorNetwork = "Could not reach the server"
    override val syncErrorServer = "The server returned an error"
    override val syncErrorResponse = "Could not read the server response"
    override val syncClockSkew = { difference: String ->
        "This device's clock is $difference off the server. The newest note may lose the conflict."
    }
    override val syncDisconnectTitle = "Disconnect from the server?"
    override val syncDisconnectMessage =
        "The notes stay on this device. The server copy is left untouched."

    override val noDataFound = "No data found"
    override val backupSaved = "Backup saved"
    override val restoreComplete = "Restore complete"
    override val operationFailed = "Operation failed"
    override val noDataToExportMessage =
        "You don't have any notes. Please create some notes before exporting."
    override val exportSuccessMessage =
        "Your notes have been successfully exported to your device."
    override val importSuccessMessage =
        "Your notes and categories have been successfully restored."
    override val unknownError = "An unknown error occurred."

    override val sortNotes = "Sort notes"
    override val ascending = "Ascending"
    override val descending = "Descending"
    override val sortAlphabetical = "Alphabetical"
    override val sortDateCreated = "Date created"
    override val sortLastUpdated = "Last updated"

    override val labelApp = "APP"
    override val labelVersion = "VERSION"
    override val labelOrganization = "ORGANIZATION"
    override val labelSourceCode = "SOURCE CODE"
    override val labelSupport = "SUPPORT"
    override val labelLicense = "LICENSE"
    override val labelMadeWithLove = "MADE WITH LOVE"
    override val developerRole = "DEVELOPER"

    override val generalNote = "General note"
    override val generalNoteTitle = "Jotter · General note"
    override val closeGeneralNote = "Close general note"
    override val showWidget = "Show widget"
    override val hideWidget = "Hide widget"
    override val alwaysOnTop = "Always on top"
    override val quit = "Quit"

    override val privacyIntroTitle = "1. Introduction"
    override val privacyIntroBody =
        "Welcome to Jotter! I respect your privacy. This policy explains how I handle data in the app. If you have any questions, feel free to contact me."
    override val privacyCollectTitle = "2. Information I Collect"
    override val privacyCollectBody =
        "Jotter does not collect any personal data. All notes, categories, and preferences are stored locally on your device. No data is shared."
    override val privacyUseTitle = "3. Use of Your Information"
    override val privacyUseBody =
        "I do not use, share, or process any of your data. Your notes and information remain on your device and are used only for the functionality of the app."
    override val privacyThirdPartyTitle = "4. Third-Party Services"
    override val privacyThirdPartyBody =
        "Jotter does not use third-party services, including analytics, ads, or data collection tools. Your data stays private and local to your device."
    override val privacyContactTitle = "5. Contact Me"
    override val privacyContactBody =
        "If you have any questions or concerns, you can reach out to me via GitHub."
    override val privacyLastUpdated = "Last updated: December 03, 2025"
}

object PortugueseStrings : BaseStrings() {
    override val language = AppLanguage.PORTUGUESE_BR
    override val locale: Locale = Locale.forLanguageTag("pt-BR")

    override val appName = "Jotter"
    override val untitled = "Sem título"
    override val uncategorized = "Sem categoria"
    override val uncategorizedUpper = "SEM CATEGORIA"
    override val all = "Todas"
    override val add = "Adicionar"
    override val back = "Voltar"
    override val cancel = "Cancelar"
    override val save = "Salvar"
    override val delete = "Excluir"
    override val remove = "Remover"
    override val edit = "Editar"
    override val restore = "Restaurar"
    override val restoreAll = "Restaurar todas"
    override val unlock = "Desbloquear"
    override val lock = "Bloquear"
    override val pin = "PIN"
    override val done = "Concluído"
    override val dismiss = "Fechar"
    override val gotIt = "Entendi"
    override val close = "Fechar"
    override val clear = "Limpar"
    override val share = "Compartilhar"
    override val search = "Pesquisar"
    override val settings = "Configurações"
    override val processing = "Processando..."
    override val editMode = "Modo edição"
    override val viewMode = "Modo leitura"
    override val startTyping = "Comece a digitar..."
    override val typeAnything = "Digite qualquer coisa…"
    override val words = "Palavras"
    override val characters = "Caracteres"
    override val created = "Criada"
    override val modified = "Modificada"
    override val category = "Categoria"
    override val none = "Nenhuma"
    override val default = "Padrão"

    override val searchNotes = "Pesquisar notas"
    override val profile = "Perfil"
    override val clearSearch = "Limpar pesquisa"
    override val createNote = "Criar nota"
    override val showGeneralNote = "Mostrar nota geral"
    override val hideGeneralNote = "Ocultar nota geral"
    override val noNotesFound = "Nenhuma nota encontrada"
    override val noNotesInCategory = { category: String -> "Nenhuma nota em $category" }
    override val noNotesMatch = { query: String -> "Nenhuma nota corresponde a \"$query\"" }
    override val notesInCategoryHint =
        "As notas adicionadas a esta categoria aparecerão aqui."
    override val noNotesYet = "Nenhuma nota ainda"
    override val createFirstNoteHint = "Toque no botão + para criar sua primeira nota."
    override val noteMovedToTrash = "Nota movida para a lixeira"
    override val authenticationFailed = "Falha na autenticação"
    override val unlockNote = "Desbloquear nota"
    override val authenticateToViewLockedNote =
        "Autentique-se para ver esta nota bloqueada"
    override val noteHasOwnPassword = "Esta nota tem uma senha própria"
    override val enableNoteLockInSettings =
        "Ative o Bloqueio de notas nas Configurações para usar este recurso"
    override val encryptedNote = "Nota criptografada"
    override val lockedNote = "Nota bloqueada"
    override val unlockToRead = "Desbloqueie para ler"
    override val pinnedLabel = "Fixada"
    override val lockedLabel = "Bloqueada"
    override val noteSettingsDescription = "Configurações da nota"

    override val trash = "Lixeira"
    override val trashSubtitle = "As notas que você excluir aparecem aqui"
    override val emptyTrash = "Esvaziar lixeira"
    override val nothingInTrash = "Nada na lixeira"
    override val notesYouDeleteAppearHere = "As notas que você excluir aparecerão aqui."
    override val emptyTrashQuestion = "Esvaziar a lixeira?"
    override val emptyTrashMessage =
        "Tem certeza de que deseja excluir permanentemente todas as notas da lixeira? Esta ação não pode ser desfeita."
    override val restoreAllNotesQuestion = "Restaurar todas as notas?"
    override val restoreAllNotesMessage = { count: Int ->
        "Restaurar $count ${if (count == 1) "nota" else "notas"} para a sua lista principal?"
    }
    override val noteCount = { count: Int -> if (count == 1) "1 nota" else "$count notas" }

    override val manageCategories = "Gerenciar categorias"
    override val createCategoriesToOrganize = "Crie categorias para organizar suas notas."
    override val categoryName = "Nome da categoria"
    override val updateName = "Atualizar nome"
    override val existingCategories = "Categorias existentes"
    override val categoryAlreadyExists = { name: String ->
        "A categoria \"$name\" já existe"
    }
    override val deleteCategoryQuestion = "Excluir categoria?"
    override val deleteCategoryMessage = { name: String ->
        "Tem certeza de que deseja excluir \"$name\"? As notas desta categoria ficarão sem categoria."
    }
    override val chooseCategory = "Escolher categoria"

    override val noteSettings = "Configurações da nota"
    override val pinNote = "Fixar"
    override val unpinNote = "Desafixar"
    override val lockNote = "Bloquear"
    override val unlockNoteAction = "Desbloquear"
    override val removeNotePassword = "Remover senha da nota"
    override val encryptWithPassword = "Criptografar com senha"
    override val moveToTrash = "Mover para a lixeira"
    override val openIn = "Abrir em"
    override val openInDefault = "Padrão"
    override val openInEdit = "Editar"
    override val openInRead = "Leitura"
    override val actions = "Ações"
    override val noteCopiedToClipboard = "Nota copiada para a área de transferência"
    override val discardChangesQuestion = "Descartar alterações?"
    override val discardChangesMessage =
        "Você tem alterações não salvas. Se descartá-las agora, elas serão perdidas."
    override val discard = "Descartar"
    override val restoreNoteQuestion = "Restaurar nota?"
    override val restoreNoteMessage =
        "Tem certeza de que deseja restaurar esta nota para a sua lista principal?"
    override val encryptThisNote = "Criptografar esta nota"
    override val protectedByOwnPassword = "Protegida por senha própria"
    override val independentFromAppLock = "Independente do bloqueio do app"
    override val thisNoteIsEncrypted = "Esta nota está criptografada"
    override val enterItsOwnPassword = "Digite a senha própria para ler e editar a nota."
    override val editCategory = "Editar categoria"

    override val format = "Formato"
    override val formatPlainText = "Texto puro"
    override val formatShownAs = { name: String ->
        "Exibido como $name no modo leitura. O texto em si não muda."
    }
    override val formatAsTyped = "Exibido exatamente como foi digitado."

    override val minPasswordCharacters = { count: Int -> "Use pelo menos $count caracteres" }
    override val passwordsDoNotMatch = "As senhas não coincidem"
    override val couldNotEncryptNote = "Não foi possível criptografar esta nota"
    override val wrongPassword = "Senha incorreta"
    override val encryptNoteTitle = "Criptografar esta nota"
    override val unlockNoteTitle = "Desbloquear nota"
    override val removeEncryptionTitle = "Remover criptografia"
    override val encryptNoteDescription =
        "Esta nota terá uma senha própria, independente do bloqueio do app.\n\n" +
            "Não há recuperação: se você esquecê-la, a nota não poderá ser lida novamente."
    override val enterNotePassword = "Digite a senha desta nota."
    override val enterNotePasswordToRemove =
        "Digite a senha desta nota para guardá-la novamente sem proteção."
    override val encryptAction = "Criptografar"
    override val password = "Senha"
    override val confirmPassword = "Confirmar senha"

    override val jotterLocked = "O Jotter está bloqueado"
    override val enterPinToContinue = "Digite seu PIN para continuar"
    override val authenticateToOpenApp = "Autentique-se para abrir o app"
    override val enterYourPin = "Digite seu PIN"
    override val createPin = "Criar um PIN"
    override val createPinSubtitle =
        "Use pelo menos 4 dígitos para proteger notas bloqueadas"
    override val wrongPin = "PIN incorreto"
    override val useAtLeast4Digits = "Use pelo menos 4 dígitos"
    override val confirmIdentity = "Confirmar identidade"
    override val enterPinToDisableNoteLock =
        "Digite seu PIN para desativar o bloqueio de notas"
    override val enterPinToDisableAppLock =
        "Digite seu PIN para desativar o bloqueio do app"

    override val appearanceTitle = "Aparência"
    override val lookAndFeel = "Aparência e comportamento"
    override val miscellaneous = "Diversos"
    override val categoriesAndPreferences = "Categorias e preferências"
    override val security = "Segurança"
    override val securitySubtitle = "Bloqueie notas e proteja seu conteúdo"
    override val dataManagement = "Gerenciamento de dados"
    override val manageYourData = "Gerencie seus dados"
    override val privacyPolicy = "Política de privacidade"
    override val privacyPolicySubtitle = "Como trato seus dados"
    override val about = "Sobre"
    override val aboutSubtitle = "Informações e suporte"
    override val couldNotOpenLink = "Não foi possível abrir o link"

    override val appLanguage = "Idioma do app"
    override val appLanguageSubtitle = "Idioma usado em todo o app"
    override val systemDefault = "Padrão do sistema"
    override val systemLanguageHint = "Segue o idioma do sistema"

    override val appTheme = "Tema do app"
    override val selectAppAppearance = "Selecione a aparência do app"
    override val trueDarkMode = "Modo escuro absoluto"
    override val pureBlackForOled = "Preto puro para telas OLED"
    override val dynamicColors = "Cores dinâmicas"
    override val adaptToWallpaper = "Adaptar ao papel de parede"
    override val defaultOpenMode = "Modo de abertura padrão"
    override val defaultOpenModeSubtitle = "Leitura ou edição. Cada nota pode substituir"
    override val defaultViewMode = "Modo de exibição padrão"
    override val gridView = "Grade"
    override val listView = "Lista"
    override val defaultTimeFormat = "Formato de hora padrão"
    override val clock24 = "Formato 24 horas"
    override val clock12 = "12 horas (AM/PM)"
    override val dateFormat = "Formato da data"
    override val changeHowDatesAppear = "Altere como as datas aparecem"
    override val themeSystem = "Sistema"
    override val themeLight = "Claro"
    override val themeDark = "Escuro"

    override val noteLock = "Bloqueio de notas"
    override val noteLockSubtitle = "Exigir seu PIN para abrir notas bloqueadas"
    override val appLock = "Bloqueio do app"
    override val appLockSubtitle = "Exigir seu PIN para abrir o Jotter"
    override val requireAuthToOpen = "Exigir autenticação para abrir"
    override val authenticateToDisableNoteLock = "Autentique-se para desativar o bloqueio de notas"
    override val secureScreen = "Tela protegida"
    override val disableScreenshots = "Desativar capturas de tela"
    override val disableNoteLockQuestion = "Desativar o bloqueio de notas?"
    override val disableNoteLockMessage =
        "Isso removerá o bloqueio de todas as notas bloqueadas no momento. Elas ficarão acessíveis sem autenticação."
    override val disableLock = "Desativar bloqueio"

    override val categories = "Categorias"
    override val categoriesSubtitle = "Adicione, edite ou remova categorias"
    override val showAddCategoryButton = "Mostrar botão de adicionar categoria"
    override val showOrHidePlus = "Mostrar ou ocultar '+' na tela inicial"
    override val hapticFeedback = "Retorno tátil"
    override val vibrateOnTouch = "Vibrar nas interações de toque"
    override val showSortButton = "Mostrar botão de ordenação"
    override val showOrHideSortButton = "Mostrar ou ocultar o botão de ordenação"

    override val exportNotes = "Exportar notas"
    override val exportNotesSubtitle = "Exportar notas para um arquivo"
    override val importNotes = "Importar notas"
    override val importNotesSubtitle = "Importar notas de um arquivo"
    override val clearAllLocalData = "Apagar todos os dados locais"
    override val clearAllLocalDataSubtitle = "Excluir todos os dados permanentemente"
    override val clearAllDataQuestion = "Apagar todos os dados?"
    override val clearAllDataMessage =
        "Tem certeza de que deseja apagar todos os dados? Esta ação não pode ser desfeita."
    override val clearAllDataAction = "Apagar todos os dados"
    override val invalidBackup = "Arquivo de backup inválido ou corrompido"

    override val sync = "Sincronização"
    override val syncSubtitle = "Suas notas em todos os aparelhos, com uma URL e um token"
    override val syncServerUrl = "Servidor"
    override val syncServerUrlHint = "notas.exemplo.com ou 192.168.0.10:8080"
    override val syncToken = "Token"
    override val syncTokenHint = "Aparece uma vez no log do servidor"
    override val syncDeviceName = "Nome do aparelho"
    override val syncDeviceNameTitle = "Como chamar este aparelho?"
    override val syncDeviceNameHint = "Aparece no servidor, para você saber qual é qual"
    override val syncDevice = "Aparelho"
    override val syncChecking = "Testando a conexão…"
    override val syncEdit = "Trocar servidor"
    override val syncNow = "Sincronizar agora"
    override val syncConnect = "Conectar"
    override val syncDisconnect = "Desconectar"
    override val syncLastSync = "Última sincronização"
    override val syncNever = "Nunca"
    override val syncNotConnected = "Não conectado"
    override val syncResult = { pushed: Int, pulled: Int -> "$pushed enviadas · $pulled recebidas" }
    override val syncRunning = "Sincronizando…"
    override val syncLocalFirst =
        "As notas são salvas primeiro neste aparelho; o servidor é apenas uma cópia."
    override val syncErrorNotConfigured = "Preencha a URL do servidor e o token"
    override val syncErrorUnauthorized = "O servidor recusou o token"
    override val syncErrorNetwork = "Não foi possível falar com o servidor"
    override val syncErrorServer = "O servidor retornou um erro"
    override val syncErrorResponse = "Não foi possível ler a resposta do servidor"
    override val syncClockSkew = { difference: String ->
        "O relógio deste aparelho está $difference fora do servidor. A nota mais recente pode perder o conflito."
    }
    override val syncDisconnectTitle = "Desconectar do servidor?"
    override val syncDisconnectMessage =
        "As notas continuam neste aparelho. A cópia no servidor não é alterada."

    override val noDataFound = "Nenhum dado encontrado"
    override val backupSaved = "Backup salvo"
    override val restoreComplete = "Restauração concluída"
    override val operationFailed = "Falha na operação"
    override val noDataToExportMessage =
        "Você não tem nenhuma nota. Crie algumas notas antes de exportar."
    override val exportSuccessMessage =
        "Suas notas foram exportadas com sucesso para o seu dispositivo."
    override val importSuccessMessage =
        "Suas notas e categorias foram restauradas com sucesso."
    override val unknownError = "Ocorreu um erro desconhecido."

    override val sortNotes = "Ordenar notas"
    override val ascending = "Crescente"
    override val descending = "Decrescente"
    override val sortAlphabetical = "Alfabética"
    override val sortDateCreated = "Data de criação"
    override val sortLastUpdated = "Última atualização"

    override val labelApp = "APP"
    override val labelVersion = "VERSÃO"
    override val labelOrganization = "ORGANIZAÇÃO"
    override val labelSourceCode = "CÓDIGO-FONTE"
    override val labelSupport = "SUPORTE"
    override val labelLicense = "LICENÇA"
    override val labelMadeWithLove = "FEITO COM AMOR"
    override val developerRole = "DESENVOLVEDOR"

    override val generalNote = "Nota geral"
    override val generalNoteTitle = "Jotter · Nota geral"
    override val closeGeneralNote = "Fechar nota geral"
    override val showWidget = "Mostrar widget"
    override val hideWidget = "Ocultar widget"
    override val alwaysOnTop = "Sempre no topo"
    override val quit = "Sair"

    override val privacyIntroTitle = "1. Introdução"
    override val privacyIntroBody =
        "Bem-vindo ao Jotter! Respeito a sua privacidade. Esta política explica como trato os dados no app. Se tiver alguma dúvida, fique à vontade para entrar em contato."
    override val privacyCollectTitle = "2. Informações que coleto"
    override val privacyCollectBody =
        "O Jotter não coleta nenhum dado pessoal. Todas as notas, categorias e preferências são armazenadas localmente no seu dispositivo. Nenhum dado é compartilhado."
    override val privacyUseTitle = "3. Uso das suas informações"
    override val privacyUseBody =
        "Não uso, compartilho nem processo nenhum dos seus dados. Suas notas e informações permanecem no seu dispositivo e são usadas apenas para o funcionamento do app."
    override val privacyThirdPartyTitle = "4. Serviços de terceiros"
    override val privacyThirdPartyBody =
        "O Jotter não usa serviços de terceiros, incluindo análises, anúncios ou ferramentas de coleta de dados. Seus dados permanecem privados e locais no seu dispositivo."
    override val privacyContactTitle = "5. Fale comigo"
    override val privacyContactBody =
        "Se tiver alguma dúvida ou preocupação, você pode entrar em contato comigo pelo GitHub."
    override val privacyLastUpdated = "Última atualização: 03 de dezembro de 2025"
}

/** Strings for the current language, or the resolved default when unknown. */
fun stringsFor(languageKey: String?): AppStrings =
    when (AppLanguage.fromKey(languageKey).resolved()) {
        AppLanguage.PORTUGUESE_BR -> PortugueseStrings
        else -> EnglishStrings
    }

/** Strings for an already resolved language. */
fun stringsFor(language: AppLanguage): AppStrings = when (language.resolved()) {
    AppLanguage.PORTUGUESE_BR -> PortugueseStrings
    else -> EnglishStrings
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { EnglishStrings }
