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

package kohii.v1.internal

import android.os.Build.VERSION_CODES
import android.view.View
import android.view.View.OnScrollChangeListener
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import kohii.v1.core.Manager

@RequiresApi(VERSION_CODES.M)
internal class ViewGroupV23Bucket(
  manager: Manager,
  root: ViewGroup
) : ViewGroupBucket(manager, root), OnScrollChangeListener {

  override fun onScrollChange(
    v: View?,
    scrollX: Int,
    scrollY: Int,
    oldScrollX: Int,
    oldScrollY: Int
  ) {
    manager.refresh()
  }

  override fun onAddedInternal() {
    root.setOnScrollChangeListener(this)
  }

  override fun onRemovedInternal() {
    root.setOnScrollChangeListener(null as OnScrollChangeListener?)
  }
}
