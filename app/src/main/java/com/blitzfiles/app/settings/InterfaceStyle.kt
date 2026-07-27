/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.settings

/**
 * Selects the presentation layer of the main file browser.
 *
 * Keep existing entries in their current order because [EnumSettingLiveData] persists ordinals.
 */
enum class InterfaceStyle {
    CLASSIC,
    FILE_MANAGER_PLUS
}
