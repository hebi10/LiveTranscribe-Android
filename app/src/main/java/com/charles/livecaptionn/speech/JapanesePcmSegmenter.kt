package com.charles.livecaptionn.speech

import java.io.ByteArrayOutputStream
import kotlin.math.abs

internal class JapanesePcmSegmenter {
    private val audio = ByteArrayOutputStream(MAX_BYTES)
    private var leadingSilence = ByteArray(0)
    private var voicedBytes = 0
    private var silentBytes = 0
    private var continuingSpeech = false
    private var finished = false

    fun feed(pcm: ByteArray, length: Int = pcm.size, speechDetected: Boolean? = null): List<ByteArray> {
        if (finished) return emptyList()
        require(length in 0..pcm.size && length % 2 == 0)
        if (length == 0) return emptyList()
        val voiced = averageAmplitude(pcm, length) >= 120 && (speechDetected != false)
        val outputs = mutableListOf<ByteArray>()
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

    fun pauseBoundary(): ByteArray? {
        if (finished) return null
        return drain().also { leadingSilence = ByteArray(0) }
    }

    fun finish(): ByteArray? {
        if (finished) return null
        finished = true
        return drain().also { leadingSilence = ByteArray(0) }
    }

    private fun drain(continueUtterance: Boolean = false): ByteArray? {
        val qualifiedSpeech = voicedBytes >= MIN_SPEECH_BYTES || (continuingSpeech && voicedBytes > 0)
        val result = if (qualifiedSpeech) audio.toByteArray() else null
        // A duration split continues the same utterance, including a short voiced
        // tail. Silence/pause/stop ends that eligibility for independent sounds.
        continuingSpeech = continueUtterance && (continuingSpeech || qualifiedSpeech)
        audio.reset()
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
    }
}
