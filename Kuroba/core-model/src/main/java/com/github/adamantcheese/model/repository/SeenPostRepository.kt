package com.github.adamantcheese.model.repository

import com.github.adamantcheese.common.ModularResult
import com.github.adamantcheese.model.KurobaDatabase
import com.github.adamantcheese.model.common.Logger
import com.github.adamantcheese.model.data.SeenPost
import com.github.adamantcheese.model.source.local.SeenPostLocalSource
import com.github.adamantcheese.model.util.ensureBackgroundThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

class SeenPostRepository(
        database: KurobaDatabase,
        loggerTag: String,
        logger: Logger,
        private val seenPostLocalSource: SeenPostLocalSource
) : AbstractRepository(database, logger) {
    private val TAG = "$loggerTag SeenPostRepository"
    private val alreadyExecuted = AtomicBoolean(false)

    suspend fun insert(seenPost: SeenPost): ModularResult<Unit> {
        ensureBackgroundThread()
        seenPostLocalRepositoryCleanup().ignore()

        return seenPostLocalSource.insert(seenPost)
    }

    suspend fun selectAllByLoadableUid(loadableUid: String): ModularResult<List<SeenPost>> {
        ensureBackgroundThread()
        return seenPostLocalSource.selectAllByLoadableUid(loadableUid)
    }

    private suspend fun seenPostLocalRepositoryCleanup(): ModularResult<Int> {
        ensureBackgroundThread()

        if (!alreadyExecuted.compareAndSet(false, true)) {
            return ModularResult.value(0)
        }

        val result = seenPostLocalSource.deleteOlderThan(
                SeenPostLocalSource.ONE_MONTH_AGO
        )

        if (result is ModularResult.Value) {
            logger.log(TAG, "cleanup() -> $result")
        } else {
            logger.logError(TAG, "cleanup() -> $result")
        }

        return result
    }

    fun deleteAllSync(): Int {
        return runBlocking(Dispatchers.Default) { deleteAll().unwrap() }
    }

    suspend fun deleteAll(): ModularResult<Int> {
        ensureBackgroundThread()

        return seenPostLocalSource.deleteAll()
    }
}