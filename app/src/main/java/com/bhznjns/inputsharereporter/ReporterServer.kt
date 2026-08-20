package com.bhznjns.inputsharereporter

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.bhznjns.inputsharereporter.utils.I18n // Assuming I18n is available
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

const val SERVER_EVENT_KEEPALIVE = 0x00
const val SERVER_EVENT_TOGGLE    = 0x01

const val ABSTRACT_SOCKET_NAME = "inputsharereporter"

const val KEEPALIVE_INTERVAL_MS = 4000L
const val RETRY_INTERVAL_MS = 1000L

class ReporterServer : Service() {
    private val isRunning = AtomicBoolean(false)

    // accept 专用线程：阻塞在 LocalServerSocket.accept()，不与事件发送互相抢占
    private val serverExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReporterServer-accept")
    }
    // 发送专用线程：toggle/keepalive 串行写入 socket，保证事件即时、有序发出
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReporterServer-sender")
    }
    private val uiHandler = Handler(Looper.getMainLooper())

    // 心跳定时器：主线程 postDelayed 定时投递，替代 sleep 长期占用线程
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            sendExecutor.execute {
                // 心跳写失败说明连接已断开，触发重连
                if (!sendBytes(byteArrayOf(SERVER_EVENT_KEEPALIVE.toByte()))) {
                    stopServer(true)
                }
            }
            heartbeatHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var outputStream: OutputStream? = null

    private fun startServer() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.i("ReporterServer", "Server is already running.")
            return
        }

        serverExecutor.execute {
            try {
                // Create LocalServerSocket bound to the abstract namespace
                serverSocket = LocalServerSocket(ABSTRACT_SOCKET_NAME)
                Log.i("ReporterServer", "Server started on abstract address: localabstract:$ABSTRACT_SOCKET_NAME")

                clientSocket = serverSocket?.accept()
                Log.i("ReporterServer", "Client connected")
                outputStream = clientSocket?.outputStream

                // Post UI update to main thread
                uiHandler.post { Toast.makeText(this, I18n.choose(listOf(
                    "PC client connected.",
                    "电脑端已连接。",
                )), Toast.LENGTH_SHORT).show() }

                startHeartbeat()
            } catch (e: IOException) {
                // Catch IOException specifically for socket operations
                Log.e("ReporterServer", "Server starting or connection error: ${e.message}")
                // Only retry if the service is still intended to be running
                if (isRunning.get()) {
                    stopServer(true) // Retry connection
                }
            } catch (e: Exception) {
                // Catch other potential exceptions
                Log.e("ReporterServer", "Unexpected server error: ${e.message}")
                if (isRunning.get()) {
                    stopServer(true) // Retry connection
                }
            }
        }
    }

    private fun sendEvent(event: Int): Boolean {
        val data = byteArrayOf(event.toByte())
        return sendBytes(data)
    }

    private fun sendBytes(data: ByteArray): Boolean {
        // 先取局部引用，避免跨线程读到半更新的字段
        val stream = outputStream
        val socket = clientSocket
        if (stream == null || socket == null || !socket.isConnected) {
            Log.e("ReporterServer", "Client is not connected or socket is closed.")
            return false
        }

        try {
            stream.write(data)
            stream.flush()
        } catch (e: IOException) {
            // Catch IOException for write/flush errors, indicating connection issue
            Log.e("ReporterServer", "Server sending data error: ${e.message}")
            return false
        } catch (e: Exception) {
            // Catch other potential exceptions
            Log.e("ReporterServer", "Unexpected sending data error: ${e.message}")
            return false
        }
        return true
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun stopServer(retry: Boolean) {
        // 停止心跳投递，避免重连后多个心跳任务叠加
        heartbeatHandler.removeCallbacks(heartbeatRunnable)

        // Use compareAndSet to ensure only one thread stops the server
        if (!isRunning.compareAndSet(true, false)) {
            Log.w("ReporterServer", "Server is already stopping or stopped.")
            return
        }

        Log.i("ReporterServer", "Attempting to stop server...")
        // 关闭 socket：串行到发送线程，避免与正在进行的写操作并发
        sendExecutor.execute {
            closeSocketResources()
            if (!retry) {
                stopExecutors()
            }
        }

        if (!retry) {
            Log.i("ReporterServer", "Server fully stopped (no retry).")
            return
        }

        uiHandler.post { Toast.makeText(this, I18n.choose(listOf(
            "PC client disconnected.",
            "电脑端已断开连接。",
        )), Toast.LENGTH_SHORT).show() }
        uiHandler.postDelayed({
            Log.i("ReporterServer", "Attempting to restart server...")
            startServer()
        }, RETRY_INTERVAL_MS)
    }

    private fun closeSocketResources() {
        try {
            // Closing LocalSocket will likely cause read/write operations on it to throw IOException
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
            Log.i("ReporterServer", "Server socket resources closed.")
        } catch (e: IOException) {
            Log.e("ReporterServer", "Error closing server resources: ${e.message}")
        } catch (e: Exception) {
            Log.e("ReporterServer", "Unexpected error during server stop: ${e.message}")
        } finally {
            clientSocket = null
            serverSocket = null
            outputStream = null
        }
    }

    private fun stopExecutors() {
        serverExecutor.shutdownNow()
        sendExecutor.shutdownNow()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ReporterServer", "Service onCreate")
        startServer()
    }

    override fun onDestroy() {
        Log.d("ReporterServer", "Service onDestroy")
        stopServer(false)
        super.onDestroy()
    }

    /* Codes for bind to this service */
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun sendEvent(event: Int) {
            sendExecutor.execute {
                val ret = this@ReporterServer.sendEvent(event)
                if (!ret) stopServer(true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d("ReporterServer", "Service onBind")
        // Ensure server is running when bound, in case it was stopped unexpectedly
        if (!isRunning.get()) startServer()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("ReporterServer", "Service onUnbind")
        stopServer(false) // Stop server when all clients unbind
        stopSelf()
        return super.onUnbind(intent)
    }
}