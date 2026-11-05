package coredevices.util.transcription

import android.speech.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidSpeechRecognitionTest {

    @Test
    fun installedLanguageMatchesOnPrimarySubtag() {
        assertEquals(
            SpeechModelAvailability.Installed,
            speechModelAvailability("ja-JP", installed = listOf("ja"), pending = emptyList(), supported = emptyList(), online = emptyList()),
        )
    }

    @Test
    fun onlineLanguageCountsAsInstalled() {
        assertEquals(
            SpeechModelAvailability.Installed,
            speechModelAvailability("ja", installed = emptyList(), pending = emptyList(), supported = emptyList(), online = listOf("ja-JP")),
        )
    }

    @Test
    fun pendingLanguageIsDownloading() {
        assertEquals(
            SpeechModelAvailability.Downloading,
            speechModelAvailability("ja-JP", installed = listOf("en-US"), pending = listOf("ja-JP"), supported = listOf("ja-JP"), online = emptyList()),
        )
    }

    @Test
    fun supportedButMissingLanguageNeedsDownload() {
        assertEquals(
            SpeechModelAvailability.NotDownloaded,
            speechModelAvailability("ja-JP", installed = listOf("en-US"), pending = emptyList(), supported = listOf("ja-JP"), online = emptyList()),
        )
    }

    @Test
    fun unknownLanguageIsUnsupported() {
        assertEquals(
            SpeechModelAvailability.Unsupported,
            speechModelAvailability("ja-JP", installed = listOf("en-US"), pending = emptyList(), supported = listOf("fr-FR"), online = emptyList()),
        )
    }

    @Test
    fun japaneseSegmentsJoinWithoutSpaces() {
        assertEquals("今日は晴れ明日は雨", joinSegments(listOf("今日は晴れ", "明日は雨"), "ja-JP"))
    }

    @Test
    fun otherSegmentsJoinWithSpaces() {
        assertEquals("hello there world", joinSegments(listOf("hello there", " ", "world"), "en-US"))
    }

    @Test
    fun noSpeechErrorsAreReportedAsNoSpeech() {
        assertIs<TranscriptionException.NoSpeechDetected>(recognizerErrorToException(SpeechRecognizer.ERROR_NO_MATCH))
        assertIs<TranscriptionException.NoSpeechDetected>(recognizerErrorToException(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun languageErrorsAreMappedToTheirOwnExceptions() {
        assertIs<TranscriptionException.NoSupportedLanguage>(recognizerErrorToException(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertIs<TranscriptionException.TranscriptionRequiresDownload>(recognizerErrorToException(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    }

    @Test
    fun networkErrorsAreTransient() {
        assertIs<TranscriptionException.TranscriptionNetworkError>(recognizerErrorToException(SpeechRecognizer.ERROR_NETWORK))
    }

    @Test
    fun otherErrorsAreServiceErrors() {
        assertIs<TranscriptionException.TranscriptionServiceError>(recognizerErrorToException(SpeechRecognizer.ERROR_CLIENT))
    }
}
