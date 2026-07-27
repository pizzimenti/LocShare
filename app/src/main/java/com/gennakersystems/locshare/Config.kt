package com.gennakersystems.locshare

/**
 * Firebase / hosting configuration. The REPLACE_ME values are filled in once the
 * Firebase project exists (see README). Until then the app runs map + location
 * only, with sharing disabled.
 */
object Config {
    const val FIREBASE_PROJECT_ID = "REPLACE_ME_PROJECT_ID"
    const val FIREBASE_APP_ID = "REPLACE_ME_APP_ID"
    const val FIREBASE_API_KEY = "REPLACE_ME_API_KEY"
    const val DATABASE_URL = "https://REPLACE_ME_PROJECT_ID-default-rtdb.firebaseio.com"
    const val SHARE_BASE_URL = "https://REPLACE_ME_PROJECT_ID.web.app/s/"

    val isConfigured: Boolean get() = !FIREBASE_PROJECT_ID.startsWith("REPLACE_ME")
}
