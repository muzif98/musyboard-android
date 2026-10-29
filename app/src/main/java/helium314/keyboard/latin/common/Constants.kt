// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EXPERIMENTAL_SUFFIX = "dictionaries_experimental/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    /**
     * The upstream project.
     *
     * Still the root for wiki, discussions and community links: those pages exist
     * **there**, not in this fork. It is also the attribution: the keyboard is
     * their work.
     */
    const val GITHUB = "https://github.com/HeliBorg/HeliBoard"

    /**
     * This fork.
     *
     * Separate on purpose: "view on GitHub" must lead to the code in use; a bug in
     * the encryption part is reported here, not to those who did not write it.
     */
    const val PROJECT_GITHUB = "https://github.com/muzif98/musyboard-android"
    const val LICENSE = "$PROJECT_GITHUB/blob/cipher/LICENSE"
    const val WIKI_URL = "$GITHUB/wiki"
    const val LAYOUT_WIKI_URL = "$WIKI_URL/2.-Layouts"
    const val CUSTOM_LAYOUTS = "$GITHUB/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$GITHUB/discussions/categories/custom-colors"
    const val GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/cQECfDkuLGR9eUQquUEo4K"
    const val GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/CyjumVTWtJA"
    const val GESTURE_DATA_WIKI = "$WIKI_URL/Tutorial:-How-to-Contribute-Gesture-Data"
    const val BACKGROUND_GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/pPywMiF7kjumFfMrQKHzoU"
    const val BACKGROUND_GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/VJLzVEhY2PY"
    const val BACKGROUND_GESTURE_DATA_WIKI = "$WIKI_URL/Background-Gesture-Data-Gathering"
    const val SWIPE_O_SCOPE = "https://codeberg.org/eclexic/swipe-o-scope"
    const val COMMUNITY_LINKS = "$GITHUB#to-community"
}

val combiningRange = 0x300..0x35b
