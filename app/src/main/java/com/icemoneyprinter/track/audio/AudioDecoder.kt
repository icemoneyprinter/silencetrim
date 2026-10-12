package com.icemoneyprinter.track.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any compressed audio (m4a/mp3/opus) to 16-bit mono PCM at its native rate. */
object AudioDecoder {

    fun decodeToMonoPcm(context: Context, uri: Uri): PcmMono? {
        val extractor = MediaExtractor()
        var track = -1
        return try {
            extractor.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmShorts = ArrayList<Short>(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.asShortBuffer()
                        while (shorts.hasRemaining()) pcmShorts.add(shorts.get())
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }

            runCatching { codec.stop() }
            runCatching { codec.release() }

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val frameCount = pcmShorts.size / channels
            val mono = ShortArray(frameCount)
            for (i in 0 until frameCount) {
                var sum = 0
                for (c in 0 until channels) sum += pcmShorts[i * channels + c]
                mono[i] = (sum / channels).toShort()
            }
            PcmMono(mono, sampleRate)
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                extractor.unselectTrack(track)
                extractor.release()
            }
        }
    }
}
