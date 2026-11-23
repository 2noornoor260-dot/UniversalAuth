package ax.nd.faceunlock.overlay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * نغمات الفتح المركّبة (PCM 44.1kHz أحادي). التركيب كما كان سابقاً حرفياً، لكن العيّنات تُحسب مرة واحدة
 * لكل نوع وتُخزَّن، فيصبح التشغيل التالي فورياً. يمكن استدعاء [prepare] مبكراً لتسخين النغمة المختارة.
 */
object ChimePlayer {

    private const val SAMPLE_RATE = 44100
    private val cache = HashMap<String, ShortArray>()

    private fun durationFor(type: String): Double = when (type) {
        "crystal" -> 0.22
        "titanium" -> 0.26
        "silk" -> 0.42
        else -> 0.36
    }

    private fun synth(type: String): ShortArray {
        val durationSec = durationFor(type)
        val numSamples = (SAMPLE_RATE * durationSec).toInt()
        val pcm = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            val sample = when (type) {
                "crystal" -> {
                    val env = exp(-t * 22.0) * min(1.0, t / 0.004)
                    (0.45 * sin(2.0 * PI * 523.25 * t)
                            + 0.30 * sin(2.0 * PI * 783.99 * t)
                            + 0.25 * sin(2.0 * PI * 261.63 * t)) * env
                }
                "titanium" -> {
                    val env1 = exp(-t * 26.0) * min(1.0, t / 0.003)
                    val t2 = (t - 0.055).coerceAtLeast(0.0)
                    val env2 = if (t >= 0.055) exp(-t2 * 18.0) * min(1.0, t2 / 0.004) else 0.0
                    (0.40 * sin(2.0 * PI * 440.0 * t) * env1
                            + 0.45 * sin(2.0 * PI * 659.25 * t2) * env2
                            + 0.20 * sin(2.0 * PI * 220.0 * t) * env1)
                }
                "silk" -> {
                    val env = exp(-t * 9.5) * min(1.0, t / 0.015)
                    (0.38 * sin(2.0 * PI * 587.33 * t)
                            + 0.32 * sin(2.0 * PI * 739.99 * t)
                            + 0.30 * sin(2.0 * PI * 880.00 * t)) * env
                }
                else -> {
                    val env1 = exp(-t * 14.0) * min(1.0, t / 0.006)
                    val t2 = (t - 0.085).coerceAtLeast(0.0)
                    val env2 = if (t >= 0.085) exp(-t2 * 11.0) * min(1.0, t2 / 0.006) else 0.0
                    (0.42 * sin(2.0 * PI * 698.46 * t) * env1
                            + 0.16 * sin(2.0 * PI * 1396.91 * t) * env1
                            + 0.48 * sin(2.0 * PI * 1046.50 * t2) * env2
                            + 0.14 * sin(2.0 * PI * 523.25 * t2) * env2)
                }
            }
            pcm[i] = (sample.coerceIn(-1.0, 1.0) * 18000.0).toInt().toShort()
        }
        return pcm
    }

    private fun samplesFor(type: String): ShortArray {
        synchronized(cache) {
            val hit = cache[type]
            if (hit != null) return hit
            val made = synth(type)
            cache[type] = made
            return made
        }
    }

    /** تحضير مسبق على خيط خلفي (اختياري). */
    fun prepare(type: String) {
        if (type == "none") return
        Thread { samplesFor(type) }.start()
    }

    fun play(type: String) {
        if (type == "none") return
        Thread {
            try {
                val pcm = samplesFor(type)
                val durationSec = durationFor(type)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((durationSec * 1000).toLong() + 80L)
                track.release()
            } catch (e: Exception) {
            }
        }.start()
    }
}
