package dev.kason

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


val deserializationJson = Json {
    ignoreUnknownKeys = true
}

class User(private val session: DefaultWebSocketServerSession) {
    var name: String? = null
    private val suspendChannel = Channel<Unit>(0)

    // mapping of a pred to the lambda executing it
    private val executionMapping =
        mutableListOf<Pair<suspend (IncomingData) -> Boolean, suspend (IncomingData) -> Unit>>()

    fun on(condition: suspend (IncomingData) -> Boolean, run: suspend (IncomingData) -> Unit) {
        executionMapping.add(condition to run)
    }

    inline fun <reified T : IncomingData> on(crossinline run: suspend (T) -> Unit) {
        on({ it is T }, { run(it as T) })
    }

    suspend fun initialize() {
        send(ServerVerification)
        setupBasics()
        setupSettings()
        setupGame()
        setupVoting()
        setupImposterGuess()
        session.outgoing.invokeOnClose {
            // we haven't done anything with it yet
            if (name == null) return@invokeOnClose
            if (curLobbyInformation != null) {
                val lobby = curLobbyInformation!!
                lobby.remove(this)
                app.launch {
                    lobby.sendUpdates()
                    lobby.sendAll(ServerMsgOutgoingData("player $name dc'ed"))
                }
            } else {
                val game = curGame!!
                app.launch {
                    when (this@User) {
                        in game.players -> game.players.remove(this@User)
                        in game.spectators -> game.spectators.remove(this@User)
                        in game.imposters -> {
                            game.imposters.remove(this@User)
                            game.checkImpostersAllGone()
                        }
                    }
                    game.sendAll(ServerMsgOutgoingData("player $name dc'ed"))
                }
            }
        }
        for (frame in session.incoming) {
            if (frame !is Frame.Text) {
                spyFallLogger.warn("Received non text frame data!")
                continue
            }
            val incomingData =
                kotlin.runCatching { deserializationJson.decodeFromString<IncomingData>(frame.readText()) }
                    .getOrNull()
            if (incomingData == null) {
                spyFallLogger.warn("Received invalid frame data (${frame.readText()}), skipping!")
                continue
            }

            for (item in executionMapping) {
                if (item.first(incomingData)) {
                    item.second(incomingData) // yes
                }
            }
        }
        suspendChannel.receive()
    }

    private fun setupBasics() {
        on<JoinIncomingData> {
            if (curLobbyInformation != null) {
                val lobby = curLobbyInformation!!
                if (lobby.keys.any { user -> user.name.equals(it.name, true) }) {
                    spyFallLogger.warn("User tried to set their name but already existed rip: name = ${it.name}, cur lobby = ${lobby.keys}")
                    send(NameValidity(false))
                    return@on
                }

                send(NewSettingsOutgoingData(settings))
                name = it.name
                spyFallLogger.info("Initialized user name! name = $name")
                send(NameValidity(true))

                lobby[this] = false
                lobby.sendUpdates()
            } else {
                // add to specs
                val game = curGame!!

                if ((game.players + game.spectators).any { user -> user.name.equals(it.name, true) }) {
                    spyFallLogger.warn("User tried to set their name but already existed rip: name = ${it.name}, in game," +
                            " users = ${(game.players + game.spectators).map { user -> user.name }}"
                    )
                    send(NameValidity(false))
                    return@on
                }

                game.spectators.add(this)
                game.introduceToSpectator(this)
            }
        }

        on<LobbyChangeIncomingData> {
            val lobby = curLobbyInformation ?: return@on
            spyFallLogger.debug("Updating the readiness of $name to ${it.newReadiness}")
            lobby[this] = it.newReadiness
            lobby.sendUpdates()
            if (it.newReadiness && lobby.isAllReady()) {
                val game = Game(lobby)
                if (game.init()) {
                    curGame = game
                    curLobbyInformation = null
                    // transition over to a game state
                } else {
                    lobby.sendAll(GameInvalidStartOutgoingData)
                    lobby.sendAll(ServerMsgOutgoingData("can't start the game: invalid settings / player count"))
                    lobby.keys.forEach { user ->
                        lobby[user] = false
                    }
                    lobby.sendUpdates()
                }
            }
        }
    }

    private fun setupSettings() {
        on<SettingsUpdIncomingData> {
            settings = it.settings
            curLobbyInformation?.sendAll(NewSettingsOutgoingData(settings))
        }
    }

    private fun setupGame() {
        on<SendMsgIncomingData> {
            val game = curGame ?: return@on
            val thread = game.threads.getOrNull(it.threadId)
                ?: return@on send(BadReqOutgoingData)
            thread.msgs.add(Thread.Msg(name!!, it.text))
            game.sendAll(NewMsgOutgoingData(it.threadId, it.text, name!!))
        }
        on<CreateThreadIncomingData> {
            val game = curGame ?: return@on
            val threadId = game.threads.size
            val newThread = Thread(threadId, game, name!!, it.question, it.user)
            game.threads.add(newThread)
            game.sendAll(NewThreadOutgoingData(threadId, name!!, it.question, it.user))
        }
    }

    private fun setupVoting() {
        on<VoteRequestIncomingData> {
            val game = curGame ?: return@on
            val votingSession = game.currentVotingSession ?: return@on
            votingSession[this] = game.players.first { it.name == this.name }
            votingSession.sendUpdates()
            if (votingSession.isEveryoneVoted()) {
                game.continueFromVotingSession()
            }
        }
        on<VoteSessionRequestIncomingData> {
            val game = curGame ?: return@on
            if (game.currentVotingSession != null) return@on
            val votingSession = game.VotingSession()
            game.currentVotingSession = votingSession
            game.sendAll(VoteSessionStartOutgoingData)
            votingSession.sendUpdates()
        }
    }
    
    private fun setupImposterGuess() {
        on<ImposterGuessIncomingData> {
            val game = curGame ?: return@on
            if (this !in game.imposters) return@on
            if (this !in game.players) return@on
            if (it.guess.equals(game.gameWord, true)) {
                game.gameEnd(true)
            }
        }
    }

    suspend fun send(outgoingData: OutgoingData) =
        sendStr(Json.encodeToString(outgoingData))

    suspend fun sendStr(outputString: String) {
        spyFallLogger.debug("Sending $outputString to user $name")
        session.send(Frame.Text(outputString))
    }

    override fun toString(): String = name ?: "no name yet"
}