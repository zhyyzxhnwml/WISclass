package com.shangkele.core.database.mapper

import com.shangkele.core.common.classroom.RoomKeyNormalizer
import com.shangkele.core.common.week.WeeksCodec
import com.shangkele.core.database.entity.CourseEntity
import com.shangkele.core.database.entity.SemesterEntity
import com.shangkele.core.database.entity.TimeSlotEntity
import com.shangkele.core.model.Course
import com.shangkele.core.model.CourseSource
import com.shangkele.core.model.Semester
import com.shangkele.core.model.TimeSlot

fun SemesterEntity.toDomain(): Semester = Semester(
    id = id,
    xnm = xnm,
    xqm = xqm,
    name = name,
    startDateEpochDay = startDateEpochDay,
    totalWeeks = totalWeeks,
    isActive = isActive,
)

fun Semester.toEntity(): SemesterEntity = SemesterEntity(
    id = id,
    xnm = xnm,
    xqm = xqm,
    name = name,
    startDateEpochDay = startDateEpochDay,
    totalWeeks = totalWeeks,
    isActive = isActive,
)

fun CourseEntity.toDomain(): Course = Course(
    id = id,
    semesterId = semesterId,
    name = name,
    teacher = teacher,
    roomRaw = roomRaw,
    roomKey = roomKey ?: RoomKeyNormalizer.normalize(roomRaw),
    credits = credits,
    courseType = courseType,
    teachingClass = teachingClass,
    weekday = weekday,
    startSection = startSection,
    endSection = endSection,
    weeks = WeeksCodec.decode(weeksJson),
    colorSeed = colorSeed,
    source = runCatching { CourseSource.valueOf(source) }.getOrDefault(CourseSource.API),
)

fun Course.toEntity(nowMs: Long): CourseEntity = CourseEntity(
    id = id,
    semesterId = semesterId,
    name = name,
    teacher = teacher,
    roomRaw = roomRaw,
    roomKey = roomKey ?: RoomKeyNormalizer.normalize(roomRaw),
    campus = null,
    credits = credits,
    courseType = courseType,
    teachingClass = teachingClass,
    classGroup = null,
    weekday = weekday,
    startSection = startSection,
    endSection = endSection,
    weeksJson = WeeksCodec.encode(weeks),
    colorSeed = colorSeed,
    source = source.name,
    stableKey = stableKey(),
    rawJson = null,
    updatedAt = nowMs,
)

fun TimeSlotEntity.toDomain(): TimeSlot = TimeSlot(
    section = section,
    startMinutes = startMinutes,
    endMinutes = endMinutes,
)

fun TimeSlot.toEntity(semesterId: Long): TimeSlotEntity = TimeSlotEntity(
    semesterId = semesterId,
    section = section,
    startMinutes = startMinutes,
    endMinutes = endMinutes,
)
