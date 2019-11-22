/*
 * Copyright (c) 2019 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.v1.core

import android.view.View
import android.view.ViewGroup
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP
import androidx.collection.ArraySet
import androidx.core.view.ViewCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.lifecycle.Lifecycle.Event.ON_ANY
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.Lifecycle.Event.ON_START
import androidx.lifecycle.Lifecycle.Event.ON_STOP
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import kohii.v1.core.MemoryMode.LOW
import kohii.v1.core.Scope.BUCKET
import kohii.v1.core.Scope.GLOBAL
import kohii.v1.core.Scope.GROUP
import kohii.v1.core.Scope.MANAGER
import kohii.v1.core.Scope.PLAYBACK
import kohii.v1.media.VolumeInfo
import kohii.v1.partitionToMutableSets
import java.util.ArrayDeque
import kotlin.properties.Delegates

class Manager(
  internal val master: Master,
  internal val group: Group,
  internal val host: Any,
  internal val lifecycleOwner: LifecycleOwner,
  internal val memoryMode: MemoryMode = LOW
) : PlayableManager, LifecycleObserver, Comparable<Manager> {

  companion object {
    internal fun compareAndCheck(
      left: Prioritized,
      right: Prioritized
    ): Int {
      val ltr = left.compareTo(right)
      val rtl = right.compareTo(left)
      check(ltr + rtl == 0) {
        "Sum of comparison result of 2 directions must be 0, get ${ltr + rtl}."
      }

      return ltr
    }
  }

  internal var lock: Boolean = group.lock
    set(value) {
      if (field == value) return
      field = value
      buckets.forEach { it.lock = value }
      refresh()
    }

  // Use as both Queue and Stack.
  // - When adding new Bucket, we add it to tail of the Queue.
  // - When promoting a Bucket as sticky, we push it to head of the Queue.
  // - When demoting a Bucket from sticky, we just poll the head.
  internal val buckets = ArrayDeque<Bucket>(4 /* less than default minimum of ArrayDeque */)
  // Up to one Bucket can be sticky at a time.
  private var stickyBucket by Delegates.observable<Bucket?>(
      initialValue = null,
      onChange = { _, from, to ->
        if (from === to) return@observable
        // Move 'to' from buckets.
        if (to != null /* set new sticky Bucket */) {
          buckets.push(to) // Push it to head.
        } else { // 'to' is null then 'from' must be nonnull. Consider to remove it from head.
          if (buckets.peek() === from) buckets.pop()
        }
      }
  )

  internal val playbacks = mutableMapOf<Any /* container */, Playback>()

  internal var sticky: Boolean = false

  internal var volumeInfoUpdater: VolumeInfo by Delegates.observable(
      initialValue = VolumeInfo(),
      onChange = { _, from, to ->
        if (from == to) return@observable
        // Update VolumeInfo of all Buckets. This operation will then callback to this #applyVolumeInfo
        buckets.forEach { it.volumeInfoUpdater = to }
      }
  )

  internal val volumeInfo: VolumeInfo
    get() = volumeInfoUpdater

  init {
    volumeInfoUpdater = group.volumeInfo
    group.onManagerCreated(this)
    lifecycleOwner.lifecycle.addObserver(this)
  }

  override fun compareTo(other: Manager): Int {
    return if (other.host !is Prioritized) {
      if (this.host is Prioritized) 1 else 0
    } else {
      if (this.host is Prioritized) {
        compareAndCheck(this.host, other.host)
      } else -1
    }
  }

  @OnLifecycleEvent(ON_ANY)
  internal fun onAnyEvent(owner: LifecycleOwner) {
    playbacks.forEach { it.value.lifecycleState = owner.lifecycle.currentState }
  }

  @OnLifecycleEvent(ON_CREATE)
  internal fun onCreate() {
  }

  @OnLifecycleEvent(ON_DESTROY)
  internal fun onDestroy(owner: LifecycleOwner) {
    playbacks.values.toMutableList()
        .also { group.organizer.selection -= it }
        .onEach { removePlayback(it) /* also modify 'playbacks' content */ }
        .clear()
    stickyBucket = null // will pop current sticky Bucket from the Stack
    buckets.toMutableList()
        .onEach { removeBucket(it.root) }
        .clear()
    owner.lifecycle.removeObserver(this)
    group.onManagerDestroyed(this)
  }

  @OnLifecycleEvent(ON_START)
  internal fun onStart() {
    refresh() // This will also update active/inactive Playbacks accordingly.
  }

  @OnLifecycleEvent(ON_STOP)
  internal fun onStop() {
    playbacks.forEach { if (it.value.isActive) onPlaybackInActive(it.value) }
    refresh()
  }

  @RestrictTo(LIBRARY_GROUP)
  fun findPlayableForContainer(container: ViewGroup): Playable? {
    return playbacks[container]?.playable
  }

  internal fun findBucketForContainer(container: ViewGroup): Bucket? {
    require(ViewCompat.isAttachedToWindow(container))
    return buckets.find { it.accepts(container) }
  }

  internal fun onContainerAttachedToWindow(container: Any?) {
    val playback = playbacks[container]
    if (playback != null) {
      onPlaybackAttached(playback)
      onPlaybackActive(playback)
      refresh()
    }
  }

  internal fun onContainerDetachedFromWindow(container: Any?) {
    // A detached Container can be re-attached later (in case of RecyclerView)
    val playback = playbacks[container]
    if (playback != null) {
      if (playback.isAttached) {
        if (playback.isActive) onPlaybackInActive(playback)
        onPlaybackDetached(playback)
      }
      refresh()
    }
  }

  internal fun onContainerLayoutChanged(container: Any?) {
    val playback = playbacks[container]
    if (playback != null) refresh()
  }

  private fun addBucket(view: View) {
    val existing = buckets.find { it.root === view }
    if (existing != null) return
    val bucket = Bucket[this@Manager, view]
    if (buckets.add(bucket)) {
      bucket.onAdded()
      view.doOnAttach { v ->
        bucket.onAttached()
        v.doOnDetach {
          detachBucket(v)
        } // In case the View is detached immediately ...
      }
    }
  }

  private fun detachBucket(view: View) {
    buckets.firstOrNull { it.root === view }
        ?.onDetached()
  }

  private fun removeBucket(view: View) {
    buckets.firstOrNull { it.root === view && buckets.remove(it) }
        ?.onRemoved()
  }

  internal fun refresh() {
    group.onRefresh()
  }

  private fun refreshPlaybackStates(): Pair<MutableSet<Playback> /* Active */, MutableSet<Playback> /* InActive */> {
    val toActive = playbacks.filterValues { !it.isActive && it.token.shouldPrepare() }
        .values
    val toInActive = playbacks.filterValues { it.isActive && !it.token.shouldPrepare() }
        .values

    toActive.forEach { onPlaybackActive(it) }
    toInActive.forEach { onPlaybackInActive(it) }

    return playbacks.entries.filter { it.value.isAttached }
        .partitionToMutableSets(
            predicate = { it.value.isActive },
            transform = { it.value }
        )
  }

  internal fun splitPlaybacks(): Pair<Set<Playback> /* toPlay */, Set<Playback> /* toPause */> {
    val (activePlaybacks, inactivePlaybacks) = refreshPlaybackStates()
    val toPlay = ArraySet<Playback>()

    val bucketToPlaybacks = playbacks.values.groupBy { it.bucket } // -> Map<Bucket, List<Playback>
    buckets.asSequence()
        .filter { !bucketToPlaybacks[it].isNullOrEmpty() }
        .map {
          val all = bucketToPlaybacks.getValue(it)
          val candidates = all.filter { playback -> it.allowToPlay(playback) }
          it to candidates
        }
        .map { (bucket, candidates) ->
          bucket.selectToPlay(candidates)
        }
        .find { it.isNotEmpty() }
        ?.also {
          toPlay.addAll(it)
          activePlaybacks.removeAll(it)
        }

    activePlaybacks.addAll(inactivePlaybacks)
    return if (lock) emptySet<Playback>() to (toPlay + activePlaybacks) else toPlay to activePlaybacks
  }

  internal fun addPlayback(playback: Playback) {
    val prev = playbacks.put(playback.container, playback)
    require(prev == null)
    playback.lifecycleState = lifecycleOwner.lifecycle.currentState
    playback.onAdded()
  }

  internal fun removePlayback(playback: Playback) {
    if (playbacks.remove(playback.container) === playback) {
      if (playback.isAttached) {
        if (playback.isActive) onPlaybackInActive(playback)
        onPlaybackDetached(playback)
      }
      playback.onRemoved()
      refresh()
    }
  }

  internal fun onRemoveContainer(container: Any) {
    playbacks[container]?.let { removePlayback(it) }
  }

  private fun onPlaybackAttached(playback: Playback) {
    playback.onAttached()
  }

  private fun onPlaybackDetached(playback: Playback) {
    playback.onDetached()
  }

  private fun onPlaybackActive(playback: Playback) {
    playback.onActive()
  }

  private fun onPlaybackInActive(playback: Playback) {
    playback.onInActive()
  }

  // Public APIs

  fun attach(vararg views: View): Manager {
    views.forEach { this.addBucket(it) }
    return this
  }

  fun detach(vararg views: View): Manager {
    views.forEach { this.removeBucket(it) }
    return this
  }

  internal fun stick(bucket: Bucket) {
    this.stickyBucket = bucket
  }

  // Null bucket --> unstick all current sticky buckets
  internal fun unstick(bucket: Bucket?) {
    if (bucket == null || this.stickyBucket === bucket) {
      this.stickyBucket = null
    }
  }

  internal fun updateBucketVolumeInfo(
    bucket: Bucket,
    volumeInfo: VolumeInfo
  ) {
    playbacks.forEach { if (it.value.bucket === bucket) it.value.volumeInfoUpdater = volumeInfo }
  }

  /**
   * Apply a specific [VolumeInfo] to all Playbacks in a [Scope].
   * - The smaller a scope's priority is, the wider applicable range it will be.
   * - Applying new [VolumeInfo] to smaller [Scope] will change [VolumeInfo] of Playbacks in that [Scope].
   * - If the [Scope] is from [Scope.BUCKET], any new [Playback] added to that [Bucket] will be configured
   * with the updated [VolumeInfo].
   *
   * @param target is the container to apply new [VolumeInfo] to. This must be set together with the [Scope].
   * For example, if client wants to apply the [VolumeInfo] to [Scope.PLAYBACK], the receiver must be the [Playback]
   * to apply to. If client wants to apply to [Scope.BUCKET], the receiver must be either the [Playback] inside that [Bucket],
   * or the root object of a [Bucket].
   */
  fun applyVolumeInfo(
    volumeInfo: VolumeInfo,
    target: Any,
    scope: Scope
  ) {
    when (scope) {
      PLAYBACK -> {
        require(target is Playback) { "Expected Playback, found ${target.javaClass.canonicalName}" }
        target.volumeInfoUpdater = volumeInfo
      }
      BUCKET -> {
        when (target) {
          is Bucket -> target.volumeInfoUpdater = volumeInfo
          is Playback -> target.bucket.volumeInfoUpdater = volumeInfo
          // If neither Playback nor Bucket, must be the root View of the Bucket.
          else -> {
            requireNotNull(buckets.find { it.root === target }) {
              "$target is not a root of any Bucket."
            }
                .volumeInfoUpdater = volumeInfo
          }
        }
      }
      MANAGER -> {
        this.volumeInfoUpdater = volumeInfo
      }
      GROUP -> {
        this.group.volumeInfoUpdater = volumeInfo
      }
      GLOBAL -> {
        this.master.groups.forEach { it.volumeInfoUpdater = volumeInfo }
      }
    }
  }

  fun play(playable: Playable) {
    master.play(playable)
  }

  fun pause(playable: Playable) {
    master.pause(playable)
  }

  interface OnSelectionListener {

    // Called when some Playbacks under this Manager are selected.
    fun onSelection(selection: Collection<Playback>)
  }
}
