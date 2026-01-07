package com.roman.zemzeme.p2p

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Message received on a P2P topic (GossipSub)
 */
data class TopicMessage(
    val topicName: String,
    val senderID: String,
    val content: String,
    val timestamp: Long,
    val isOutgoing: Boolean = false
)

/**
 * Topic peer update event
 */
data class TopicPeerUpdate(
    val topicName: String,
    val peerID: String,
    val action: String // "join", "leave", or "discovered"
)

/**
 * Topic connection state for UI feedback
 */
enum class TopicConnectionState {
    CONNECTING,    // Searching for peers
    CONNECTED,     // Has at least one peer
    NO_PEERS,      // Discovery complete, no peers found
    ERROR          // Connection error
}

/**
 * Current state of a topic subscription
 */
data class TopicState(
    val connectionState: TopicConnectionState = TopicConnectionState.CONNECTING,
    val meshPeerCount: Int = 0,        // Peers in PubSub mesh
    val providerCount: Int = 0,        // DHT providers
    val peers: List<String> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Subscribed topic info
 */
data class TopicInfo(
    val name: String,
    val subscribedAt: Long = System.currentTimeMillis()
)

/**
 * P2P Topics Repository
 * 
 * Manages P2P topic (GossipSub) subscriptions for:
 * - Geohash channels (location-based chat)
 * - Custom topic rooms
 * 
 * Delegates all P2P operations to P2PLibraryRepository.
 * Adapted from mobile_go_libp2p reference implementation.
 */
class P2PTopicsRepository(
    private val context: Context,
    private val p2pLibraryRepository: P2PLibraryRepository
) {
    companion object {
        private const val TAG = "P2PTopicsRepository"
        private const val PREFS_NAME = "p2p_topics"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // State flows
    private val _subscribedTopics = MutableStateFlow<List<TopicInfo>>(emptyList())
    val subscribedTopics: StateFlow<List<TopicInfo>> = _subscribedTopics.asStateFlow()
    
    private val _topicPeers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val topicPeers: StateFlow<Map<String, List<String>>> = _topicPeers.asStateFlow()
    
    private val _topicMessages = MutableStateFlow<Map<String, List<TopicMessage>>>(emptyMap())
    val topicMessages: StateFlow<Map<String, List<TopicMessage>>> = _topicMessages.asStateFlow()
    
    private val _topicStates = MutableStateFlow<Map<String, TopicState>>(emptyMap())
    val topicStates: StateFlow<Map<String, TopicState>> = _topicStates.asStateFlow()
    
    // Event flows
    private val _incomingMessages = MutableSharedFlow<TopicMessage>(replay = 0, extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<TopicMessage> = _incomingMessages.asSharedFlow()
    
    private val _peerUpdates = MutableSharedFlow<TopicPeerUpdate>(replay = 0, extraBufferCapacity = 50)
    val peerUpdates: SharedFlow<TopicPeerUpdate> = _peerUpdates.asSharedFlow()
    
    init {
        loadSavedTopics()
        
        // Listen to incoming messages from P2PLibraryRepository
        scope.launch {
            p2pLibraryRepository.incomingMessages.collect { p2pMessage ->
                if (p2pMessage.isTopicMessage && p2pMessage.topicName != null) {
                    val topicMsg = TopicMessage(
                        topicName = p2pMessage.topicName,
                        senderID = p2pMessage.senderPeerID,
                        content = p2pMessage.content,
                        timestamp = p2pMessage.timestamp,
                        isOutgoing = false
                    )
                    addMessageToTopic(p2pMessage.topicName, topicMsg)
                    _incomingMessages.emit(topicMsg)
                }
            }
        }
    }
    
    /**
     * Initialize and re-subscribe to saved topics when P2P node starts.
     * Call this after P2PLibraryRepository.startNode() succeeds.
     */
    suspend fun onNodeStarted() {
        _subscribedTopics.value.forEach { topic ->
            try {
                initTopicState(topic.name)
                p2pLibraryRepository.subscribeTopic(topic.name)
                startTopicDiscovery(topic.name)
                Log.d(TAG, "Re-subscribed to saved topic: ${topic.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-subscribe to ${topic.name}: ${e.message}")
                setTopicError(topic.name, e.message ?: "Failed to subscribe")
            }
        }
    }
    
    /**
     * Clean up when P2P node stops.
     */
    fun onNodeStopped() {
        // Reset all topic states to connecting for next startup
        val states = _topicStates.value.toMutableMap()
        states.keys.forEach { key ->
            states[key] = TopicState(connectionState = TopicConnectionState.CONNECTING)
        }
        _topicStates.value = states
    }
    
    // ============== Topic Operations ==============
    
    /**
     * Subscribe to a topic (e.g., geohash channel).
     */
    suspend fun subscribeTopic(topicName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (p2pLibraryRepository.nodeStatus.value != P2PNodeStatus.RUNNING) {
                return@withContext Result.failure(Exception("P2P node not running"))
            }
            
            // Initialize state
            initTopicState(topicName)
            
            // Subscribe via P2PLibraryRepository
            val result = p2pLibraryRepository.subscribeTopic(topicName)
            if (result.isFailure) {
                setTopicError(topicName, result.exceptionOrNull()?.message ?: "Subscription failed")
                return@withContext result
            }
            
            // Add to subscribed list
            val current = _subscribedTopics.value.toMutableList()
            if (current.none { it.name == topicName }) {
                current.add(TopicInfo(topicName))
                _subscribedTopics.value = current
                saveTopics()
            }
            
            // Initialize message/peer tracking
            initTopicData(topicName)
            
            // Start peer discovery
            startTopicDiscovery(topicName)
            
            Log.d(TAG, "Subscribed to topic: $topicName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to $topicName: ${e.message}")
            setTopicError(topicName, e.message ?: "Subscription failed")
            Result.failure(e)
        }
    }
    
    /**
     * Unsubscribe from a topic.
     */
    suspend fun unsubscribeTopic(topicName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            p2pLibraryRepository.unsubscribeTopic(topicName)
            
            // Remove from list
            val current = _subscribedTopics.value.toMutableList()
            current.removeAll { it.name == topicName }
            _subscribedTopics.value = current
            saveTopics()
            
            // Cleanup data
            cleanupTopicData(topicName)
            
            Log.d(TAG, "Unsubscribed from topic: $topicName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unsubscribe from $topicName: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Publish a message to a topic.
     */
    suspend fun publishToTopic(
        topicName: String,
        content: String,
        senderNickname: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (p2pLibraryRepository.nodeStatus.value != P2PNodeStatus.RUNNING) {
                return@withContext Result.failure(Exception("P2P node not running"))
            }
            
            val result = p2pLibraryRepository.publishToTopic(topicName, content)
            if (result.isFailure) {
                return@withContext result
            }
            
            // Add our message to local list
            val myPeerID = p2pLibraryRepository.peerID.value ?: "unknown"
            val msg = TopicMessage(
                topicName = topicName,
                senderID = myPeerID,
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
            addMessageToTopic(topicName, msg)
            
            Log.d(TAG, "Published to $topicName: ${content.take(50)}...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to $topicName: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Get current peers for a topic.
     */
    fun getPeersForTopic(topicName: String): List<String> {
        return _topicPeers.value[topicName] ?: emptyList()
    }
    
    /**
     * Get current state for a topic.
     */
    fun getTopicState(topicName: String): TopicState {
        return _topicStates.value[topicName] ?: TopicState()
    }
    
    /**
     * Get messages for a topic.
     */
    fun getMessagesForTopic(topicName: String): List<TopicMessage> {
        return _topicMessages.value[topicName] ?: emptyList()
    }
    
    /**
     * Check if subscribed to a topic.
     */
    fun isSubscribed(topicName: String): Boolean {
        return _subscribedTopics.value.any { it.name == topicName }
    }
    
    // ============== Internal ==============
    
    private fun initTopicState(topicName: String) {
        val current = _topicStates.value.toMutableMap()
        current[topicName] = TopicState(connectionState = TopicConnectionState.CONNECTING)
        _topicStates.value = current
    }
    
    private fun initTopicData(topicName: String) {
        val msgs = _topicMessages.value.toMutableMap()
        if (!msgs.containsKey(topicName)) {
            msgs[topicName] = emptyList()
            _topicMessages.value = msgs
        }
        
        val peers = _topicPeers.value.toMutableMap()
        if (!peers.containsKey(topicName)) {
            peers[topicName] = emptyList()
            _topicPeers.value = peers
        }
    }
    
    private fun cleanupTopicData(topicName: String) {
        val msgs = _topicMessages.value.toMutableMap()
        msgs.remove(topicName)
        _topicMessages.value = msgs
        
        val peers = _topicPeers.value.toMutableMap()
        peers.remove(topicName)
        _topicPeers.value = peers
        
        val states = _topicStates.value.toMutableMap()
        states.remove(topicName)
        _topicStates.value = states
    }
    
    private fun setTopicError(topicName: String, error: String) {
        val current = _topicStates.value.toMutableMap()
        val state = current[topicName] ?: TopicState()
        current[topicName] = state.copy(
            connectionState = TopicConnectionState.ERROR,
            error = error,
            lastUpdated = System.currentTimeMillis()
        )
        _topicStates.value = current
    }
    
    private fun updateTopicState(topicName: String, peers: List<String>) {
        val current = _topicStates.value.toMutableMap()
        val state = current[topicName] ?: TopicState()
        
        val newState = when {
            peers.isNotEmpty() -> TopicConnectionState.CONNECTED
            state.connectionState == TopicConnectionState.CONNECTING -> TopicConnectionState.CONNECTING
            else -> TopicConnectionState.NO_PEERS
        }
        
        current[topicName] = state.copy(
            connectionState = newState,
            meshPeerCount = peers.size,
            peers = peers,
            error = null,
            lastUpdated = System.currentTimeMillis()
        )
        _topicStates.value = current
    }
    
    private fun addMessageToTopic(topicName: String, message: TopicMessage) {
        val current = _topicMessages.value.toMutableMap()
        val msgs = current[topicName]?.toMutableList() ?: mutableListOf()
        msgs.add(message)
        current[topicName] = msgs
        _topicMessages.value = current
    }
    
    private fun startTopicDiscovery(topicName: String) {
        scope.launch {
            try {
                // Periodically check peers
                repeat(6) { // 30 seconds total
                    delay(5000)
                    refreshTopicPeers(topicName)
                }
                
                // Mark discovery complete
                val current = _topicStates.value.toMutableMap()
                val state = current[topicName]
                if (state?.connectionState == TopicConnectionState.CONNECTING) {
                    current[topicName] = state.copy(
                        connectionState = if (state.meshPeerCount > 0) 
                            TopicConnectionState.CONNECTED 
                        else 
                            TopicConnectionState.NO_PEERS
                    )
                    _topicStates.value = current
                }
            } catch (e: Exception) {
                Log.w(TAG, "Topic discovery failed for $topicName: ${e.message}")
            }
        }
    }
    
    private suspend fun refreshTopicPeers(topicName: String) {
        try {
            val peers = p2pLibraryRepository.getTopicPeers(topicName)
            
            val current = _topicPeers.value.toMutableMap()
            current[topicName] = peers
            _topicPeers.value = current
            
            updateTopicState(topicName, peers)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh peers for $topicName: ${e.message}")
        }
    }
    
    // ============== Persistence ==============
    
    private fun loadSavedTopics() {
        val json = prefs.getString("topics", "[]") ?: "[]"
        try {
            val topicNames = json.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
            
            _subscribedTopics.value = topicNames.map { TopicInfo(it) }
            
            // Initialize states
            val initialStates = mutableMapOf<String, TopicState>()
            topicNames.forEach { name ->
                initialStates[name] = TopicState(connectionState = TopicConnectionState.CONNECTING)
            }
            _topicStates.value = initialStates
            
            Log.d(TAG, "Loaded ${topicNames.size} saved topics")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved topics: ${e.message}")
            _subscribedTopics.value = emptyList()
        }
    }
    
    private fun saveTopics() {
        val topicNames = _subscribedTopics.value.map { "\"${it.name}\"" }
        prefs.edit().putString("topics", "[${topicNames.joinToString(",")}]").apply()
    }
}
