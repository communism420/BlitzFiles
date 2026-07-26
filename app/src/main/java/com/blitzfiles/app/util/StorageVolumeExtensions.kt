package com.blitzfiles.app.util

import android.os.storage.StorageVolume
import com.blitzfiles.app.compat.directoryCompat

val StorageVolume.isMounted: Boolean
    get() = directoryCompat != null
