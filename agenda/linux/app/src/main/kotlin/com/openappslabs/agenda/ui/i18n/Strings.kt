/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.ui.i18n

/**
 * Text shown to the user. Kept in one place so a translation never has to hunt
 * for string literals inside the UI. Today: pt-BR only.
 */
object Strings {
    const val appName = "Agenda"
    const val today = "Hoje"
    const val previousPeriod = "Período anterior"
    const val nextPeriod = "Próximo período"

    const val days = "Dias"
    const val months = "Meses"
    const val years = "Anos"

    const val newEvent = "Novo evento"
    const val noEvents = "Nada neste dia"
    const val noEventsHint = "Use “Novo evento” para agendar."
    const val allDay = "Dia inteiro"

    const val title = "Título"
    const val titleHint = "Almoço com a equipe"
    const val date = "Data"
    const val dateHint = "dd/mm/aaaa"
    const val start = "Início"
    const val end = "Fim"
    const val timeHint = "hh:mm"
    const val location = "Local"
    const val locationHint = "Opcional"
    const val description = "Descrição"
    const val descriptionHint = "Opcional"

    const val save = "Salvar"
    const val cancel = "Cancelar"
    const val delete = "Excluir"
    const val close = "Fechar"

    const val editEvent = "Editar evento"

    const val sync = "Sincronizar"
    const val syncing = "Sincronizando…"
    const val settings = "Ajustes"
    const val settingsTitle = "Ajustes"
    const val windowSection = "Janela"
    const val closeOnClickOutside = "Fechar ao clicar fora"
    const val closeOnClickOutsideHint = "Desligado, a janela só fecha com Esc ou no X."
    const val syncTitle = "Sincronização"
    const val syncHint = "Opcional. Os eventos ficam neste computador; o hub só os " +
        "compartilha com os outros aparelhos."
    const val serverUrl = "URL do servidor"
    const val serverUrlHint = "http://192.168.0.10:8080"
    const val token = "Token"
    const val tokenHint = "Impresso no log do hub"
    const val deviceName = "Nome deste computador"
    const val connect = "Conectar"
    const val disconnect = "Desconectar"
    const val testConnection = "Testar conexão"
    const val testing = "Testando…"
    const val testOk = "Conexão OK"
    const val neverSynced = "Nunca sincronizou"
    const val notConfigured = "Sem sincronização"
    const val offline = "Sem conexão com o hub"
    const val syncError = "Falha na sincronização"
    const val unauthorized = "Token recusado pelo hub"

    const val errorTitle = "Dê um título ao evento."
    const val errorDate = "Data inválida. Use dd/mm/aaaa."
    const val errorTime = "Horário inválido. Use hh:mm."
    const val errorEndBeforeStart = "O fim precisa ser depois do início."
    const val errorServerUrl = "Informe a URL do servidor."
    const val errorToken = "Informe o token."

    /** "13 de novembro" — used as the title of the selected day. */
    const val eventCountOne = "1 evento"
    const val eventCountMany = "%d eventos"

    fun eventCount(count: Int): String =
        if (count == 1) eventCountOne else eventCountMany.format(count)
}
