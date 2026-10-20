package com.wrapper.teams

object Constants {
    const val TEAMS_URL = "https://teams.microsoft.com/"

    // Desktop Edge UA so Microsoft serves the full web app instead of "use the app" page.
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0"

    // Notification channels
    const val CHANNEL_SERVICE = "teams_service"
    const val CHANNEL_MESSAGES = "teams_messages"
    const val CHANNEL_CALLS = "teams_calls"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val CALL_NOTIFICATION_ID = 2001

    // Intent actions
    const val ACTION_ANSWER_CALL = "com.wrapper.teams.ANSWER_CALL"
    const val ACTION_DECLINE_CALL = "com.wrapper.teams.DECLINE_CALL"
    const val ACTION_SHOW_MAIN = "com.wrapper.teams.SHOW_MAIN"

    const val EXTRA_CALLER = "extra_caller"
    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_CHAT_ID = "extra_chat_id"
    const val EXTRA_CHAT_TITLE = "extra_chat_title"

    // Bridge name exposed to the Teams web page
    const val JS_BRIDGE = "AndroidTeams"
}
