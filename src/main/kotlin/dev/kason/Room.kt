package dev.kason

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    val numImposter: Int,
    val voteInterval: Int
)

var settings = Settings(1, 300)

@Suppress("MemberVisibilityCanBePrivate")
class Game(lobbyInformation: LobbyInformation) {
    lateinit var voteJob: Job
    val settingsUsed: Settings = settings
    val players: MutableList<User> = ArrayList(lobbyInformation.keys)
    val spectators: MutableList<User> = mutableListOf()
    val threads: MutableList<Thread> = mutableListOf()

    val imposters: MutableSet<User> = mutableSetOf()
    val initialPlayers = players.filter { it !in imposters }.map { it.name!! }

    val gameEntry = topicAndWordList.random()
    val gameTopic: String = gameEntry.first
    val gameWord: String = gameEntry.second

    var currentVotingSession: VotingSession? = null
    var lastCallTimestamp = System.currentTimeMillis()

    inner class VotingSession : MutableMap<User, User> by mutableMapOf() {
        suspend fun sendUpdates() {
            val map = mutableMapOf<String, String>()
            for (entry in this) {
                map[entry.key.name!!] = entry.value.name!!
            }
            this@Game.sendAll(VoteInfoOutgoingData(map))
        }

        fun isEveryoneVoted(): Boolean =
            players.size == size
    }

    suspend fun init(): Boolean {
        // do checks early
        if (players.size <= settingsUsed.numImposter) return false
        if (players.size < 3) return false // too few players to play well :(
        if (imposters.isNotEmpty()) return false

        sendAll(GameStartingOutgoingData(gameTopic))
        repeat(settingsUsed.numImposter) {
            var curImposter: User
            do {
                curImposter = players.random()
            } while (curImposter in imposters)
            imposters.add(curImposter)
        }

        val imposterRole = Json.encodeToString(GameInitOutgoingData(null))
        val regularRole = Json.encodeToString(GameInitOutgoingData(gameWord))
        for (player in players) {
            if (player in imposters) {
                player.sendStr(imposterRole)
            } else {
                player.sendStr(regularRole)
            }
        }

        val imposterNames = GameImpostersOutgoingData(imposters.map { it.name!! })
        val jsonStr = Json.encodeToString(imposterNames)
        imposters.forEach {
            it.sendStr(jsonStr)
        }
        
        voteJob = app.launch { 
            delay((settingsUsed.voteInterval * 1_000).toLong())
            startVotingSession()
        }

        return true
    }

    suspend fun sendAll(outgoingData: OutgoingData) {
        val outgoingStr = Json.encodeToString(outgoingData)
        for (player in players) {
            player.sendStr(outgoingStr)
        }
        for (spectator in spectators) {
            spectator.sendStr(outgoingStr)
        }
    }

    private fun createGameStateUpd(showWord: Boolean = false, showImposters: Boolean = false): GameStateOutgoingData =
        GameStateOutgoingData(
            gameTopic,
            gameWord.takeIf { showWord },
            players.map { it.name!! },
            if (showImposters) imposters.map { it.name!! } else null
        )
    
    suspend fun startVotingSession() {
        val votingSession = VotingSession()
        currentVotingSession = votingSession
        sendAll(VoteSessionStartOutgoingData)
        votingSession.sendUpdates()
        if (voteJob.isActive)
            voteJob.cancel()
    }

    suspend fun continueFromVotingSession() {
        val votingSession = this.currentVotingSession!!
        val hist = mutableMapOf<User, Int>()
        for (entry in votingSession) {
            hist.merge(entry.key, 1, Int::plus)
        }
        var bestUser: User? = null
        var count = -1
        var eq = false
        for ((key, value) in hist) {
            if (value > count) {
                bestUser = key
                count = value
                eq = false
            } else if (value == count && !eq) {
                eq = true
            }
        }
        if (eq) {
            this@Game.sendAll(VoteResOutgoingData(null))
        } else {
            players.remove(bestUser!!)
            spectators.add(bestUser) // they get moved to the spectators
            this@Game.sendAll(VoteResOutgoingData(bestUser.name))

            if (checkImpostersAllGone())
                return

            updateStateAll()
            voteJob = app.launch {
                delay((settingsUsed.voteInterval * 1_000).toLong())
                startVotingSession()
            }
            lastCallTimestamp = System.currentTimeMillis()
        }
    }

    suspend fun checkImpostersAllGone(): Boolean {
        if (players.all { it !in imposters }) {
            gameEnd(false)
            return true
        }
        return false
    }

    suspend fun updateStateAll() {
        val impostersOutgoingData = Json.encodeToString(createGameStateUpd(showImposters = true))
        val playerOutgoingData = Json.encodeToString(createGameStateUpd(showWord = true))
        val spectatorOutgoingData = Json.encodeToString(createGameStateUpd(showWord = true, showImposters = true))
        for (player in players) {
            if (player in imposters) {
                player.sendStr(impostersOutgoingData)
            } else {
                player.sendStr(playerOutgoingData)
            }
        }
        spectators.forEach {
            it.sendStr(spectatorOutgoingData)
        }
    }

    suspend fun introduceToSpectator(user: User) {
        user.send(createGameStateUpd(showWord = true, showImposters = true))

        val threadInfo = threads.map { it.toRep() }
        val setThread = SetThreadOutgoingData(threadInfo)
        user.send(setThread)

        if (currentVotingSession != null) {
            currentVotingSession!!.sendUpdates()
        }
    }

    suspend fun gameEnd(imposterWin: Boolean) {
        sendAll(GameEndOutgoingData(
            imposterWin,
            gameTopic,
            gameWord,
            initialPlayers,
            imposters.map { it.name!! }
        ))

        // transfer everyone to the lobby
        val newLobby = LobbyInformation()
        for (user in (players + spectators)) {
            newLobby[user] = false
        }
        curLobbyInformation = newLobby
        curGame = null
        newLobby.sendUpdates()
    }
}


class Thread(val id: Int, val game: Game, val asker: String, val question: String, val receiver: String) {
    val msgs = mutableListOf<Msg>()

    @Serializable
    data class Msg(val userName: String, val text: String)

    @Serializable
    data class Rep(val id: Int, val asker: String, val question: String, val receiver: String, val msgs: List<Msg>)

    fun toRep() = Rep(id, asker, question, receiver, msgs)
}

var curGame: Game? = null
var curLobbyInformation: LobbyInformation? = LobbyInformation()

val encodeDefaultsJson = Json {
    encodeDefaults = true
}

class LobbyInformation : MutableMap<User, Boolean> by mutableMapOf() {
    suspend fun sendUpdates() {
        val lobbyData = LobbyOutgoingData(this.mapKeys { it.key.name!! })
        sendAll(lobbyData)
    }

    suspend fun sendAll(data: OutgoingData) {
        val json = encodeDefaultsJson.encodeToString(data)
        keys.forEach {
            it.sendStr(json)
        }
    }

    fun isAllReady() = all { it.value }
}