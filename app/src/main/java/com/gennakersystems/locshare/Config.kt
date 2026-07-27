package com.gennakersystems.locshare

/**
 * Firebase / hosting configuration. The REPLACE_ME values are filled in once the
 * Firebase project exists (see README). Until then the app runs map + location
 * only, with sharing disabled.
 */
object Config {
    const val FIREBASE_PROJECT_ID = "locshare-gennaker"
    const val FIREBASE_APP_ID = "1:281098801907:android:b22062514ad36224059d86"
    const val FIREBASE_API_KEY = "AIzaSyDjCmOQ0yXztTJwZ9ipjW9RXf0cpnVY9v0"
    const val DATABASE_URL = "https://locshare-gennaker-default-rtdb.firebaseio.com"
    const val SHARE_BASE_URL = "https://locshare-gennaker.web.app/s/"

    val isConfigured: Boolean get() = !FIREBASE_PROJECT_ID.startsWith("REPLACE_ME")
}
