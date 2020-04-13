package com.github.adamantcheese.model.data.post

import okhttp3.HttpUrl

class ChanPostHttpIconUnparsed(
        val iconUrl: HttpUrl,
        val iconName: String
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChanPostHttpIconUnparsed) return false

        if (iconUrl != other.iconUrl) return false
        if (iconName != other.iconName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = iconUrl.hashCode()
        result = 31 * result + iconName.hashCode()
        return result
    }

    override fun toString(): String {
        return "ChanPostHttpIconUnparsed(iconUrl=$iconUrl, iconName='$iconName')"
    }


}