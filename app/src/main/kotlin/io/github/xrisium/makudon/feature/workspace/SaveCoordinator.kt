package io.github.xrisium.makudon.feature.workspace

import io.github.xrisium.makudon.domain.Failure
import io.github.xrisium.makudon.domain.WorkspaceException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SavePhase { Saved, Waiting, Saving, Failed }
data class SaveStatus(val phase: SavePhase = SavePhase.Saved, val failure: Failure? = null)

/** One writer and one replaceable latest snapshot. Receipts never set text or acknowledge newer edits. */
class SaveCoordinator(
    private val scope: CoroutineScope,
    initial: String,
    private val clock: () -> Long,
    private val write: suspend (String) -> Unit,
) {
    private val mutableStatus = MutableStateFlow(SaveStatus())
    val status = mutableStatus.asStateFlow()
    var latest: String = initial
        private set
    var revision = 0L
        private set
    var savedRevision = 0L
        private set
    private var firstDirty: Long? = null
    private var timer: Job? = null
    private var writer: Job? = null

    fun changed(text: String) {
        if (text == latest) return
        latest = text; revision++
        if (firstDirty == null) firstDirty = clock()
        if (mutableStatus.value.phase == SavePhase.Failed) return
        mutableStatus.value = SaveStatus(if (writer?.isActive == true) SavePhase.Saving else SavePhase.Waiting)
        timer?.cancel()
        timer = scope.launch {
            delay(minOf(500L, (2_000 - (clock() - (firstDirty ?: clock()))).coerceAtLeast(0)))
            requestWrite()
        }
    }
    private fun requestWrite() {
        if (writer?.isActive == true || mutableStatus.value.phase == SavePhase.Failed || revision == savedRevision) return
        writer = scope.launch {
            while (savedRevision != revision) {
                val targetRevision = revision
                val snapshot = latest
                mutableStatus.value = SaveStatus(SavePhase.Saving)
                try { write(snapshot) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutableStatus.value = SaveStatus(SavePhase.Failed, (e as? WorkspaceException)?.reason ?: Failure.Storage)
                    return@launch
                }
                savedRevision = targetRevision
                if (revision == savedRevision) {
                    firstDirty = null
                    mutableStatus.value = SaveStatus()
                }
            }
        }
    }
    suspend fun flush(retry: Boolean = false): Boolean {
        timer?.cancel()
        if (retry && mutableStatus.value.phase == SavePhase.Failed) mutableStatus.value = SaveStatus(SavePhase.Waiting)
        requestWrite()
        writer?.join()
        return mutableStatus.value.phase != SavePhase.Failed && revision == savedRevision
    }
}
