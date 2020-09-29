package org.wordpress.android.ui.stories

import com.google.gson.Gson
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.ui.posts.EditPostRepository
import org.wordpress.android.ui.stories.prefs.StoriesPrefs
import org.wordpress.android.ui.stories.prefs.StoriesPrefs.LocalMediaId
import org.wordpress.android.ui.stories.prefs.StoriesPrefs.RemoteMediaId
import org.wordpress.android.util.StringUtils
import org.wordpress.android.util.helpers.MediaFile
import javax.inject.Inject

class SaveStoryGutenbergBlockUseCase @Inject constructor() {
    fun buildJetpackStoryBlockInPost(
        editPostRepository: EditPostRepository,
        mediaFiles: ArrayList<MediaFile>
    ) {
        editPostRepository.update { postModel: PostModel ->
            postModel.setContent(buildJetpackStoryBlockString(mediaFiles))
            true
        }
    }

    fun buildJetpackStoryBlockString(
        mediaFiles: List<MediaFile>
    ): String {
        val jsonArrayMediaFiles = ArrayList<StoryMediaFileData>() // holds media files
        for (mediaFile in mediaFiles) {
            jsonArrayMediaFiles.add(buildMediaFileData(mediaFile))
        }
        return buildJetpackStoryBlockStringFromStoryMediaFileData(jsonArrayMediaFiles)
    }

    fun buildJetpackStoryBlockStringFromStoryMediaFileData(
        storyMediaFileDataList: ArrayList<StoryMediaFileData>
    ): String {
        return createGBStoryBlockStringFromJson(StoryBlockData(mediaFiles = storyMediaFileDataList))
    }

    private fun buildMediaFileData(mediaFile: MediaFile): StoryMediaFileData {
        return StoryMediaFileData(
                alt = "",
                id = mediaFile.id.toString(),
                link = StringUtils.notNullStr(mediaFile.fileURL),
                type = if (mediaFile.isVideo) "video" else "image",
                mime = StringUtils.notNullStr(mediaFile.mimeType),
                caption = "",
                url = StringUtils.notNullStr(mediaFile.fileURL)
        )
    }

    fun buildMediaFileDataWithTemporaryId(mediaFile: MediaFile, temporaryId: String): StoryMediaFileData {
        return StoryMediaFileData(
                alt = "",    // notation abuse: we're repurposing this field to
                                                            // hold a temporaryId
                id = TEMPORARY_ID_PREFIX + temporaryId, // mediaFile.id,
                link = StringUtils.notNullStr(mediaFile.fileURL),
                type = if (mediaFile.isVideo) "video" else "image",
                mime = StringUtils.notNullStr(mediaFile.mimeType),
                caption = "",
                url = StringUtils.notNullStr(mediaFile.fileURL)
        )
    }

    fun cleanTemporaryMediaFilesStructFoundInAnyStoryBlockInPost(editPostRepository: EditPostRepository) {
        editPostRepository.update { postModel: PostModel ->
            val gson = Gson()
            findAllStoryBlocksInPostContentAndPerformOnEachMediaFilesJsonString(
                    postModel,
                    object : DoWithMediaFilesListener {
                        override fun doWithMediaFilesJsonString(content: String, mediaFilesJsonString: String): String {
                            var processedContent = content
                            val storyBlockData: StoryBlockData?
                                    = gson.fromJson(mediaFilesJsonString, StoryBlockData::class.java)
                            storyBlockData?.let {
                                if (hasTemporaryIdsInStoryData(it)) {
                                    // here remove the whole mediaFiles attribute
                                    processedContent = content.replace(mediaFilesJsonString, "")
                                }
                            }
                            return processedContent
                        }
                    }
            )
            true
        }
    }

    private fun hasTemporaryIdsInStoryData(storyBlockData: StoryBlockData): Boolean {
        val temporaryIds = storyBlockData.mediaFiles.filter {
            it.id.startsWith(TEMPORARY_ID_PREFIX)
        }
        return temporaryIds.size > 0
    }

