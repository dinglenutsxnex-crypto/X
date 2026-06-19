package top.niunaijun.blackboxa.view.net

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionTracker {

    companion object {
        private const val MAX_CONNECTIONS = 2000
        private const val UPDATE_DEBOUNCE_MS = 80L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionMap = ConcurrentHashMap<String, ConnectionRecord>()
    private val recordList = CopyOnWriteArrayList<ConnectionRecord>()

    private val _liveData = MutableLiveData<List<ConnectionRecord>>()
    val liveData: LiveData<List<ConnectionRecord>> = _liveData

    // Added observer for manual polling if needed by FloatingService
    var observer: ((List<ConnectionRecord>) -> Unit)? = null

    private var updatePending = false

    fun getOrCreate(key: String, factory: () -> ConnectionRecord): ConnectionRecord {
        return sessionMap.getOrPut(key) {
            val record = factory()
            synchronized(recordList) {
                if (recordList.size >= MAX_CONNECTIONS) recordList.removeAt(0)
                recordList.add(record)
            }
            scheduleUpdate()
            record
        }
    }

    fun get(key: String): ConnectionRecord? = sessionMap[key]

    fun getById(id: Long): ConnectionRecord? = recordList.find { it.id == id }

    fun remove(key: String) {
        sessionMap.remove(key)
        scheduleUpdate()
    }

    fun markClosed(key: String, status: ConnStatus = ConnStatus.CLOSED) {
        sessionMap[key]?.let { it.status = status }
        sessionMap.remove(key)
        scheduleUpdate()
    }

    fun getAll(): List<ConnectionRecord> = recordList.toList()

    fun clear() {
        sessionMap.clear()
        recordList.clear()
        postUpdate(emptyList())
    }

    private fun scheduleUpdate() {
        if (updatePending) return
        updatePending = true
        mainHandler.postDelayed({
            updatePending = false
            postUpdate(recordList.toList())
        }, UPDATE_DEBOUNCE_MS)
    }

    private fun postUpdate(list: List<ConnectionRecord>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _liveData.value = list
            observer?.invoke(list)
        } else {
            _liveData.postValue(list)
            mainHandler.post { observer?.invoke(list) }
        }
    }

    fun forceRefresh() = postUpdate(recordList.toList())
}
