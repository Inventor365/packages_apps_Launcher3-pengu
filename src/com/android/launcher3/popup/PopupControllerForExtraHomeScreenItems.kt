/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.popup

import android.content.Context
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.AppWidgetResizeFrame
import com.android.launcher3.R
import com.android.launcher3.dragndrop.LauncherDragController
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.widget.LauncherAppWidgetHostView

/**
 * Controller for home screen items: folders, app pairs, widgets, and file system based items. This
 * controller does not handle apps or app shortcuts. This controller handles actions for the popups
 * such as showing and dismissing them.
 */
class PopupControllerForExtraHomeScreenItems<T>(
    private val popupDataRepository: PopupDataRepository,
    private val dragController: LauncherDragController,
) : PopupController<T> where T : Context, T : ActivityContext {
    override fun show(view: View): Popup {
        val container: PopupContainer<T>
        val activityContext: T = ActivityContext.lookupContext(view.context) as T
        val itemInfo = view.tag as ItemInfo
        try {
            Trace.beginSection("showPopupMenu")
            container =
                PopupContainer.create(
                    context = view.context,
                    originalView = view,
                    itemInfo = itemInfo,
                )
            dragController.addDragListener(container)
            val bigFolder = view is FolderIcon && itemInfo is FolderInfo &&
                itemInfo.spanX == 2 && itemInfo.spanY == 2
            popupDataRepository.getAllSupportedPopupActions(itemInfo)?.let {
                container.showForSystemShortcuts(
                    it,
                    activityContext,
                    view,
                    beforeShortcuts =
                        if (bigFolder) {
                            { rows ->
                                addBigFolderStyleSelector(
                                    container,
                                    rows,
                                    itemInfo as FolderInfo,
                                    view as FolderIcon,
                                )
                            }
                        } else null,
                )
            }
            showResizeFrameIfNeeded(activityContext, itemInfo, view)
        } finally {
            logEvent(activityContext.statsLogManager, itemInfo.itemType, PopupEvent.OPEN)
            Trace.endSection()
        }
        return container
    }

    private fun showResizeFrameIfNeeded(
        activityContext: ActivityContext,
        itemInfo: ItemInfo,
        view: View,
    ) {
        val cellLayout = activityContext.getCellLayout(itemInfo.container, itemInfo.screenId)
        val resizeStrategy = DefaultPopupResizeStrategy()
        if (resizeStrategy.shouldShowResizeFrame(itemInfo, view, cellLayout)) {
            when (view) {
                is FolderIcon -> AppWidgetResizeFrame.showForFolder(view, cellLayout)

                is LauncherAppWidgetHostView -> AppWidgetResizeFrame.showForWidget(view, cellLayout)
            }
        }
    }

    private fun addBigFolderStyleSelector(
        popup: PopupContainer<T>,
        rows: ViewGroup,
        folderInfo: FolderInfo,
        folderIcon: FolderIcon,
    ) {
        val selectorRow: View = popup.inflateAndAdd(R.layout.folder_style_selector_row, rows)
        val btn3x3 = selectorRow.findViewById<View>(R.id.folder_style_3x3)
        val btn2x2 = selectorRow.findViewById<View>(R.id.folder_style_2x2)
        val btnFeatured = selectorRow.findViewById<View>(R.id.folder_style_featured)
        val icon3x3 = selectorRow.findViewById<View>(R.id.folder_style_3x3_icon)
        val icon2x2 = selectorRow.findViewById<View>(R.id.folder_style_2x2_icon)
        val iconFeatured = selectorRow.findViewById<View>(R.id.folder_style_featured_icon)

        fun updateSelectionUi(activeStyle: Int) {
            val is3x3 = activeStyle == FolderInfo.FOLDER_STYLE_3X3
            val is2x2 = activeStyle == FolderInfo.FOLDER_STYLE_2X2
            val isFeatured = activeStyle == FolderInfo.FOLDER_STYLE_FEATURED

            btn3x3.isSelected = is3x3
            btn2x2.isSelected = is2x2
            btnFeatured.isSelected = isFeatured

            btn3x3.setBackgroundResource(
                if (is3x3) R.drawable.bg_folder_style_option_selected else 0
            )
            btn2x2.setBackgroundResource(
                if (is2x2) R.drawable.bg_folder_style_option_selected else 0
            )
            btnFeatured.setBackgroundResource(
                if (isFeatured) R.drawable.bg_folder_style_option_selected else 0
            )
            icon3x3.alpha = if (is3x3) 1.0f else 0.55f
            icon2x2.alpha = if (is2x2) 1.0f else 0.55f
            iconFeatured.alpha = if (isFeatured) 1.0f else 0.55f
        }

        updateSelectionUi(folderInfo.getBigFolderStyle())

        btn3x3.setOnClickListener {
            folderIcon.setBigFolderStyle(FolderInfo.FOLDER_STYLE_3X3)
            updateSelectionUi(FolderInfo.FOLDER_STYLE_3X3)
        }
        btn2x2.setOnClickListener {
            folderIcon.setBigFolderStyle(FolderInfo.FOLDER_STYLE_2X2)
            updateSelectionUi(FolderInfo.FOLDER_STYLE_2X2)
        }
        btnFeatured.setOnClickListener {
            folderIcon.setBigFolderStyle(FolderInfo.FOLDER_STYLE_FEATURED)
            updateSelectionUi(FolderInfo.FOLDER_STYLE_FEATURED)
        }
    }

    override fun dismiss() {}
}
