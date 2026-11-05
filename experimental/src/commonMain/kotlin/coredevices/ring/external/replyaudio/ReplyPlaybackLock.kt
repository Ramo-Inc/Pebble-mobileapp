package coredevices.ring.external.replyaudio

import kotlinx.coroutines.sync.Mutex

/** Shared by every reply player so server replies and Gemini Live replies never talk over each other. */
class ReplyPlaybackLock {
    val mutex = Mutex()
}
