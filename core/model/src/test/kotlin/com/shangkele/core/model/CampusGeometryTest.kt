package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusGeometryTest {

    // 福州一带
    private val a = GeoPoint(26.0, 119.0)

    @Test
    fun `同一点距离为零`() {
        assertEquals(0.0, CampusGeometry.distanceMeters(a, a), 0.001)
    }

    /**
     * 0.001 度纬度 ≈ 111.2 米。用「一度纬度约 111 公里」这个常识来验，
     * 而不是照抄一遍公式 —— 抄公式的话公式错了测试也跟着错。
     */
    @Test
    fun `一度纬度约 111 公里`() {
        val oneDegree = CampusGeometry.distanceMeters(GeoPoint(26.0, 119.0), GeoPoint(27.0, 119.0))
        assertEquals(111_195.0, oneDegree, 500.0)
    }

    @Test
    fun `同样的经纬度差，纬度方向的米数大于经度方向`() {
        // 北纬 26 度上，1 度经度约 100 公里，比 1 度纬度的 111 公里短
        val lat = CampusGeometry.distanceMeters(a, GeoPoint(26.001, 119.0))
        val lng = CampusGeometry.distanceMeters(a, GeoPoint(26.0, 119.001))
        assertTrue("纬度方向应更长：$lat vs $lng", lat > lng)
    }

    @Test
    fun `步行时间随距离单调增长`() {
        val near = CampusGeometry.walkingMinutes(a, GeoPoint(26.001, 119.0))
        val far = CampusGeometry.walkingMinutes(a, GeoPoint(26.01, 119.0))
        assertTrue("$near 应小于 $far", near < far)
    }

    /**
     * 下限是 1 分钟，不是 0。
     *
     * 返回 0 会让「提前量」变成 0，提醒就退化成上课铃 —— 而它本来要表达的是
     * 「现在就得动身」。这条以前没人守，很容易被优化成 `toInt()` 顺手抹掉。
     */
    @Test
    fun `再近也至少算一分钟`() {
        assertEquals(1, CampusGeometry.walkingMinutes(a, GeoPoint(26.000001, 119.0)))
        assertEquals(1, CampusGeometry.walkingMinutes(a, a))
    }

    @Test
    fun `直线距离要按绕路系数放大`() {
        val target = GeoPoint(26.01, 119.0)
        val straight = CampusGeometry.distanceMeters(a, target)
        val expected = kotlin.math.ceil(straight * CampusGeometry.DETOUR_FACTOR / CampusGeometry.WALK_METERS_PER_MINUTE).toInt()
        assertEquals(expected, CampusGeometry.walkingMinutes(a, target))
    }

    @Test
    fun `提前量含到教室后的缓冲`() {
        val target = GeoPoint(26.01, 119.0)
        assertEquals(
            CampusGeometry.walkingMinutes(a, target) + CampusGeometry.ARRIVAL_BUFFER_MINUTES,
            CampusGeometry.leadMinutes(a, target),
        )
    }

    @Test
    fun `很近就算已经在教室`() {
        assertTrue(CampusGeometry.alreadyThere(a, GeoPoint(26.0002, 119.0))) // 约 22 米
        assertFalse(CampusGeometry.alreadyThere(a, GeoPoint(26.01, 119.0))) // 约 1.1 公里
    }

    /**
     * (0, 0) 是「定位失败但返回了默认值」时的典型输出，不是校园坐标。
     * 不拦的话会算出一个几万公里的步行时间，提醒彻底失去意义且看不出来。
     */
    @Test
    fun `零点坐标不算合法坐标`() {
        assertFalse(GeoPoint(0.0, 0.0).isValid)
        assertTrue(a.isValid)
        assertFalse(GeoPoint(91.0, 119.0).isValid)
        assertFalse(GeoPoint(26.0, 181.0).isValid)
    }
}
