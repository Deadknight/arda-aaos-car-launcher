/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.docklib.view

import android.view.View
import android.graphics.Rect
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.car.docklib.R

class DockView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val recyclerView: RecyclerView
    private val dockOrientation: Int

    init {
        dockOrientation = attrs?.getAttributeIntValue(
            "http://schemas.android.com/apk/res/android",
            "orientation",
            LinearLayoutManager.HORIZONTAL,
        ) ?: LinearLayoutManager.HORIZONTAL

        inflate(context, R.layout.dock_view, this)
        recyclerView = requireViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(
            context,
            if (dockOrientation == LinearLayoutManager.VERTICAL) {
                LinearLayoutManager.VERTICAL
            } else {
                LinearLayoutManager.HORIZONTAL
            },
            false,
        )

        // ARDA_DOCK_REAL_RECYCLERVIEW_SPACING_V1_3
        // Apply spacing on the actual RecyclerView so STATIC and DYNAMIC
        // items (including recent map + recent app) use the same gap.
        // Half-offsets produce 6dp BETWEEN neighboring items while
        // keeping the first/last outer edge unchanged.
        val ardaDockGapPx =
            (6f * resources.displayMetrics.density + 0.5f).toInt()

        recyclerView.addItemDecoration(
            object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State,
                ) {
                    outRect.set(0, 0, 0, 0)

                    val position = parent.getChildAdapterPosition(view)
                    if (position == RecyclerView.NO_POSITION) return

                    val itemCount = parent.adapter?.itemCount ?: return
                    if (itemCount <= 1) return

                    val before = ardaDockGapPx / 2
                    val after = ardaDockGapPx - before

                    if (dockOrientation == LinearLayoutManager.VERTICAL) {
                        outRect.top = if (position > 0) before else 0
                        outRect.bottom =
                            if (position < itemCount - 1) after else 0
                    } else {
                        outRect.left = if (position > 0) before else 0
                        outRect.right =
                            if (position < itemCount - 1) after else 0
                    }
                }
            },
        )
    }

    fun getAdapter() = recyclerView.adapter as DockAdapter

    fun setAdapter(adapter: DockAdapter) {
        recyclerView.adapter = adapter
    }
}
