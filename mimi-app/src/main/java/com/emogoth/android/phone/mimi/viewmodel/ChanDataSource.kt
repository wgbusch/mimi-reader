package com.emogoth.android.phone.mimi.viewmodel

import android.util.Log
import com.emogoth.android.phone.mimi.async.ProcessThreadTask
import com.emogoth.android.phone.mimi.db.ArchivedPostTableConnection
import com.emogoth.android.phone.mimi.db.HistoryTableConnection
import com.emogoth.android.phone.mimi.db.PostTableConnection
import com.emogoth.android.phone.mimi.db.UserPostTableConnection
import com.emogoth.android.phone.mimi.db.model.Archive
import com.emogoth.android.phone.mimi.db.model.History
import com.emogoth.android.phone.mimi.fourchan.FourChanConnector
import com.emogoth.android.phone.mimi.util.ArchivesManager
import com.emogoth.android.phone.mimi.util.HttpClientFactory
import com.emogoth.android.phone.mimi.util.MimiUtil
import com.mimireader.chanlib.ChanConnector
import com.mimireader.chanlib.models.*
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.rxkotlin.combineLatest
import io.reactivex.rxkotlin.zipWith
import io.reactivex.schedulers.Schedulers
import java.util.*
import kotlin.collections.ArrayList

open class ChanDataSource {
    private val TAG = ChanDataSource::class.java.simpleName

    var userPosts = ArrayList<Long>()
    private val chanConnector: ChanConnector = FourChanConnector.Builder()
            .setCacheDirectory(MimiUtil.getInstance().cacheDir)
            .setEndpoint(FourChanConnector.getDefaultEndpoint())
            .setPostEndpoint(FourChanConnector.getDefaultPostEndpoint())
            .setClient(HttpClientFactory.getInstance().client)
            .build<ChanConnector>()

    fun watchThread(boardName: String, threadId: Long): Flowable<Pair<History, ChanThread>> {
        return PostTableConnection.watchThread(threadId)
                .observeOn(Schedulers.io())
                .flatMap { Flowable.just(PostTableConnection.convertDbPostsToChanThread(boardName, threadId, it)) }
                .flatMap { chanThread ->
                    UserPostTableConnection.fetchPosts(chanThread.boardName, chanThread.threadId)
                            .flatMap { userPostList ->
                                Log.d(TAG, "user posts size: ${userPostList.size}")
                                val postIds = UserPostTableConnection.postIdList(userPostList)
                                userPosts.clear()
                                userPosts.addAll(postIds)
                                Single.just(ProcessThreadTask.processThread(chanThread.posts, postIds, boardName, threadId))
                            }
                            .toFlowable()

                }
                .combineLatest(ArchivedPostTableConnection.watchPosts(boardName, threadId))
                .flatMap { data ->
                    var chanThread = data.first
                    val archivedPosts = data.second

                    Log.d(TAG, "Thread contains ${chanThread.posts.size} posts from the database with ${archivedPosts.size} archive augmentations")

                    if (archivedPosts.size > 0) {
                        chanThread = ArchivedChanThread(boardName, threadId, ArrayList(), archivedPosts[0].archiveName, archivedPosts[0].archiveDomain)
                        for (archivedPost in archivedPosts) {
                            val item = data.first.posts.find { it.no == archivedPost.postId }
                                    ?: ChanPost()

                            if (!item.empty()) {
                                val post = ArchivedChanPost(item, archivedPost.mediaLink, archivedPost.thumbLink)
                                chanThread.posts.add(post)
                            }
                        }
                    }

                    Flowable.just(chanThread)
                }
                .flatMap {
                    HistoryTableConnection.fetchPost(boardName, threadId)
                            .zipWith(Single.just(it)).toFlowable()
                }
                .observeOn(Schedulers.io())
    }

