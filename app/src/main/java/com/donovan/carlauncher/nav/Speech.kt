package com.donovan.carlauncher.nav

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken turn instructions. Tagged as navigation guidance so Android ducks the music
 * over the car's Bluetooth link instead of talking over it.
 */
class Speech(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let { engine ->
                        runCatching { engine.language = Locale.getDefault() }
                        runCatching {
                            engine.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }
                    }
                    ready = true
                }
            }
        }
    }

    fun say(text: String, interrupt: Boolean = true) {
        if (!ready || text.isBlank()) return
        runCatching {
            tts?.speak(
                text,
                if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "car-launcher-nav",
            )
        }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
