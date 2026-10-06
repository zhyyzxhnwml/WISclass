package com.shangkele.core.database.mapper

import com.shangkele.core.database.entity.NoteEntity
import com.shangkele.core.database.entity.PhotoEntity
import com.shangkele.core.model.Note
import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.NoteStatus

fun NoteEntity.toDomain(): Note = Note(
    id = id,
    courseId = courseId,
    semesterId = semesterId,
    weekIndex = weekIndex,
    dateEpochDay = dateEpochDay,
    startedAtMs = startedAtMs,
    durationMs = durationMs,
    audioPath = audioPath,
    audioSizeBytes = audioSizeBytes,
    audioKept = audioKept,
    transcriptText = transcriptText,
    status = runCatching { NoteStatus.valueOf(status) }.getOrDefault(NoteStatus.FAILED),
    title = title,
    createdAt = createdAt,
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    courseId = courseId,
    semesterId = semesterId,
    weekIndex = weekIndex,
    dateEpochDay = dateEpochDay,
    startedAtMs = startedAtMs,
    durationMs = durationMs,
    audioPath = audioPath,
    audioSizeBytes = audioSizeBytes,
    audioKept = audioKept,
    transcriptText = transcriptText,
    liveTranscriptText = null,
    status = status.name,
    title = title,
    createdAt = createdAt,
)

fun PhotoEntity.toDomain(): NotePhoto = NotePhoto(
    id = id,
    noteId = noteId,
    semesterId = semesterId,
    courseId = courseId,
    offsetMs = offsetMs,
    takenAtMs = takenAtMs,
    path = path,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
)

fun NotePhoto.toEntity(): PhotoEntity = PhotoEntity(
    id = id,
    noteId = noteId,
    semesterId = semesterId,
    courseId = courseId,
    offsetMs = offsetMs,
    takenAtMs = takenAtMs,
    path = path,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
)
