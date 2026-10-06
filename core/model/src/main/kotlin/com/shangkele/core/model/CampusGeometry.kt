package com.shangkele.core.model

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一个经纬度点。
 *
 * [isValid] 存在的意义是挡住「定位失败但返回了 0,0 坐标系原点」这种值 ——
 * 它不是一个合法的校园坐标，但如果不拦，会被当成「你在几内亚湾」，
 * 于是算出一个几万公里的步行时间，提醒直接失去意义。
 */
data class GeoPoint(val lat: Double, val lng: Double) {
    val isValid: Boolean
        get() = lat in -90.0..90.0 &&
            lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)
}

/**
 * 校园里的距离与步行时间。
 *
 * 纯函数，没有任何 Android 依赖 —— 「该不该现在提醒出发」整条逻辑里最容易被算错的
 * 就是这里，所以它必须能在单测里直接跑。
 */
object CampusGeometry {

    /** 地球平均半径（米）。 */
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** 步行速度：70 米/分 ≈ 4.2 km/h，校园里的正常步速。 */
    const val WALK_METERS_PER_MINUTE = 70.0

    /**
     * 直线距离要乘的绕路系数。
     *
     * 人不是飞过去的：楼与楼之间得绕路、进门、上楼梯。1.3 是保守值 ——
     * 宁可略微高估（早提醒几分钟）也不要低估（提醒了也来不及）。
     */
    const val DETOUR_FACTOR = 1.3

    /**
     * 离教室这么近就认为「已经算到了」，不再提醒出发。
     *
     * 120 米约等于 2 分钟步行；室内 GPS 误差本身就有几十米，
     * 阈值给小了会在你站在教室里的时候还提醒你「该出发了」。
     */
    const val ALREADY_THERE_METERS = 120.0

    /** 到了教室还要留的缓冲：找座位、掏书。 */
    const val ARRIVAL_BUFFER_MINUTES = 3

    /** 两点间的直线距离（米），Haversine。 */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)

        val sinHalfLat = sin(dLat / 2)
        val sinHalfLng = sin(dLng / 2)
        val h = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLng * sinHalfLng

        // min(1, …) 是防浮点误差把 asin 的入参推到 1 以上（会得到 NaN）
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /**
     * 走到教室要几分钟。
     *
     * 返回值**至少 1 分钟**：返回 0 会让提前量变成 0，提醒就退化成「上课铃」，
     * 而用户要的是「留出走过去的这段时间」。
     */
    fun walkingMinutes(from: GeoPoint, to: GeoPoint): Int {
        val meters = distanceMeters(from, to) * DETOUR_FACTOR
        return ceil(meters / WALK_METERS_PER_MINUTE).toInt().coerceAtLeast(1)
    }

    /** 算上缓冲后应该提前多少分钟动身。 */
    fun leadMinutes(from: GeoPoint, to: GeoPoint): Int =
        walkingMinutes(from, to) + ARRIVAL_BUFFER_MINUTES

    /** 是否已经算到教室了。 */
    fun alreadyThere(from: GeoPoint, to: GeoPoint): Boolean =
        distanceMeters(from, to) <= ALREADY_THERE_METERS
}
