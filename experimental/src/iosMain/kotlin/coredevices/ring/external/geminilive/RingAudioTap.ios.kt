package coredevices.ring.external.geminilive

import HaversineSatelliteLibrary.HaversineCollectionTransferDelegateProtocol
import HaversineSatelliteLibrary.HaversineSwiftError
import HaversineSatelliteLibrary.TelestoStoredCollectionIndexes
import coredevices.haversine.KMPHaversineSatelliteManager
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import platform.posix.memcpy

// The environment holds its delegate weakly.
private var retainedDelegates: List<Any> = emptyList()

@OptIn(ExperimentalForeignApi::class)
internal actual fun installCollectionTap(manager: KMPHaversineSatelliteManager, onCollection: (index: Int, data: ByteArray) -> Unit) {
    val environment = manager.wrap.environment()
    val original = environment.transferDelegate() ?: return
    val tap = object : NSObject(), HaversineCollectionTransferDelegateProtocol {
        override fun firstCollectionToTransferIn(range: CValue<TelestoStoredCollectionIndexes>, `for`: NSUUID): UShort =
            original.firstCollectionToTransferIn(range, `for`)

        override fun willTransferCollectionsIn(range: CValue<TelestoStoredCollectionIndexes>, `for`: NSUUID) =
            original.willTransferCollectionsIn(range, `for`)

        override fun collectionTransferDidFinishWith(data: NSData, collectionIndex: UShort, satelliteId: NSUUID) {
            original.collectionTransferDidFinishWith(data, collectionIndex, satelliteId)
            onCollection(collectionIndex.toInt(), data.toByteArray())
        }

        override fun collectionTransferDidFailWith(error: HaversineSwiftError, collectionIndex: UShort, satelliteId: NSUUID) =
            original.collectionTransferDidFailWith(error, collectionIndex, satelliteId)
    }
    retainedDelegates = listOf(original, tap)
    environment.setTransferDelegate(tap)
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
    if (length > 0u) {
        usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
