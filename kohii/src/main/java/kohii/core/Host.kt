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

package kohii.core

import android.os.Build
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import kohii.findCoordinatorLayoutDirectChildContainer
import kohii.media.VolumeInfo
import kotlin.LazyThreadSafetyMode.NONE
import kotlin.properties.Delegates

abstract class Host constructor(
  val manager: Manager,
  open val root: View
) : OnAttachStateChangeListener, OnLayoutChangeListener {

  companion object {
    const val VERTICAL = RecyclerView.VERTICAL
    const val HORIZONTAL = RecyclerView.HORIZONTAL
    const val BOTH_AXIS = -1
    const val NONE_AXIS = -2

    val comparators = mapOf(
        HORIZONTAL to Playback.HORIZONTAL_COMPARATOR,
        VERTICAL to Playback.VERTICAL_COMPARATOR,
        BOTH_AXIS to Playback.BOTH_AXIS_COMPARATOR,
        NONE_AXIS to Playback.BOTH_AXIS_COMPARATOR
    )

    @JvmStatic
    internal operator fun get(
      manager: Manager,
      root: View
    ): Host {
      return when (root) {
        is RecyclerView -> RecyclerViewHost(manager, root)
        is NestedScrollView -> NestedScrollViewHost(manager, root)
        is ViewPager2 -> ViewPager2Host(manager, root)
        is ViewPager -> ViewPagerHost(manager, root)
        is ViewGroup -> {
          if (Build.VERSION.SDK_INT >= 23)
            ViewGroupV23Host(manager, root)
          else
            ViewGroupHost(manager, root)
        }
        else -> throw IllegalArgumentException("Unsupported: $root")
      }
    }
  }

  private val containers = mutableSetOf<Any>()

  // The direct child of CoordinatorLayout that is an ancestor of this root if exist.
  private val rootContainer: CoordinatorLayout? by lazy(NONE) {
    val found = findCoordinatorLayoutDirectChildContainer(
        manager.group.activity.window.peekDecorView(), root
    )
    return@lazy if (found is CoordinatorLayout) found else null
  }

  abstract fun accepts(container: ViewGroup): Boolean

  abstract fun allowToPlay(playback: Playback): Boolean

  abstract fun selectToPlay(candidates: Collection<Playback>): Collection<Playback>

  @CallSuper
  open fun addContainer(container: ViewGroup) {
    if (containers.add(container)) {
      if (ViewCompat.isAttachedToWindow(container)) {
        this.onViewAttachedToWindow(container)
      }
      container.addOnAttachStateChangeListener(this)
    }
  }

  @CallSuper
  open fun removeContainer(container: ViewGroup) {
    if (containers.remove(container)) {
      container.removeOnAttachStateChangeListener(this)
      container.removeOnLayoutChangeListener(this)
    }
  }

  @CallSuper
  override fun onViewDetachedFromWindow(v: View?) {
    manager.onContainerDetachedFromWindow(v)
  }

  @CallSuper
  override fun onViewAttachedToWindow(v: View?) {
    manager.onContainerAttachedToWindow(v)
  }

  @CallSuper
  override fun onLayoutChange(
    v: View?,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    oldLeft: Int,
    oldTop: Int,
    oldRight: Int,
    oldBottom: Int
  ) {
    if (v != null && (left != oldLeft || right != oldRight || top != oldTop || bottom != oldBottom)) {
      manager.onContainerLayoutChanged(v)
    }
  }

  @CallSuper
  open fun onAdded() {
    val containerParam = rootContainer?.layoutParams
    if (containerParam is CoordinatorLayout.LayoutParams) {
      root.doOnAttach {
        if (containerParam.behavior is ScrollingViewBehavior) {
          val behaviorWrapper = BehaviorWrapper(containerParam.behavior!!, manager)
          containerParam.behavior = behaviorWrapper
        }
      }

      root.doOnDetach {
        if (containerParam.behavior is BehaviorWrapper) {
          (containerParam.behavior as BehaviorWrapper).onDetach()
        }
      }
    }
  }

  @CallSuper
  open fun onRemoved() {
    mutableListOf(containers).onEach {
      manager.onRemoveContainer(it)
    }
        .clear()
  }

  internal var volumeInfoUpdater: VolumeInfo by Delegates.observable(
      initialValue = VolumeInfo(),
      onChange = { _, from, to ->
        if (from == to) return@observable
        manager.updateHostVolumeInfo(this, to)
      }
  )

  internal val volumeInfo: VolumeInfo
    get() = volumeInfoUpdater

  init {
    volumeInfoUpdater = manager.volumeInfo
  }

  // This operation should be considered heavy/expensive.
  protected fun selectByOrientation(
    candidates: Collection<Playback>,
    orientation: Int
  ): Collection<Playback> {
    val comparator = comparators.getValue(orientation)

    val grouped = candidates.sortedWith(comparator)
        .groupBy { it.config.controller != null }
        .withDefault { emptyList() }

    val manualCandidates = with(grouped.getValue(true)) {
      val started = asSequence()
          .find {
            manager.master.playablesPendingStates[it.tag] == Common.PENDING_PLAY ||
                // Started by client.
                manager.master.playablesStartedByClient.contains(it.tag)
          }
      return@with listOfNotNull(started ?: this@with.firstOrNull())
    }

    val automaticCandidates by lazy(NONE) {
      listOfNotNull(grouped.getValue(false).firstOrNull())
    }

    return if (manualCandidates.isNotEmpty()) manualCandidates else automaticCandidates
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as Host
    if (manager !== other.manager) return false
    if (root !== other.root) return false
    return true
  }

  private val lazyHashCode by lazy(NONE) {
    val result = manager.hashCode()
    31 * result + root.hashCode()
  }

  override fun hashCode(): Int {
    return lazyHashCode
  }
}
