package com.github.adamantcheese.database.source.remote

import com.github.adamantcheese.base.ModularResult
import com.github.adamantcheese.base.ModularResult.Companion.safeRun
import com.github.adamantcheese.database.data.video_service.MediaServiceType
import com.github.adamantcheese.database.util.ensureBackgroundThread
import com.google.gson.JsonElement
import org.joda.time.Period

internal object MediaServiceLinkExtraContentRemoteSourceHelper {

    fun tryExtractVideoDuration(
            mediaServiceType: MediaServiceType,
            parser: JsonElement
    ): ModularResult<Period> {
        ensureBackgroundThread()

        return safeRun {
            return@safeRun when (mediaServiceType) {
                MediaServiceType.Youtube -> tryExtractYoutubeVideoDuration(parser)
            }
        }
    }

    fun tryExtractVideoTitle(
            mediaServiceType: MediaServiceType,
            parser: JsonElement
    ): ModularResult<String> {
        ensureBackgroundThread()

        return safeRun {
            return@safeRun when (mediaServiceType) {
                MediaServiceType.Youtube -> tryExtractYoutubeVideoTitle(parser)
            }
        }
    }

    private fun tryExtractYoutubeVideoTitle(parser: JsonElement): String {
        ensureBackgroundThread()

        return parser.asJsonObject
                .get("items")
                .asJsonArray
                .get(0)
                .asJsonObject
                .get("snippet")
                .asJsonObject
                .get("title")
                .asString
    }

    private fun tryExtractYoutubeVideoDuration(parser: JsonElement): Period {
        ensureBackgroundThread()

        val durationUnparsed = parser.asJsonObject
                .get("items")
                .asJsonArray
                .get(0)
                .asJsonObject
                .get("contentDetails")
                .asJsonObject
                .get("duration")
                .asString

        return Period.parse(durationUnparsed)
    }
}