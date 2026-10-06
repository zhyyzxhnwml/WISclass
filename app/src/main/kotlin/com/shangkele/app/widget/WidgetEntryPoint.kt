package com.shangkele.app.widget

import android.content.Context
import com.shangkele.core.database.repository.ScheduleRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 小组件拿仓库的入口。
 *
 * Glance 的 `provideGlance` 不是 Hilt 管理的组件，注入不进来，
 * 所以走 EntryPoint 从 Application 的依赖图里取。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun scheduleRepository(): ScheduleRepository
}

internal fun Context.scheduleRepository(): ScheduleRepository =
    EntryPointAccessors.fromApplication(this, WidgetEntryPoint::class.java).scheduleRepository()
