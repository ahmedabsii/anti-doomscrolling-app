package com.absi.ebb.watcher

/**
 * Tells Instagram's chats from everything else, by which views are on screen.
 *
 * Measured on Instagram 454.0.0.54.77 (realme RMX3301, Android 15, 8 November 2026). Instagram renames
 * views between versions, so every view name Ebb relies on lives here. Anything not recognised counts as
 * not a chat, so an update can only make Ebb stricter, never leave the feed open.
 */
object InstagramScreens {
    const val PACKAGE = "com.instagram.android"

    /** Opens the inbox directly. On the same build, the web link to the inbox lands on the feed instead. */
    const val CHATS_LINK = "instagram://direct-inbox"

    // The reel player, wherever the reel was opened, including from a chat. A reel is never a chat.
    private val REEL_PLAYER = listOf("clips_viewer_view_pager", "clips_video_container")

    // The inbox list and an open conversation.
    private val CHATS = listOf(
        "inbox_refreshable_thread_list_recyclerview",
        "direct_inbox_action_bar",
        "direct_thread_header",
        "row_thread_composer_edittext",
    )

    // The camera, opened from a chat or from Instagram's main camera, and a photo taken in a chat before it's
    // sent. Measured on Instagram 455. Nothing on them scrolls, so they count with the chats.
    private val CAMERA = listOf("camera_shutter_button", "direct_send_controls_and_thumbnail_tray_container")

    fun viewId(name: String) = "$PACKAGE:id/$name"

    /** Whether the screen is a chat or the camera, given a way to ask whether a view is on screen. */
    fun isChats(shows: (String) -> Boolean): Boolean = REEL_PLAYER.none(shows) && (CHATS.any(shows) || CAMERA.any(shows))
}