    fun findAllStoryBlocksInPostContentAndPerformOnEachMediaFilesJsonString(
        postModel: PostModel,
        listener: DoWithMediaFilesListener
    ) {
        var content = postModel.content
        // val contentMutable = StringBuilder(postModel.content)

        // find next Story Block
        // evaluate if this has a temporary id mediafile
        // --> remove mediaFiles entirely
        // set start index and go up.
        var storyBlockStartIndex = 0
        while (storyBlockStartIndex > -1 && storyBlockStartIndex < content.length) {
            storyBlockStartIndex = content.indexOf(HEADING_START, storyBlockStartIndex)
            if (storyBlockStartIndex > -1) {
                val jsonString: String = content.substring(
                        storyBlockStartIndex + HEADING_START.length,
                        content.indexOf(HEADING_END))
                content = listener.doWithMediaFilesJsonString(content, jsonString)
                storyBlockStartIndex += HEADING_START.length
            }
        }

        postModel.setContent(content)
    }

    fun replaceLocalMediaIdsWithRemoteMediaIdsInPost(postModel: PostModel, mediaFile: MediaFile) {
        val gson = Gson()
        findAllStoryBlocksInPostContentAndPerformOnEachMediaFilesJsonString(
                postModel,
                object : DoWithMediaFilesListener {
                    override fun doWithMediaFilesJsonString(content: String, mediaFilesJsonString: String): String {
                        var processedContent = content
                        val storyBlockData: StoryBlockData?
                                = gson.fromJson(mediaFilesJsonString, StoryBlockData::class.java)
                        storyBlockData?.let { storyBlockDataNonNull ->
                            val localMediaId = mediaFile.id.toString()
                            // now replace matching localMediaId with remoteMediaId in the mediaFileObjects, obtain the URLs and replace
                            val mediaFiles = storyBlockDataNonNull.mediaFiles.filter { it.id == localMediaId }
                            if (mediaFiles.isNotEmpty()) {
                                mediaFiles[0].apply {
                                    id = mediaFile.mediaId
                                    link = mediaFile.fileURL
                                    url = mediaFile.fileURL

                                    // look for the slide saved with the local id key (mediaFile.id), and re-convert to mediaId.
                                    val localIdKey = mediaFile.id.toLong()
                                    val remoteIdKey = mediaFile.mediaId.toLong()
                                    val localSiteId = postModel.localSiteId.toLong()
                                    StoriesPrefs.getSlideWithLocalId(
                                            WordPress.getContext(),
                                            localSiteId,
                                            LocalMediaId(localIdKey)
                                    )?.let {
                                        it.id = mediaFile.mediaId // update the StoryFrameItem id to hold the same value as the remote mediaID
                                        StoriesPrefs.saveSlideWithRemoteId(
                                                WordPress.getContext(),
                                                localSiteId,
                                                RemoteMediaId(remoteIdKey), // use the new mediaId as key
                                                it
                                        )
                                        // now delete the old entry
                                        StoriesPrefs.deleteSlideWithLocalId(
                                                WordPress.getContext(),
                                                localSiteId,
                                                LocalMediaId(localIdKey)
                                        )
                                    }
                                }
                            }
                            processedContent = content.replace(mediaFilesJsonString, gson.toJson(storyBlockDataNonNull))
                        }
                        return processedContent
                    }
                }
        )
    }

    private fun createGBStoryBlockStringFromJson(storyBlock: StoryBlockData): String {
        val gson = Gson()
        return HEADING_START + gson.toJson(storyBlock) + HEADING_END + DIV_PART + CLOSING_TAG
    }

    interface DoWithMediaFilesListener {
        fun doWithMediaFilesJsonString(content: String, mediaFilesJsonString: String): String
    }

    data class StoryBlockData(
        val mediaFiles: List<StoryMediaFileData>
    )

    data class StoryMediaFileData(
        var alt: String,
        var id: String,
        var link: String,
        val type: String,
        val mime: String,
        val caption: String,
        var url: String
    )

    companion object {
        const val TEMPORARY_ID_PREFIX = "tempid-"
        const val HEADING_START = "<!-- wp:jetpack/story "
        const val HEADING_END = " -->\n"
        const val DIV_PART = "<div class=\"wp-story wp-block-jetpack-story\"></div>\n"
        const val CLOSING_TAG = "<!-- /wp:jetpack/story -->"
    }
}
