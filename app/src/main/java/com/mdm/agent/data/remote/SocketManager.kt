package com.mdm.agent.data.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mdm.agent.util.DeviceUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.NetworkInterface
import java.net.URI

class SocketManager(private val context: Context) {

    companion object {
        private const val TAG = "SocketManager"
        private const val RECONNECT_DELAY = 5000L  // 5 seconds auto-reconnect
    }

    var onCommandReceived: ((JSONObject) -> Unit)? = null
    var onBanned: ((String?) -> Unit)? = null
    var onForceDisconnect: ((String?) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var socket: Socket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var commandHandler: CommandHandler? = null
    private var cryptoManager: CryptoManager? = null
    private var manuallyDisconnected = false

    val isConnected: Boolean get() = socket?.connected() == true

    fun setCommandHandler(handler: CommandHandler) {
        this.commandHandler = handler
    }

    fun setCryptoManager(cryptoManager: CryptoManager) {
        this.cryptoManager = cryptoManager
    }

    fun connect() {
        if (socket?.connected() == true) return
        manuallyDisconnected = false

        val serverUrl = DeviceUtils.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "Server URL not configured")
            return
        }

        try {
            val cleanUrl = serverUrl.replace(Regex("https?://"), "")
            val uri = URI("https://$cleanUrl")
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = RECONNECT_DELAY         // 5 seconds
                reconnectionDelayMax = RECONNECT_DELAY       // always 5 seconds
                timeout = 30000
                forceNew = true

                // Polling only - works through Render reverse proxy
                transports = arrayOf("polling")

                val info = DeviceUtils.getDeviceInfo(context)
                val deviceId = DeviceUtils.getDeviceId(context)
                auth = mapOf(
                    "device_id" to deviceId,
                    "model" to "${info["manufacturer"]} ${info["model"]}",
                    "version" to info["version"],
                    "ip" to getLocalIpAddress()
                )

                // Send device info via extraHeaders for immediate identification
                extraHeaders = mapOf(
                    "X-Device-ID" to listOf(deviceId),
                    "X-Device-Model" to listOf("${info["manufacturer"]} ${info["model"]}"),
                    "X-Device-Version" to listOf(info["version"] ?: ""),
                    "X-Device-IP" to listOf(getLocalIpAddress())
                )
            }

            socket = IO.socket(uri, options)
            setupListeners()
            socket?.connect()
            Log.i(TAG, "⚡ Connecting to $uri (polling, auto-reconnect 5s)")
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            scheduleReconnect()
        }
    }

    fun disconnect() {
        manuallyDisconnected = true
        stopHeartbeat()
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    private fun setupListeners() {
        val s = socket ?: return

        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "⚡ CONNECTED - Ready for instant push!")
            handler.post { onConnected?.invoke() }
            register()
        }

        s.on(Socket.EVENT_DISCONNECT) { args ->
            Log.i(TAG, "Disconnected: ${args.getOrElse(0) { "" }}")
            stopHeartbeat()
            handler.post { onDisconnected?.invoke() }
            if (!manuallyDisconnected) scheduleReconnect()
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Connect error: ${args.getOrElse(0) { "" }}")
            if (!manuallyDisconnected) scheduleReconnect()
        }

        s.on("reconnect") { _ ->
            Log.i(TAG, "⚡ Reconnected!")
        }

        s.on("banned") { args ->
            val data = args.getOrNull(0) as? JSONObject
            val reason = data?.optString("reason", "")
            Log.w(TAG, "BANNED: $reason")
            handler.post { onBanned?.invoke(reason) }
        }

        s.on("force_disconnect") { args ->
            val data = args.getOrNull(0) as? JSONObject
            val reason = data?.optString("reason", "")
            Log.w(TAG, "Force disconnect: $reason")
            handler.post { onForceDisconnect?.invoke(reason) }
        }

        s.on("registered") { args ->
            val data = args.getOrNull(0) as? JSONObject
            val status = data?.optString("status", "")
            Log.i(TAG, "✅ Registered: $status")

            val accessKey = data?.optString("access_key", "")
            if (!accessKey.isNullOrEmpty()) {
                DeviceUtils.saveAccessKey(context, accessKey)
                Log.i(TAG, "Access key saved")
            }

            val e2e = data?.optJSONObject("e2e")
            if (e2e != null) {
                try {
                    val cryptoResult = ApiClient.CryptoResult(
                        key = e2e.getString("key"),
                        iv = e2e.getString("iv"),
                        algorithm = e2e.optString("algorithm", "AES-256-CBC")
                    )
                    cryptoManager?.initFromServer(cryptoResult)
                    Log.i(TAG, "E2E crypto initialized")
                } catch (e: Exception) {
                    Log.w(TAG, "E2E init failed", e)
                }
            }

            startHeartbeat()
        }

        s.on("heartbeat_ack") { _ ->
            Log.d(TAG, "Heartbeat acknowledged")
        }

        // ⚡ INSTANT PUSH COMMAND - received immediately from server
        s.on("command") { args ->
            val data = args.getOrNull(0) as? JSONObject
            if (data != null) {
                Log.i(TAG, "⚡ INSTANT push command: ${data.optString("command")}")
                handler.post { onCommandReceived?.invoke(data) }
            }
        }

        s.on("error") { args ->
            Log.e(TAG, "Socket error: ${args.getOrElse(0) { "" }}")
        }
    }

    private fun register() {
        val deviceId = DeviceUtils.getDeviceId(context)
        val info = DeviceUtils.getDeviceInfo(context)
        val data = JSONObject().apply {
            put("device_id", deviceId)
            put("model", "${info["manufacturer"]} ${info["model"]}")
            put("version", info["version"])
            put("ip", getLocalIpAddress())
            put("extra_info", JSONObject(info))
        }
        socket?.emit("register", data)
        Log.i(TAG, "Registration sent for: $deviceId")
    }

    fun sendCommandResponse(data: JSONObject) {
        sendMessage("command_response", data)
    }

    fun sendMessage(event: String, data: JSONObject) {
        if (socket?.connected() == true) {
            socket?.emit(event, data)
            Log.d(TAG, "⚡ Sent [$event]: ${data.optString("cmd", "")}")
        } else {
            Log.w(TAG, "Cannot send [$event] - not connected, will retry on reconnect")
            sendResponseViaRest(data)
        }
    }

    fun sendFileExplorerData(data: JSONObject) {
        if (socket?.connected() == true) {
            socket?.emit("file_explorer_data", data)
        }
    }

    // Fallback: send response via REST if Socket.IO is disconnected
    private fun sendResponseViaRest(data: JSONObject) {
        try {
            val serverUrl = DeviceUtils.getServerUrl(context)
            val url = "$serverUrl/api/device/response"
            val body = data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = okhttp3.Request.Builder().url(url).post(body).build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "REST fallback response OK")
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST fallback response error: ${e.message}")
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (socket?.connected() == true) {
                    socket?.emit("heartbeat", JSONObject())
                }
                handler.postDelayed(this, 30000)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, 30000)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        handler.postDelayed({
            if (!manuallyDisconnected && socket?.connected() != true) {
                Log.i(TAG, "⚡ Auto-reconnecting in 5 seconds...")
                try {
                    socket?.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect error: ${e.message}")
                    scheduleReconnect()
                }
            }
        }, RECONNECT_DELAY)
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (e: Exception) { "" }
    }
}
