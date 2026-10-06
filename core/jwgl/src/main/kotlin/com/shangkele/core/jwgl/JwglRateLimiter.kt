package com.shangkele.core.jwgl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 请求限速。
 *
 * docs/03-教务系统对接.md §十一 的硬性要求：对教务系统的请求间隔 ≥ 2 秒、严格串行。
 * 这既是为了不给学校服务器添麻烦，也是避免触发风控。
 *
 * 用 `System.nanoTime()` 而不是 Android 的 `SystemClock`，保证可测。
 */
@Singleton
class JwglRateLimiter @Inject constructor() {

    private val mutex = Mutex()
    private var lastRequestNanos = 0L

    var minIntervalMs: Long = 2_000L

    /** 挂起直到距上次请求已满 [minIntervalMs]。 */
    suspend fun await() {
        mutex.withLock {
            val now = System.nanoTime()
            if (lastRequestNanos != 0L) {
                val elapsedMs = (now - lastRequestNanos) / 1_000_000
                if (elapsedMs < minIntervalMs) {
                    delay(minIntervalMs - elapsedMs)
                }
            }
            lastRequestNanos = System.nanoTime()
        }
    }
}