    fun fetchThread(boardName: String, threadId: Long, size: Int = 0): Single<ChanThread> {
        return chanConnector.fetchThread(boardName, threadId, ChanConnector.CACHE_DEFAULT)
                .observeOn(Schedulers.io())
                .onErrorResumeNext { throwable: Throwable -> fetchArchivesOrError(boardName, threadId, throwable) }
                .doOnNext {
                    when {
                        it is ErrorChanThread -> {
                            Log.d(TAG, "Error fetching thread: ${it.error.localizedMessage}", it.error)
                        }
                        it.posts.size > size -> {
                            if (it is ArchivedChanThread) {
                                Log.d(TAG, "Thread has been archived")
                            } else {
                                Log.d(TAG, "Loading active thread from 4chan")
                            }
                            Log.d(TAG, "Putting thread into the database")
                            PostTableConnection.putThread(it)
                        }
                    }
                }
                .single(ChanThread.empty())
    }

    private fun fetchArchivesOrError(boardName: String, threadId: Long, throwable: Throwable): Flowable<ChanThread> {
        if (throwable !is retrofit2.adapter.rxjava2.HttpException || throwable.code() != 404) {
            Log.e(TAG, "An exception occurred while trying to fetch thread [/$boardName/$threadId]")

            return PostTableConnection.fetchThread(threadId)
                    .map(PostTableConnection.mapDbPostsToChanThread(boardName, threadId))
                    .map {
                        ErrorChanThread(it, throwable) as ChanThread
                    }
                    .toFlowable()
        } else {

            val archivesManager = ArchivesManager(this)
            return archivesManager.thread(boardName, threadId)
                    // Removing the onErrorReturn() and the doOnError() calls may make the system more robust
                    // by passing exceptions through to the containing Rx stream which should turn it into
                    // an ErrorChanThread object by itself.
                    .onErrorReturn {
                        Log.e(TAG, "Could not fetch archive for /$boardName/$threadId", it)
                        ErrorChanThread(ChanThread(boardName, threadId, Collections.emptyList()), it)
                    }
                    .zipWith(ArchivedPostTableConnection.removeThread(boardName, threadId))
                    .flatMap { (a, _) ->
                        Single.just(a)
                    }
                    .flatMap {
                        if (it is ErrorChanThread) {
                            Log.w(TAG, "Passing error through")
                        } else {
                            ArchivedPostTableConnection.putThread(it as ArchivedChanThread)
                        }

                        Single.just(it)
                    }
                    .doOnError { Single.just(ErrorChanThread(ChanThread(boardName, threadId, Collections.emptyList()), it)) }
                    .toFlowable()
        }
    }

    fun updateHistoryLastRead(boardName: String, threadId: Long, lastReadPosition: Int, unreadCount: Int): Single<Boolean> {
        return HistoryTableConnection.fetchPost(boardName, threadId)
                .flatMap {
                    it.lastReadPosition = lastReadPosition
                    it.unreadCount = if (unreadCount < 0) {
                        0
                    } else {
                        unreadCount
                    }
                    Single.just(it)
                }
                .flatMap {
                    HistoryTableConnection.updateHistory(it)
                }
    }

    fun updateHistoryBookmark(boardName: String, threadId: Long, bookmarked: Boolean): Single<Boolean> {
        return HistoryTableConnection.fetchPost(boardName, threadId)
                .flatMap {
                    if (bookmarked) {
                        it.watched = 1
                    } else {
                        it.watched = 0
                    }

                    Single.just(it)
                }
                .flatMap {
                    HistoryTableConnection.updateHistory(it)
                }
    }

    fun fetchCatalog(boardName: String): Single<ChanCatalog> {
        return chanConnector.fetchCatalog(boardName)
    }

    fun fetchArchivedThread(boardName: String, threadId: Long, archive: Archive): Single<ArchivedChanThread> {
        val protocol = if (archive.https) "https" else "http"
        val domain = archive.domain
        val name = archive.name
        val url = String.format("%s://%s/_/api/chan/thread/?board=%s&num=%d", protocol, domain, boardName, threadId)
        Log.v(TAG, "Archive URL: $url")
        return chanConnector.fetchArchivedThread(boardName, threadId, name, domain, url)
    }

}