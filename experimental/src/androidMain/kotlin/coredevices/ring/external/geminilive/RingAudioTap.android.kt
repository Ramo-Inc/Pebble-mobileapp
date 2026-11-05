package coredevices.ring.external.geminilive

import com.wtlp.haversinesatellitelibrary.HaversineSatellite
import com.wtlp.haversinesatellitelibrary.operations.HaversineCollectionTransferCallback
import coredevices.haversine.KMPHaversineSatelliteManager

internal actual fun installCollectionTap(manager: KMPHaversineSatelliteManager, onCollection: (index: Int, data: ByteArray) -> Unit) {
    val environment = manager.environment
    val original = environment.transferDelegate ?: return
    environment.transferDelegate = object : HaversineCollectionTransferCallback by original {
        override fun collectionTransferDidFinish(data: ByteArray, index: Int, satellite: HaversineSatellite) {
            original.collectionTransferDidFinish(data, index, satellite)
            onCollection(index, data.copyOf())
        }
    }
}
