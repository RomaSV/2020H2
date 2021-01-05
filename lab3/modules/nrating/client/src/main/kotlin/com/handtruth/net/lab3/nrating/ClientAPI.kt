package com.handtruth.net.lab3.nrating

import com.handtruth.kommon.Log
import com.handtruth.kommon.default
import com.handtruth.net.lab3.message.readMessage
import com.handtruth.net.lab3.message.writeMessage
import com.handtruth.net.lab3.nrating.messages.DisconnectMessage
import com.handtruth.net.lab3.nrating.messages.QueryMessage
import com.handtruth.net.lab3.nrating.messages.QueryResponseMessage
import com.handtruth.net.lab3.nrating.options.*
import com.handtruth.net.lab3.nrating.types.QueryMethod
import com.handtruth.net.lab3.nrating.types.QueryStatus
import com.handtruth.net.lab3.nrating.types.Topic
import com.handtruth.net.lab3.nrating.types.TopicStatus
import com.handtruth.net.lab3.options.toOptions
import com.handtruth.net.lab3.util.validate
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RequestFailedException(message: String) : RuntimeException(message)
class DisconnectException(message: String) : RuntimeException(message)

class ClientAPI(
    private val readChannel: ByteReadChannel,
    private val writeChannel: ByteWriteChannel
): Closeable {
    private val log = Log.default("nrating/client/api")

    private val mutex = Mutex()

    private suspend fun request(query: QueryMessage): QueryResponseMessage = mutex.withLock {
        log.info { "request:  $query" }
        writeChannel.writeMessage(query)
        writeChannel.flush()
        val result = readChannel.readMessage()
        log.info { "response: $result" }
        if (result is DisconnectMessage) {
            writeChannel.close()
            readChannel.cancel()
            val error = result.getOption<ErrorMessageOption>().name
            log.error { "disconnected with error: $error" }
            throw DisconnectException(error)
        }
        result as QueryResponseMessage
        validate(query.method == result.method) {
            "query and response methods differ (query: ${query.method}, response: ${result.method})"
        }
        when (result.status) {
            QueryStatus.OK -> result
            QueryStatus.FAILED -> {
                val error = result.getOption<ErrorMessageOption>().name
                log.error { "query failed with error: $error" }
                throw RequestFailedException(error)
            }
        }
    }

    suspend fun getTopicList(): List<Topic> {
        val response = request(QueryMessage(QueryMethod.GET, 0, 0))
        return response.getOption<TopicListOption>().topics
    }

    private fun checkId(expected: Int, actual: Int) {
        validate(expected == actual) { "wrong topic id (expected #$expected, got #$actual" }
    }

    suspend fun getTopic(id: Int): TopicStatus {
        require(id != 0)
        val response = request(QueryMessage(QueryMethod.GET, id, 0))
        checkId(id, response.topic)
        return response.getOption<TopicStatusOption>().topicStatus
    }

    suspend fun addTopic(name: String, alternatives: Collection<String>): Int {
        val addTopic = QueryMessage(QueryMethod.ADD, 0, alternatives.size, toOptions(TopicNameOption(name)))
        val topicAdded = request(addTopic)
        val id = topicAdded.topic
        for (alternative in alternatives) {
            val addAlt = QueryMessage(QueryMethod.ADD, id, 0, toOptions(AlternativeNameOption(alternative)))
            val altAdded = request(addAlt)
            checkId(id, altAdded.topic)
        }
        return id
    }

    private suspend fun action(id: Int, method: QueryMethod, alternative: Int = 0) {
        require(id != 0)
        val response = request(QueryMessage(method, id, alternative))
        checkId(id, response.topic)
    }

    suspend fun openVote(id: Int) = action(id, QueryMethod.OPEN)

    suspend fun closeVote(id: Int) = action(id, QueryMethod.CLOSE)

    suspend fun vote(id: Int, alternative: Int) = action(id, QueryMethod.VOTE, alternative)

    suspend fun removeTopic(id: Int) = action(id, QueryMethod.DEL)

    override fun close() {
        readChannel.cancel()
        writeChannel.close()
    }
}
