package com.shangkele.core.context.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.shangkele.core.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 取一次当前位置。
 *
 * **用系统的 [LocationManager]，不引 Google Play Services**：荣耀这类机器上没有 GMS，
 * `FusedLocationProviderClient` 会直接拿不到值（而且不会有明显报错）。
 * 我们只要「大概在校园的哪个位置」，系统定位完全够用，还省一个依赖。
 *
 * 权限只要**粗略定位**就够：判断「在不在教学楼附近」不需要米级精度，
 * 而粗略定位对用户来说打扰更小（系统会允许他只给大概位置）。
 */
@Singleton
class DeviceLocation @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * 取一次位置。**拿不到就返回 null，绝不用默认值顶上。**
     *
     * 拿不到的原因很多：没授权、系统定位关着、室内没信号、超时。
     * 这些情况下正确的做法是让调用方退回旧的楼栋推断 —— 而不是编一个坐标出来，
     * 那样会算出一个看着正常、实则荒唐的出发时刻。
     */
    suspend fun current(timeoutMs: Long = 8_000L): GeoPoint? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        // 先要一个新的；室内常常只能靠系统缓存的「最后一次已知位置」，所以两路都试
        val fresh = withTimeoutOrNull(timeoutMs) { requestOnce(manager) }
        val point = fresh?.toGeoPoint() ?: latestKnown(manager)?.toGeoPoint() ?: return null

        return point.takeIf { it.isValid }
    }

    /**
     * 主动要一次定位。
     *
     * Android 11（API 30）起才有 `getCurrentLocation`；更低版本系统没有等价 API，
     * 只能退回「最后一次已知位置」（见 [latestKnown]）—— minSdk 26，
     * 所以那条分支是真的会走到的，不能省。
     */
    private suspend fun requestOnce(manager: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = activeProvider(manager) ?: return null

        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (e: SecurityException) {
                // 权限在这一瞬间被撤销（用户去设置里关掉）会走到这里，正常降级
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** 系统缓存的最后一次已知位置。取最新的一条。 */
    private fun latestKnown(manager: LocationManager): Location? =
        PROVIDERS
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            // 缓存可能很旧，超过 10 分钟就不要了：用十分钟前的位置判断「你现在在哪」是错的
            .filter { System.currentTimeMillis() - it.time <= MAX_CACHE_AGE_MS }
            .maxByOrNull { it.time }

    /** 挑一个当前可用的定位来源。 */
    private fun activeProvider(manager: LocationManager): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                    return LocationManager.FUSED_PROVIDER
                }
            }
        }
        return PROVIDERS.firstOrNull { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        const val MAX_CACHE_AGE_MS = 10 * 60 * 1000L
    }
}
