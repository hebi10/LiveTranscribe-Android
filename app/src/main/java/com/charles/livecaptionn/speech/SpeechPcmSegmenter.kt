package com.charles.livecaptionn.speech

import java.io.ByteArrayOutputStream
import kotlin.math.abs

internal data class SpeechAudioWindow(val id: Long, val pcm: ByteArray, val isFinal: Boolean)

internal class SpeechPcmSegmenter {
    private val audio = ByteArrayOutputStream(MAX_BYTES)
    private var leadingSilence = ByteArray(0)
    private var voicedBytes = 0
    private var silentBytes = 0
    private var continuingSpeech = false
    private var finished = false
    private var windowId = 0L
    private var lastPreviewBytes = 0
    private var lastPreviewVoicedBytes = 0

    fun feed(pcm: ByteArray, length: Int = pcm.size, speechDetected: Boolean? = null): List<ByteArray> =
        feedSegments(pcm, length, speechDetected).map { it.pcm }

    fun feedSegments(pcm: ByteArray, length: Int = pcm.size, speechDetected: Boolean? = null): List<SpeechAudioWindow> {
        if (finished) return emptyList()
        require(length in 0..pcm.size && length % 2 == 0)
        if (length == 0) return emptyList()
        val voiced = averageAmplitude(pcm, length) >= 120 && (speechDetected != false)
        val outputs = mutableListOf<SpeechAudioWindow>()
        var offset = 0
        while (offset < length) {
            if (audio.size() == 0) {
                if (!voiced && !continuingSpeech) {
                    retainLeadingSilence(pcm, offset, length - offset)
                    break
                }
                audio.write(leadingSilence)
                leadingSilence = ByteArray(0)
            }
            val count = minOf(length - offset, MAX_BYTES - audio.size(),
                if (voiced) MAX_BYTES else SILENCE_BYTES - silentBytes)
            audio.write(pcm, offset, count)
            offset += count
            if (voiced) {
                voicedBytes += count
                silentBytes = 0
            } else {
                silentBytes += count
            }
            if (audio.size() == MAX_BYTES || silentBytes == SILENCE_BYTES) {
                drain(continueUtterance = silentBytes < SILENCE_BYTES)?.let(outputs::add)
            }
        }
        return outputs
    }

    fun previewSegment(minNewBytes: Int = PREVIEW_BYTES): SpeechAudioWindow? {
        require(minNewBytes > 0)
        if (finished || audio.size() - lastPreviewBytes < minNewBytes || voicedBytes <= lastPreviewVoicedBytes) return null
        lastPreviewBytes = audio.size()
        lastPreviewVoicedBytes = voicedBytes
        return SpeechAudioWindow(windowId, audio.toByteArray(), isFinal = false)
    }

    fun pauseBoundary(): ByteArray? = pauseBoundarySegment()?.pcm

    fun pauseBoundarySegment(): SpeechAudioWindow? {
        if (finished) return null
        return drain().also { leadingSilence = ByteArray(0) }
    }

    fun finish(): ByteArray? = finishSegment()?.pcm

    fun finishSegment(): SpeechAudioWindow? {
        if (finished) return null
        finished = true
        return drain().also { leadingSilence = ByteArray(0) }
    }

    private fun drain(continueUtterance: Boolean = false): SpeechAudioWindow? {
        val qualifiedSpeech = voicedBytes >= MIN_SPEECH_BYTES || (continuingSpeech && voicedBytes > 0)
        val result = if (qualifiedSpeech) SpeechAudioWindow(windowId, audio.toByteArray(), isFinal = true) else null
        // A duration split continues the same utterance, including a short voiced
        // tail. Silence/pause/stop ends that eligibility for independent sounds.
        continuingSpeech = continueUtterance && (continuingSpeech || qualifiedSpeech)
        audio.reset()
        windowId++
        lastPreviewBytes = 0
        lastPreviewVoicedBytes = 0
        voicedBytes = 0
        if (!continueUtterance) silentBytes = 0
        return result
    }

    private fun retainLeadingSilence(pcm: ByteArray, offset: Int, length: Int) {
        val nextSize = minOf(LEADING_BYTES, leadingSilence.size + length)
        val next = ByteArray(nextSize)
        val incoming = minOf(length, nextSize)
        val old = nextSize - incoming
        if (old > 0) leadingSilence.copyInto(next, 0, leadingSilence.size - old)
        pcm.copyInto(next, old, offset + length - incoming, offset + length)
        leadingSilence = next
    }

    private fun averageAmplitude(pcm: ByteArray, length: Int): Int {
        var sum = 0L
        for (index in 0 until length step 2) {
            val sample = (pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 255)
            sum += abs(sample)
        }
        return (sum / (length / 2)).toInt()
    }

    private companion object {
        const val MAX_BYTES = 16000 * 2 * 8
        const val SILENCE_BYTES = 32000
        const val LEADING_BYTES = 16000
        const val MIN_SPEECH_BYTES = 8000
        const val PREVIEW_BYTES = 64000
    }
}
