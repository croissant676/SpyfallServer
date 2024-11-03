package dev.kason

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface OutgoingData

@Serializable
sealed interface IncomingData

@Serializable
@SerialName("join_rq")
data class JoinIncomingData(val name: String) : IncomingData

@Serializable
@SerialName("server_msg")
data class ServerMsgOutgoingData(val data: String) : OutgoingData

@Serializable
@SerialName("server_verification")
data object ServerVerification : OutgoingData

@Serializable
@SerialName("name_validity")
data class NameValidity(val isValid: Boolean) : OutgoingData

@Serializable
@SerialName("lobby_data")
data class LobbyOutgoingData(val readiness: Map<String, Boolean>) : OutgoingData

@Serializable
@SerialName("thread_create")
data class CreateThreadIncomingData(val question: String, val user: String) : IncomingData

@Serializable
@SerialName("thread_send")
data class SendMsgIncomingData(val threadId: Int, val text: String) : IncomingData

@Serializable
@SerialName("new_msg")
data class NewMsgOutgoingData(val threadId: Int, val text: String, val user: String) : OutgoingData

@Serializable
@SerialName("new_thread")
data class NewThreadOutgoingData(
    val threadId: Int, val asker: String,
    val question: String, val receiver: String
) : OutgoingData

@Serializable
@SerialName("thread_set")
data class SetThreadOutgoingData(
    val threadInfo: List<Thread.Rep>
) : OutgoingData

@Serializable
@SerialName("upd_settings")
data class SettingsUpdIncomingData(val settings: Settings) : IncomingData

@Serializable
@SerialName("new_settings")
data class NewSettingsOutgoingData(val settings: Settings) : OutgoingData

@Serializable
@SerialName("game_state")
data class GameStateOutgoingData(
    val gameTopic: String, 
    val gameWord: String?,
    val players: List<String>, 
    val imposters: List<String>?
) : OutgoingData

@Serializable
@SerialName("lobby_readiness_data")
data class LobbyChangeIncomingData(val newReadiness: Boolean) : IncomingData

@Serializable
@SerialName("game_start")
data class GameStartingOutgoingData(val gameTopic: String) : OutgoingData

@Serializable
@SerialName("game_not_valid")
data object GameInvalidStartOutgoingData : OutgoingData

@Serializable
@SerialName("game_role")
data class GameInitOutgoingData(val text: String?) : OutgoingData


@Serializable
@SerialName("game_imposters")
data class GameImpostersOutgoingData(val imposters: List<String>) : OutgoingData

@Serializable
@SerialName("bad_request")
data object BadReqOutgoingData : OutgoingData

@Serializable
@SerialName("vote_session_req")
data object VoteSessionRequestIncomingData: IncomingData

@Serializable
@SerialName("vote_session_req")
data object VoteSessionStartOutgoingData: OutgoingData

@Serializable
@SerialName("vote")
data class VoteRequestIncomingData(val voteFor: String): IncomingData

@Serializable
@SerialName("vote_info")
data class VoteInfoOutgoingData(val votingRecords: Map<String, String>) : OutgoingData

@Serializable
@SerialName("vote_res")
data class VoteResOutgoingData(val votedOut: String?) : OutgoingData


@Serializable
@SerialName("game_end")
data class GameEndOutgoingData(
    val imposterWin: Boolean,
    val gameTopic: String,
    val gameWord: String,
    val players: List<String>,
    val imposters: List<String>
) : OutgoingData


@Serializable
@SerialName("imposter_guess")
data class ImposterGuessIncomingData(
    val guess: String
) : IncomingData


@Serializable
@SerialName("imposter_res")
data class ImposterGuessResultOutgoingData(
    val correct: Boolean
) : IncomingData