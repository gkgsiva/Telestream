package com.telestream.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.telestream.app.telegram.TdClient
import com.telestream.app.telegram.TdException
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Streams a Telegram file into ExoPlayer without downloading it first.
 *
 * ExoPlayer asks for a byte position (the start, a seek target, or the index at the end of an
 * MP4). We ask TDLib to download from that offset, wait until some bytes from there are on
 * disk, and read them straight from TDLib's partially downloaded file. A seek opens a new
 * range; parts already downloaded stay in TDLib's cache.
 *
 * Uri format: `tg://file/<fileId>`, built with [uriFor].
 */
@UnstableApi
class TelegramDataSource(private val td: TdClient) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var fileSize = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    private var requestedOffset = -1L
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val file = request(TdApi.GetFile(fileId))
        fileSize = if (file.size > 0) file.size else file.expectedSize
        position = dataSpec.position
        if (fileSize > 0 && position > fileSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileSize > 0 -> fileSize - position
            else -> C.LENGTH_UNSET.toLong()
        }
        startDownload(position)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val available = waitForBytes(position)
        if (available <= 0L) return C.RESULT_END_OF_INPUT

        var toRead = minOf(length.toLong(), available)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) toRead = minOf(toRead, bytesRemaining)

        val file = openLocalFile()
        file.seek(position)
        val read = file.read(buffer, offset, toRead.toInt())
        if (read <= 0) return C.RESULT_END_OF_INPUT

        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            raf?.close()
        } catch (_: IOException) {
        }
        raf = null
        rafPath = null
        requestedOffset = -1
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Blocks until at least one byte at [offset] is downloaded; returns how many are ready. */
    private fun waitForBytes(offset: Long): Long {
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException()
            val ready = request(TdApi.GetFileDownloadedPrefixSize(fileId, offset)).size
            if (ready > 0) return ready
            if (fileSize in 1..offset) return 0 // End of file.

            // Make sure TDLib is downloading from where we read (another reader may have moved it).
            val file = request(TdApi.GetFile(fileId))
            if (file.local.isDownloadingCompleted) return maxOf(0L, file.size - offset)
            if (!file.local.isDownloadingActive || file.local.downloadOffset != requestedOffset) {
                startDownload(offset)
            }
            if (System.currentTimeMillis() > deadline) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
    }

    private fun startDownload(offset: Long) {
        requestedOffset = offset
        request(TdApi.DownloadFile(fileId, PRIORITY, offset, 0, false))
    }

    private fun openLocalFile(): RandomAccessFile {
        val path = request(TdApi.GetFile(fileId)).local.path
        if (path.isEmpty()) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val current = raf
        if (current != null && rafPath == path) return current
        current?.close()
        return RandomAccessFile(path, "r").also {
            raf = it
            rafPath = path
        }
    }

    private fun <R : TdApi.Object> request(function: TdApi.Function<R>): R = try {
        td.sendBlocking(function)
    } catch (e: TdException) {
        throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    class Factory(private val td: TdClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(td)
    }

    companion object {
        const val SCHEME = "tg"
        private const val PRIORITY = 32
        private const val POLL_MS = 50L
        private const val READ_TIMEOUT_MS = 60_000L

        fun uriFor(fileId: Int): Uri = Uri.parse("$SCHEME://file/$fileId")
    }
}
