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

package com.openappslabs.docs.data.source

import androidx.room.Database
import androidx.room.RoomDatabase
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.model.FolderDescription
import com.openappslabs.docs.data.model.FolderTag
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.model.TagAlias

/**
 * Only the index and the tags live here.
 *
 * The documents themselves stay in the user's folder: losing this database
 * costs a rescan plus the tags, never a file. Tags are only here (by choice):
 * a rebuild from disk cannot invent them back, so the version bump that added
 * them carries a real migration.
 */
@Database(
    entities = [
        Doc::class,
        Tag::class,
        DocTag::class,
        DocText::class,
        FolderDescription::class,
        FolderTag::class,
        TagAlias::class
    ],
    version = 8,
    exportSchema = false
)
abstract class DocsDatabase : RoomDatabase() {
    abstract fun docDao(): DocDao
    abstract fun tagDao(): TagDao
    abstract fun docTextDao(): DocTextDao
    abstract fun folderDescriptionDao(): FolderDescriptionDao
    abstract fun folderTagDao(): FolderTagDao
    abstract fun tagAliasDao(): TagAliasDao
}
