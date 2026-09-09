/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.akslabs.circletosearch.utils

import android.content.Context
import android.content.SharedPreferences

class BubblePreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bubble_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val BUBBLE_SIZE_KEY = "bubble_size"
        private const val BUBBLE_TRANSPARENCY_KEY = "bubble_transparency"
        
        // Default values
        const val DEFAULT_BUBBLE_SIZE = 100 // pixels
        const val DEFAULT_TRANSPARENCY = 100 // 0-100, where 100 is fully opaque
        const val MIN_BUBBLE_SIZE = 50
        const val MAX_BUBBLE_SIZE = 200
    }

    fun getBubbleSize(): Int {
        return prefs.getInt(BUBBLE_SIZE_KEY, DEFAULT_BUBBLE_SIZE)
    }

    fun setBubbleSize(size: Int) {
        val validSize = size.coerceIn(MIN_BUBBLE_SIZE, MAX_BUBBLE_SIZE)
        prefs.edit().putInt(BUBBLE_SIZE_KEY, validSize).apply()
    }

    fun getBubbleTransparency(): Int {
        return prefs.getInt(BUBBLE_TRANSPARENCY_KEY, DEFAULT_TRANSPARENCY)
    }

    fun setBubbleTransparency(transparency: Int) {
        val validTransparency = transparency.coerceIn(0, 100)
        prefs.edit().putInt(BUBBLE_TRANSPARENCY_KEY, validTransparency).apply()
    }

    /**
     * Converts transparency percentage (0-100) to alpha value (0-255)
     * 0% transparency = 255 (fully opaque)
     * 100% transparency = 0 (fully transparent)
     */
    fun getAlphaFromTransparency(transparency: Int): Int {
        return (transparency * 255 / 100)
    }

    fun resetToDefaults() {
        prefs.edit().apply {
            remove(BUBBLE_SIZE_KEY)
            remove(BUBBLE_TRANSPARENCY_KEY)
            apply()
        }
    }
}
