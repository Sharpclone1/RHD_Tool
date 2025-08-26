package com.example.rhdtool.util

import android.content.Context
import androidx.core.content.edit

data class Selection(val wifi: Boolean, val mobile: Boolean, val bt: Boolean)

object Prefs {
    private const val FILE = "rhd_prefs"
    private const val K_WIFI = "sel_wifi"
    private const val K_MOBILE = "sel_mobile"
    private const val K_BT = "sel_bt"

    fun setSelection(ctx: Context, wifi: Boolean, mobile: Boolean, bt: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(K_WIFI, wifi)
            putBoolean(K_MOBILE, mobile)
            putBoolean(K_BT, bt)
        }
    }

    fun getSelection(ctx: Context): Selection {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Selection(
            wifi = sp.getBoolean(K_WIFI, true),
            mobile = sp.getBoolean(K_MOBILE, true),
            bt = sp.getBoolean(K_BT, true)
        )
    }
}
