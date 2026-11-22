// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Test için: hazır PCM'i (24 kHz mono 16 bit) bir kez çalar ve bitene kadar bekler. */
object PcmPlayer {
    fun play(pcm: ByteArray) {
        if (pcm.size < 2) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(GeminiSpeech.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size - pcm.size % 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size - pcm.size % 2)
            track.play()
            Thread.sleep(pcm.size / 2 * 1000L / GeminiSpeech.SAMPLE_RATE + 200)
            track.release()
        }
    }
}
