/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.sync.GalleryDetailTagsSyncTask
import com.hippo.ehviewer.ui.scene.download.part.ThumbDataContainer

/** Loads download covers lazily, refreshing missing or expired thumbnail URLs once per appearance. */
class DownloadThumb @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FixedThumb(context, attrs, defStyleAttr) {
    private var download: DownloadInfo? = null
    private var refreshRequest: EhRequest? = null
    private var refreshAttempted = false

    fun bindDownload(info: DownloadInfo?) {
        cancelRefresh()
        download = info
        refreshAttempted = false
        load(ColorDrawable(Color.TRANSPARENT))
        if (info != null && isAttachedToWindow) {
            loadThumbnail(info)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        download?.let { loadThumbnail(it) }
    }

    override fun onDetachedFromWindow() {
        cancelRefresh()
        super.onDetachedFromWindow()
        if (download != null) {
            // Keep the binding, but prevent the base view from restarting an old request on attach.
            load(ColorDrawable(Color.TRANSPARENT))
            refreshAttempted = false
        }
    }

    private fun loadThumbnail(info: DownloadInfo) {
        // An empty URL still allows the local .thumb and image caches to be checked first.
        load(
            EhCacheKeyFactory.getThumbKey(info.gid), info.thumb.orEmpty(),
            ThumbDataContainer(info), !info.thumb.isNullOrBlank(), false
        )
    }

    override fun onFailure() {
        super.onFailure()
        val info = download ?: return
        if (!isAttachedToWindow || refreshAttempted || info.token.isNullOrBlank()) return
        refreshAttempted = true

        val request = EhRequest()
        refreshRequest = request
        request.setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(EhUrl.getGalleryDetailUrl(info.gid, info.token))
            .setCallback(object : EhClient.Callback<GalleryDetail> {
                override fun onSuccess(result: GalleryDetail) {
                    if (refreshRequest !== request || download !== info) return
                    refreshRequest = null
                    if (result.gid != info.gid) return

                    GalleryDetailTagsSyncTask(result).start()
                    if (result.thumb.isNullOrBlank()) return

                    info.thumb = result.thumb
                    EhDB.putDownloadInfo(info)
                    loadThumbnail(info)
                }

                override fun onFailure(e: Exception) {
                    if (refreshRequest === request) refreshRequest = null
                }

                override fun onCancel() {
                    if (refreshRequest === request) refreshRequest = null
                }
            })
        EhApplication.getEhClient(context).execute(request)
    }

    private fun cancelRefresh() {
        val request = refreshRequest
        refreshRequest = null
        request?.cancel()
    }
}
