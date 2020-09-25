package com.github.k1rakishou.chan.core.manager

import androidx.annotation.GuardedBy
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.Logger
import com.github.k1rakishou.common.*
import com.github.k1rakishou.model.data.bookmark.ThreadBookmark
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkView
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.repository.BookmarksRepository
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

class BookmarksManager(
  private val isDevFlavor: Boolean,
  private val verboseLogsEnabled: Boolean,
  private val appScope: CoroutineScope,
  private val applicationVisibilityManager: ApplicationVisibilityManager,
  private val archivesManager: ArchivesManager,
  private val bookmarksRepository: BookmarksRepository
) {
  private val lock = ReentrantReadWriteLock()
  private val persistTaskSubject = PublishProcessor.create<Unit>()
  private val bookmarksChangedSubject = PublishProcessor.create<BookmarkChange>()
  private val delayedBookmarksChangedSubject = PublishProcessor.create<BookmarkChange>()
  private val threadIsFetchingEventsSubject = PublishProcessor.create<ChanDescriptor.ThreadDescriptor>()

  private val suspendableInitializer = SuspendableInitializer<Unit>("BookmarksManager")
  private val persistRunning = AtomicBoolean(false)
  private val currentOpenThread = AtomicReference<ChanDescriptor.ThreadDescriptor>(null)

  @GuardedBy("lock")
  private val bookmarks = mutableMapWithCap<ChanDescriptor.ThreadDescriptor, ThreadBookmark>(256)
  @GuardedBy("lock")
  private val orders = mutableListWithCap<ChanDescriptor.ThreadDescriptor>(256)

  fun initialize() {
    Logger.d(TAG, "BookmarksManager.initialize()")

    appScope.launch {
      applicationVisibilityManager.addListener { visibility ->
        if (!suspendableInitializer.isInitialized()) {
          return@addListener
        }

        if (visibility != ApplicationVisibility.Background) {
          return@addListener
        }

        persistBookmarks(true)
      }

      appScope.launch {
        persistTaskSubject
          .onBackpressureLatest()
          .debounce(1, TimeUnit.SECONDS)
          .collect { persistBookmarks() }
      }

      appScope.launch {
        delayedBookmarksChangedSubject
          .onBackpressureLatest()
          .debounce(1, TimeUnit.SECONDS)
          .doOnNext { bookmarkChange ->
            if (verboseLogsEnabled) {
              Logger.d(TAG, "delayedBookmarksChanged(${bookmarkChange::class.java.simpleName})")
            }
          }
          .collect { bookmarkChange -> bookmarksChanged(bookmarkChange) }
      }

      appScope.launch(Dispatchers.Default) {
        @Suppress("MoveVariableDeclarationIntoWhen")
        val bookmarksResult = bookmarksRepository.initialize()
        when (bookmarksResult) {
          is ModularResult.Value -> {
            lock.write {
              bookmarks.clear()
              orders.clear()

              bookmarksResult.value.forEach { threadBookmark ->
                bookmarks[threadBookmark.threadDescriptor] = threadBookmark
                orders.add(threadBookmark.threadDescriptor)
              }
            }

            suspendableInitializer.initWithValue(Unit)

            Logger.d(TAG, "BookmarksManager initialized! Loaded ${bookmarks.size} total " +
              "bookmarks and ${activeBookmarksCount()} active bookmarks")
          }
          is ModularResult.Error -> {
            Logger.e(TAG, "Exception while initializing BookmarksManager", bookmarksResult.error)
            suspendableInitializer.initWithError(bookmarksResult.error)
          }
        }

        bookmarksChanged(BookmarkChange.BookmarksInitialized)
      }
    }
  }

  fun listenForBookmarksChanges(): Flowable<BookmarkChange> {
    return bookmarksChangedSubject
      .onBackpressureLatest()
      .observeOn(AndroidSchedulers.mainThread())
      .doOnError { error -> Logger.e(TAG, "listenForBookmarksChanges error", error) }
      .hide()
  }

  fun listenForFetchEventsFromActiveThreads(): Flowable<ChanDescriptor.ThreadDescriptor> {
    return threadIsFetchingEventsSubject
      .onBackpressureLatest()
      .observeOn(AndroidSchedulers.mainThread())
      .doOnError { error -> Logger.e(TAG, "listenForFetchEventsFromActiveThreads error", error) }
      .hide()
  }

  @OptIn(ExperimentalTime::class)
  suspend fun awaitUntilInitialized() {
    if (isReady()) {
      return
    }

    Logger.d(TAG, "BookmarksManager is not ready yet, waiting...")
    val duration = measureTime { suspendableInitializer.awaitUntilInitialized() }
    Logger.d(TAG, "BookmarksManager initialization completed, took $duration")
  }

  @OptIn(ExperimentalTime::class)
  fun awaitUntilInitializedBlocking() {
    if (isReady()) {
      return
    }

    Logger.d(TAG, "BookmarksManager (blocking) is not ready yet, waiting...")
    val duration = measureTime { suspendableInitializer.awaitUntilInitializedBlocking() }
    Logger.d(TAG, "BookmarksManager (blocking) initialization completed, took $duration")
  }

  fun isReady() = suspendableInitializer.isInitialized()

  fun exists(threadDescriptor: ChanDescriptor.ThreadDescriptor): Boolean {
    return lock.read { bookmarks.containsKey(threadDescriptor) }
  }

  fun setCurrentOpenThreadDescriptor(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    currentOpenThread.set(threadDescriptor)
  }

  fun currentlyOpenedThread(): ChanDescriptor.ThreadDescriptor? = currentOpenThread.get()

  fun onThreadIsFetchingData(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    if (threadDescriptor == currentlyOpenedThread()) {
      val isActive = lock.read { bookmarks[threadDescriptor]?.isActive() ?: false }
      if (isActive) {
        threadIsFetchingEventsSubject.onNext(threadDescriptor)
      }
    }
  }

  suspend fun createBookmark(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    title: String? = null,
    thumbnailUrl: HttpUrl? = null,
    persist: Boolean = false
  ): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.write {
      if (bookmarks.containsKey(threadDescriptor)) {
        return@write false
      }

      val threadBookmark = ThreadBookmark.create(threadDescriptor).apply {
        this.title = title
        this.thumbnailUrl = thumbnailUrl
      }

      if (isDevFlavor) {
        check(!orders.contains(threadDescriptor)) { "orders already contains $threadDescriptor" }
      }

      orders.add(0, threadDescriptor)
      bookmarks[threadDescriptor] = threadBookmark
      ensureBookmarksAndOrdersConsistency()

      if (persist) {
        persistBookmarksInternal()
      }

      bookmarksChangedSubject.onNext(BookmarkChange.BookmarksCreated(listOf(threadDescriptor)))

      Logger.d(TAG, "Bookmark created ($threadDescriptor)")
      return@write true
    }
  }

  @JvmOverloads
  fun createBookmark(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    title: String? = null,
    thumbnailUrl: HttpUrl? = null
  ): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.write {
      if (bookmarks.containsKey(threadDescriptor)) {
        return@write false
      }

      val threadBookmark = ThreadBookmark.create(threadDescriptor).apply {
        this.title = title
        this.thumbnailUrl = thumbnailUrl
      }

      if (isDevFlavor) {
        check(!orders.contains(threadDescriptor)) { "orders already contains $threadDescriptor" }
      }

      orders.add(0, threadDescriptor)
      bookmarks[threadDescriptor] = threadBookmark
      ensureBookmarksAndOrdersConsistency()

      bookmarksChanged(BookmarkChange.BookmarksCreated(listOf(threadDescriptor)))
      Logger.d(TAG, "Bookmark created ($threadDescriptor)")

      return@write true
    }
  }

  fun deleteBookmark(threadDescriptor: ChanDescriptor.ThreadDescriptor): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.write {
      if (!bookmarks.containsKey(threadDescriptor)) {
        ensureBookmarksAndOrdersConsistency()
        return@write false
      }

      bookmarks.remove(threadDescriptor)
      orders.remove(threadDescriptor)
      ensureBookmarksAndOrdersConsistency()

      bookmarksChanged(BookmarkChange.BookmarksDeleted(listOf(threadDescriptor)))
      Logger.d(TAG, "Bookmark deleted ($threadDescriptor)")

      return@write true
    }
  }

  fun updateBookmark(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    notifyListenersOption: NotifyListenersOption,
    mutator: (ThreadBookmark) -> Unit
  ) {
    updateBookmarks(listOf(threadDescriptor), notifyListenersOption, mutator)
  }

  fun updateBookmarks(
    threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>,
    notifyListenersOption: NotifyListenersOption,
    mutator: (ThreadBookmark) -> Unit
  ) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    if (threadDescriptors.isEmpty()) {
      return
    }

    return lock.write {
      var updated = false

      threadDescriptors.forEach { threadDescriptor ->
        val oldThreadBookmark = bookmarks[threadDescriptor]
          ?: return@forEach

        ensureContainsOrder(threadDescriptor)

        val mutatedBookmark = oldThreadBookmark.deepCopy()
        mutator(mutatedBookmark)

        if (oldThreadBookmark != mutatedBookmark) {
          bookmarks[threadDescriptor] = mutatedBookmark
          updated = true
        }
      }

      if (!updated) {
        return@write
      }

      if (notifyListenersOption != NotifyListenersOption.DoNotNotify) {
        if (notifyListenersOption == NotifyListenersOption.NotifyEager) {
          bookmarksChanged(BookmarkChange.BookmarksUpdated(threadDescriptors))
        } else {
          delayedBookmarksChanged(BookmarkChange.BookmarksUpdated(threadDescriptors))
        }
      }
    }
  }

  fun pruneNonActive() {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.write {
      val toDelete = mutableListWithCap<ChanDescriptor.ThreadDescriptor>(bookmarks.size / 2)

      bookmarks.entries.forEach { (threadDescriptor, threadBookmark) ->
        if (!threadBookmark.isActive()) {
          toDelete += threadDescriptor
        }
      }

      ensureBookmarksAndOrdersConsistency()

      if (toDelete.size > 0) {
        toDelete.forEach { threadDescriptor ->
          bookmarks.remove(threadDescriptor)
          orders.remove(threadDescriptor)
        }
      }

      bookmarksChanged(BookmarkChange.BookmarksDeleted(toDelete))
    }
  }

  fun deleteAllBookmarks() {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.write {
      val allBookmarksDescriptors = bookmarks.keys.toList()

      bookmarks.clear()
      orders.clear()

      bookmarksChanged(BookmarkChange.BookmarksDeleted(allBookmarksDescriptors))
    }
  }

  fun readPostsAndNotificationsForThread(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.write {
      bookmarks[threadDescriptor]?.readAllPostsAndNotifications()
      ensureBookmarksAndOrdersConsistency()

      bookmarksChanged(BookmarkChange.BookmarksUpdated(listOf(threadDescriptor)))
    }
  }

  fun readAllPostsAndNotifications() {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.write {
      bookmarks.entries.forEach { (_, threadBookmark) ->
        threadBookmark.readAllPostsAndNotifications()
      }
      ensureBookmarksAndOrdersConsistency()

      bookmarksChanged(BookmarkChange.BookmarksUpdated(bookmarks.keys))
    }
  }

  fun viewBookmark(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    viewer: (ThreadBookmarkView) -> Unit
  ) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.read {
      if (!bookmarks.containsKey(threadDescriptor)) {
        ensureNotContainsOrder(threadDescriptor)
        return@read
      }

      ensureContainsOrder(threadDescriptor)

      val threadBookmark = bookmarks[threadDescriptor]
        ?: return@read

      viewer(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
    }
  }

  fun <T> mapBookmark(threadDescriptor: ChanDescriptor.ThreadDescriptor, mapper: (ThreadBookmarkView) -> T): T? {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      if (!bookmarks.containsKey(threadDescriptor)) {
        ensureNotContainsOrder(threadDescriptor)
        return@read null
      }

      ensureContainsOrder(threadDescriptor)

      val threadBookmark = bookmarks[threadDescriptor]
        ?: return@read null

      return@read mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
    }
  }

  fun iterateBookmarksOrderedWhile(viewer: (ThreadBookmarkView) -> Boolean) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.read {
      for (threadDescriptor in orders) {
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor} even though orders does"
        }

        if (!viewer(ThreadBookmarkView.fromThreadBookmark(threadBookmark))) {
          break
        }
      }
    }
  }

  fun <T> mapBookmarks(
    threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>,
    mapper: (ThreadBookmarkView) -> T
  ): List<T> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read threadDescriptors.map { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor} even though orders does"
        }

        return@map mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun <T> mapBookmarksOrdered(mapper: (ThreadBookmarkView) -> T): List<T> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read orders.map { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor} even though orders does"
        }

        return@map mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun <T : Any> mapNotNullBookmarksOrdered(mapper: (ThreadBookmarkView) -> T?): List<T> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read orders.mapNotNull { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor} even though orders does"
        }

        return@mapNotNull mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun onBookmarkMoved(from: Int, to: Int) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    require(from >= 0) { "Bad from: $from" }
    require(to >= 0) { "Bad to: $to" }

    lock.write {
      orders.add(to, orders.removeAt(from))
      bookmarksChanged(BookmarkChange.BookmarksUpdated(null))

      Logger.d(TAG, "Bookmark moved (from=$from, to=$to)")
    }
  }

  fun bookmarksCount(): Int {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      ensureBookmarksAndOrdersConsistency()

      return@read bookmarks.size
    }
  }

  fun activeBookmarksCount(): Int {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      ensureBookmarksAndOrdersConsistency()

      return@read bookmarks.values.count { threadBookmark ->
        val siteDescriptor = threadBookmark.threadDescriptor.siteDescriptor()
        val isArchiveBookmark = archivesManager.isSiteArchive(siteDescriptor)

        return@count !isArchiveBookmark && threadBookmark.isActive()
      }
    }
  }

  fun hasActiveBookmarks(): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.any { (_, threadBookmark) ->
        val siteDescriptor = threadBookmark.threadDescriptor.siteDescriptor()
        val isArchiveBookmark = archivesManager.isSiteArchive(siteDescriptor)

        return@any !isArchiveBookmark && threadBookmark.isActive()
      }
    }
  }

  fun getTotalUnseenPostsCount(): Int {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      ensureBookmarksAndOrdersConsistency()

      return@read bookmarks.values.sumBy { threadBookmark -> threadBookmark.unseenPostsCount() }
    }
  }

  fun hasUnreadReplies(): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      ensureBookmarksAndOrdersConsistency()

      return@read bookmarks.values.any { threadBookmark -> threadBookmark.hasUnreadReplies() }
    }
  }

  fun onPostViewed(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    postNo: Long,
    currentPostIndex: Int,
    realPostIndex: Int
  ) {
    if (!isReady()) {
      return
    }

    val lastViewedPostNo = lock.read {
      if (!bookmarks.containsKey(threadDescriptor)) {
        return
      }

      return@read bookmarks[threadDescriptor]?.lastViewedPostNo ?: 0L
    }

    if (postNo <= lastViewedPostNo) {
      return
    }

    updateBookmark(threadDescriptor, NotifyListenersOption.NotifyDelayed) { threadBookmark ->
      threadBookmark.updateSeenPostCount(realPostIndex)
      threadBookmark.updateLastViewedPostNo(postNo)
      threadBookmark.readRepliesUpTo(postNo)
    }
  }

  private fun ensureBookmarksAndOrdersConsistency() {
    if (isDevFlavor) {
      check(bookmarks.size == orders.size) {
        "Inconsistency detected! bookmarks.size (${bookmarks.size}) != orders.size (${orders.size})"
      }
    }
  }

  private fun ensureNotContainsOrder(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    if (isDevFlavor) {
      check(!orders.contains(threadDescriptor)) {
        "Orders contains ($threadDescriptor) when bookmarks doesn't!"
      }
    }
  }

  private fun ensureContainsOrder(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    if (isDevFlavor) {
      check(orders.contains(threadDescriptor)) {
        "Orders does not contain ($threadDescriptor) when bookmarks does!"
      }
    }
  }

  private fun bookmarksChanged(bookmarkChange: BookmarkChange) {
    if (isDevFlavor) {
      ensureBookmarksAndOrdersConsistency()
    }

    persistTaskSubject.onNext(Unit)
    bookmarksChangedSubject.onNext(bookmarkChange)
  }

  private fun delayedBookmarksChanged(bookmarkChange: BookmarkChange) {
    if (isDevFlavor) {
      ensureBookmarksAndOrdersConsistency()
    }

    delayedBookmarksChangedSubject.onNext(bookmarkChange)
  }

  private fun persistBookmarks(blocking: Boolean = false) {
    BackgroundUtils.ensureMainThread()

    if (!isReady()) {
      return
    }

    if (!persistRunning.compareAndSet(false, true)) {
      return
    }

    if (blocking) {
      runBlocking {
        Logger.d(TAG, "persistBookmarks blocking called")
        persistBookmarksInternal()
        Logger.d(TAG, "persistBookmarks blocking finished")
      }
    } else {
      appScope.launch {
        Logger.d(TAG, "persistBookmarks async called")
        persistBookmarksInternal()
        Logger.d(TAG, "persistBookmarks async finished")
      }
    }
  }

  private suspend fun persistBookmarksInternal() {

    try {
      bookmarksRepository.persist(getBookmarksOrdered()).safeUnwrap { error ->
        Logger.e(TAG, "Failed to persist bookmarks", error)
        return
      }
    } finally {
      persistRunning.set(false)
    }
  }

  private fun getBookmarksOrdered(): List<ThreadBookmark> {
    return lock.read {
      return@read orders.map { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor} even though orders does"
        }

        return@map threadBookmark.deepCopy()
      }
    }
  }

  enum class NotifyListenersOption {
    DoNotNotify,
    NotifyEager,
    // Be very careful when using this option since it's using a debouncer with 1 second delay.
    // So you may end up with a race condition. Always prefer NotifyEager. Right this options is only
    // used in one place where it's really useful.
    NotifyDelayed
  }

  @DoNotStrip
  sealed class BookmarkChange {
    object BookmarksInitialized : BookmarkChange()
    class BookmarksCreated(val threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>) : BookmarkChange()
    class BookmarksDeleted(val threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>) : BookmarkChange()
    class BookmarksUpdated(val threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>?) : BookmarkChange()

    fun threadDescriptors(): Collection<ChanDescriptor.ThreadDescriptor> {
      return when (this) {
        BookmarksInitialized -> emptyList()
        is BookmarksCreated -> threadDescriptors
        is BookmarksDeleted -> threadDescriptors
        is BookmarksUpdated -> threadDescriptors ?: emptyList()
      }
    }

    fun threadDescriptorsOrNull(): Collection<ChanDescriptor.ThreadDescriptor>? {
      return when (this) {
        BookmarksInitialized -> null
        is BookmarksCreated -> threadDescriptors
        is BookmarksDeleted -> threadDescriptors
        is BookmarksUpdated -> threadDescriptors
      }
    }

  }

  companion object {
    private const val TAG = "BookmarksManager"
  }
}