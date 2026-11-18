/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Sync
 *
 * Atena Sync is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Sync is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Sync.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.sync

import java.sql.Connection
import java.sql.ResultSet

internal fun Connection.execute(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result -> if (result.next()) map(result) else null }
    }

internal fun <T> Connection.queryList(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(map(result))
            }
        }
    }

internal fun Boolean.toInt(): Int = if (this) 1 else 0
