package me.him188.ani.app.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.BlockDownloadingAlert
import org.libtorrent4j.alerts.BlockFinishedAlert
import org.libtorrent4j.alerts.BlockTimeoutAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import java.io.File

/**
 * Needs to be closed.
 */
public interface TorrentDownloadSession : DownloadStats, AutoCloseable {
    public val savedFile: File

    public val state: Flow<TorrentDownloadState>
//    val metadata: Flow<TorrentMetadata>
}

public sealed class TorrentDownloadState {
    public data object Ready : TorrentDownloadState()
    public data object FetchingMetadata : TorrentDownloadState()
    public data object Downloading : TorrentDownloadState()
    public data object Finished : TorrentDownloadState()
}

internal class TorrentDownloadSessionImpl(
    private val sessionManager: SessionManager,
    private val torrentInfo: TorrentInfo,
    override val savedFile: File,
) : TorrentDownloadSession {
    private val logger = logger(this::class)

    private val scope = CoroutineScope(SupervisorJob())

    override val state: MutableStateFlow<TorrentDownloadState> = MutableStateFlow(TorrentDownloadState.Ready)
    private val _totalBytes = MutableSharedFlow<Long>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val totalBytes: Flow<Long> = _totalBytes.distinctUntilChanged()

    private val _downloadedBytes = MutableSharedFlow<Long>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val downloadedBytes: Flow<Long> = _downloadedBytes.distinctUntilChanged()

    override val downloadRate: MutableSharedFlow<Long> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val progress: MutableSharedFlow<Float> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val peerCount: MutableStateFlow<Int> = MutableStateFlow(0)
    override val pieces: MutableStateFlow<MutableList<PieceState>> = MutableStateFlow(mutableListOf())

    override val isFinished: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val onFinish = CompletableDeferred(Unit)

    private lateinit var handle: TorrentHandle

    internal val listener = object : AlertListener {
        override fun types(): IntArray? = null
        override fun alert(alert: Alert<*>) {
            val type = alert.type()

            when (type) {
                AlertType.ADD_TORRENT -> {
                    logger.info { "Connecting peers" }
                    val handle = (alert as AddTorrentAlert).handle()
                    this@TorrentDownloadSessionImpl.handle = handle
                    val pieceAvailability = handle.pieceAvailability()
                    logger.info { "Total ${pieceAvailability.size} pieces" }
                    logger.info { "Download first and last 10 first." }
                    for (i in pieceAvailability.indices) {
                        if (i < 16 || i >= pieceAvailability.lastIndex - 16) {
                            handle.piecePriority(i, Priority.TOP_PRIORITY)
                            handle.setPieceDeadline(i, (System.currentTimeMillis() / 1000 + 10).toInt())
                        }
                    }
                    pieces.value = MutableList(pieceAvailability.size) { PieceState.READY }


                    // Add trackers
                    trackers.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        handle.addTracker(AnnounceEntry(it))
                    }

                    handle.resume()
                    state.value = TorrentDownloadState.FetchingMetadata
                    logger.info { "Torrent added" }
                    _downloadedBytes.tryEmit(0)
                    _totalBytes.tryEmit(0)
                    progress.tryEmit(0f)
                }

//                AlertType.METADATA_RECEIVED -> {
//                    logger.info { "Metadata received" }
//                }

                AlertType.PEER_CONNECT -> {
                    peerCount.getAndUpdate { it + 1 }
                }

                AlertType.PEER_DISCONNECTED -> {
                    peerCount.getAndUpdate { it - 1 }
                }

                AlertType.BLOCK_TIMEOUT -> {
                    val a = alert as BlockTimeoutAlert
                    val pieceIndex = a.pieceIndex()
                    if (pieceIndex < pieces.value.size) {
                        pieces.value[pieceIndex] = PieceState.FAILED
                    }
                }

                AlertType.BLOCK_DOWNLOADING -> {
                    val a = alert as BlockDownloadingAlert
                    val pieceIndex = a.pieceIndex()
                    if (pieceIndex < pieces.value.size) {
                        pieces.value[pieceIndex] = PieceState.DOWNLOADING
                    }
                }

                AlertType.PIECE_FINISHED -> {
                    val a = alert as PieceFinishedAlert

                    val pieceIndex = a.pieceIndex()
                    if (pieceIndex < pieces.value.size) {
                        pieces.value[pieceIndex] = PieceState.FINISHED
                    }
                }

                AlertType.BLOCK_FINISHED -> {
                    val a = alert as BlockFinishedAlert

                    val pieceIndex = a.pieceIndex()
                    if (pieceIndex < pieces.value.size) {
                        pieces.value[pieceIndex] = PieceState.FINISHED
                    }

                    state.value = TorrentDownloadState.Downloading

//                    val p = (a.handle().status().progress() * 100).toInt()

                    val totalWanted = a.handle().status().totalWanted()
                    _totalBytes.tryEmit(totalWanted)
                    val totalDone = a.handle().status().totalDone()
                    _downloadedBytes.tryEmit(totalDone)
                    downloadRate.tryEmit(a.handle().status().downloadRate().toUInt().toLong())
                    progress.tryEmit(totalDone.toFloat() / totalWanted.toFloat())

//                    sessionManager.stats().totalDownload()
                }

                AlertType.TORRENT_FINISHED -> {
                    logger.info { "Torrent finished" }
                    pieces.value = MutableList(pieces.value.size) { PieceState.FINISHED }
                    _totalBytes.replayCache.lastOrNull()?.let {
                        _downloadedBytes.tryEmit(it)
                    }
                    downloadRate.tryEmit(0)
                    progress.tryEmit(1f)
                    state.value = TorrentDownloadState.Finished
                    isFinished.value = true
                }

                else -> {
                }
            }
        }
    }

    override suspend fun awaitFinished() {
        onFinish.join()
    }

    override fun close() {
        kotlin.runCatching {
            sessionManager.stop()
        }
    }
}


private val trackers = """
                            udp://tracker.opentrackr.org:1337/announce

                            udp://opentracker.i2p.rocks:6969/announce

                            udp://open.demonii.com:1337/announce

                            http://tracker.openbittorrent.com:80/announce

                            udp://tracker.openbittorrent.com:6969/announce

                            udp://open.stealth.si:80/announce

                            udp://tracker.torrent.eu.org:451/announce

                            udp://exodus.desync.com:6969/announce

                            udp://tracker.auctor.tv:6969/announce

                            udp://explodie.org:6969/announce

                            udp://tracker1.bt.moack.co.kr:80/announce

                            udp://uploads.gamecoast.net:6969/announce

                            udp://tracker.tiny-vps.com:6969/announce

                            udp://tracker.therarbg.com:6969/announce

                            udp://tracker.theoks.net:6969/announce

                            udp://tracker.skyts.net:6969/announce

                            udp://tracker.moeking.me:6969/announce

                            udp://thinking.duckdns.org:6969/announce

                            udp://tamas3.ynh.fr:6969/announce

                            udp://retracker01-msk-virt.corbina.net:80/announce

                        """.trimIndent()