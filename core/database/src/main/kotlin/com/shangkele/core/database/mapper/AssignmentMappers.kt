package com.shangkele.core.database.mapper

import com.shangkele.core.database.entity.AssignmentEntity
import com.shangkele.core.model.Assignment

fun AssignmentEntity.toDomain(): Assignment = Assignment(
    id = id,
    courseId = courseId,
    noteId = noteId,
    title = title,
    dueEpochDay = dueEpochDay,
    dueRawText = dueRawText,
    confidence = confidence,
    done = done,
    remindAtMs = remindAtMs,
)

fun Assignment.toEntity(): AssignmentEntity = AssignmentEntity(
    id = id,
    courseId = courseId,
    noteId = noteId,
    title = title,
    dueEpochDay = dueEpochDay,
    dueRawText = dueRawText,
    confidence = confidence,
    done = done,
    remindAtMs = remindAtMs,
)
