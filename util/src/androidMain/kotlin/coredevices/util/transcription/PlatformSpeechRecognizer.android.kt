package coredevices.util.transcription

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.inProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Android's [SpeechRecognizer] fed from a PCM file (API 33+). Prefers the on-device recognizer and
 * falls back to the default recognition service when the device has no on-device one.
 */
actual class PlatformSpeechRecognizer : KoinComponent {
    private val logger = Logger.withTag("PlatformSpeechRecognizer")
    private val context: Context by inject()
    private val mutex = Mutex()
    private val _supportedLanguageTags = MutableStateFlow<List<String>>(emptyList())
    private val _downloadStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
    private val mainExecutor = Executor { Handler(Looper.getMainLooper()).post(it) }

    actual suspend fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

    actual suspend fun isAuthorized(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    actual val supportedLanguageTags: StateFlow<List<String>> = _supportedLanguageTags.asStateFlow()

    actual val downloadStatus: StateFlow<ModelDownloadStatus> = _downloadStatus.asStateFlow()

    actual suspend fun modelAvailability(languageTag: String?): SpeechModelAvailability {
        if (_downloadStatus.value.inProgress) return SpeechModelAvailability.Downloading
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isAvailable()) return SpeechModelAvailability.Unsupported
        val support = querySupport(languageTag) ?: return SpeechModelAvailability.Unsupported
        _supportedLanguageTags.value =
            (support.installedOnDeviceLanguages + support.supportedOnDeviceLanguages + support.onlineLanguages).distinct()
        return speechModelAvailability(
            languageTag ?: Locale.getDefault().toLanguageTag(),
            installed = support.installedOnDeviceLanguages,
            pending = support.pendingOnDeviceLanguages,
            supported = support.supportedOnDeviceLanguages,
            online = support.onlineLanguages,
        )
    }

    actual fun downloadModel(languageTag: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (_downloadStatus.value.inProgress) return false
        _downloadStatus.value = ModelDownloadStatus.Scheduled(PLATFORM_STT_MODEL_NAME)
        mainExecutor.execute {
            val recognizer = createRecognizer()
            recognizer.triggerModelDownload(recognitionIntent(languageTag), mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {
                    _downloadStatus.value = ModelDownloadStatus.Downloading(PLATFORM_STT_MODEL_NAME, completedPercent / 100f)
                }

                override fun onSuccess() = finish(ModelDownloadStatus.Idle)

                override fun onScheduled() = finish(ModelDownloadStatus.Idle)

                override fun onError(error: Int) {
                    logger.w { "Platform speech model download failed: $error" }
                    finish(ModelDownloadStatus.Failed(PLATFORM_STT_MODEL_NAME, "error $error"))
                }

                private fun finish(status: ModelDownloadStatus) {
                    _downloadStatus.value = status
                    recognizer.destroy()
                }
            })
        }
        return true
    }

    actual fun releaseUnusedModels(languageTag: String?) = Unit

    actual suspend fun transcribe(pcm: ByteArray, sampleRate: Int, languageTag: String?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw TranscriptionException.TranscriptionServiceUnavailable(PLATFORM_STT_MODEL_NAME)
        }
        when (modelAvailability(languageTag)) {
            SpeechModelAvailability.Installed -> Unit
            SpeechModelAvailability.Unsupported ->
                throw TranscriptionException.NoSupportedLanguage(PLATFORM_STT_MODEL_NAME)
            SpeechModelAvailability.Downloading, SpeechModelAvailability.NotDownloaded ->
                throw TranscriptionException.TranscriptionRequiresDownload(
                    "Speech model for ${languageTag ?: "device locale"} not installed",
                    PLATFORM_STT_MODEL_NAME,
                )
        }
        if (!mutex.tryLock()) {
            throw TranscriptionException.TranscriptionInProgress(PLATFORM_STT_MODEL_NAME)
        }
        // The recognizer reads the audio source in real time at most, so allow the clip length plus slack.
        val timeout = (pcm.size / (sampleRate * 2)).seconds + RECOGNITION_SLACK
        val file = File(context.cacheDir, "platform_stt_${Uuid.random()}.pcm")
        try {
            withContext(Dispatchers.IO) { file.writeBytes(pcm) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                withTimeoutOrNull(timeout) { recognize(source, sampleRate, languageTag) }
                    ?: throw TranscriptionException.TranscriptionServiceError("Recognition timed out", modelUsed = PLATFORM_STT_MODEL_NAME)
            }
        } finally {
            mutex.unlock()
            file.delete()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(source: ParcelFileDescriptor, sampleRate: Int, languageTag: String?): String =
        withContext(Dispatchers.Main) {
            val recognizer = createRecognizer()
            try {
                suspendCancellableCoroutine { cont ->
                    val segments = mutableListOf<String>()
                    fun finish() {
                        if (cont.isActive) cont.resume(joinSegments(segments, languageTag))
                    }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onSegmentResults(segmentResults: Bundle) {
                            segmentResults.bestResult()?.let { segments += it }
                        }

                        override fun onEndOfSegmentedSession() = finish()

                        override fun onResults(results: Bundle) {
                            results.bestResult()?.let { segments += it }
                            finish()
                        }

                        override fun onError(error: Int) {
                            if (!cont.isActive) return
                            val exception = recognizerErrorToException(error)
                            if (exception is TranscriptionException.NoSpeechDetected && segments.isNotEmpty()) {
                                finish()
                            } else {
                                logger.w { "Speech recognizer error $error" }
                                cont.resumeWithException(exception)
                            }
                        }

                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    recognizer.startListening(
                        recognitionIntent(languageTag)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            // Without this the session ends at the first pause and longer notes are cut short.
                            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    )
                }
            } finally {
                recognizer.destroy()
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun querySupport(languageTag: String?): RecognitionSupport? = withContext(Dispatchers.Main) {
        val recognizer = createRecognizer()
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.checkRecognitionSupport(recognitionIntent(languageTag), mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (cont.isActive) cont.resume(recognitionSupport)
                    }

                    override fun onError(error: Int) {
                        logger.w { "Speech recognition support check failed: $error" }
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } finally {
            recognizer.destroy()
        }
    }

    /** Must be called on the main thread. */
    private fun createRecognizer(): SpeechRecognizer =
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            logger.i { "No on-device speech recognizer; using the default recognition service" }
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun recognitionIntent(languageTag: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .apply { languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }

    private fun Bundle.bestResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        val RECOGNITION_SLACK = 30.seconds
    }
}

internal fun speechModelAvailability(
    languageTag: String,
    installed: List<String>,
    pending: List<String>,
    supported: List<String>,
    online: List<String>,
): SpeechModelAvailability = when {
    installed.coversLanguage(languageTag) || online.coversLanguage(languageTag) -> SpeechModelAvailability.Installed
    pending.coversLanguage(languageTag) -> SpeechModelAvailability.Downloading
    supported.coversLanguage(languageTag) -> SpeechModelAvailability.NotDownloaded
    else -> SpeechModelAvailability.Unsupported
}

private val UNSPACED_LANGUAGES = setOf("ja", "zh", "th")

internal fun joinSegments(segments: List<String>, languageTag: String?): String {
    val language = (languageTag ?: Locale.getDefault().toLanguageTag()).substringBefore('-').lowercase()
    val separator = if (language in UNSPACED_LANGUAGES) "" else " "
    return segments.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator)
}

internal fun recognizerErrorToException(error: Int): TranscriptionException = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> TranscriptionException.NoSpeechDetected("no match", PLATFORM_STT_MODEL_NAME)
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TranscriptionException.NoSpeechDetected("speech timeout", PLATFORM_STT_MODEL_NAME)
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> TranscriptionException.NoSupportedLanguage(PLATFORM_STT_MODEL_NAME)
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> TranscriptionException.TranscriptionRequiresDownload(
        "Speech model not installed",
        PLATFORM_STT_MODEL_NAME,
    )
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
        TranscriptionException.TranscriptionNetworkError(IOException("Speech recognizer error $error"), PLATFORM_STT_MODEL_NAME)
    else -> TranscriptionException.TranscriptionServiceError("Speech recognizer error $error", modelUsed = PLATFORM_STT_MODEL_NAME)
}
