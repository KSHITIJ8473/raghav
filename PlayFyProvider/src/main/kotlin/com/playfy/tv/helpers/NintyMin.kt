package com.playfy.tv.helpers

import android.content.Context

object NintyMin {
    @JvmStatic
    external fun ohMyNo(encrypted: String, context: Context): String?

    @JvmStatic
    external fun ohMyYes(encrypted: String, lora: String, context: Context): String?
}
