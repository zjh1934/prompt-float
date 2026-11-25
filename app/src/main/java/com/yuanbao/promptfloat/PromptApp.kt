package com.yuanbao.promptfloat

import android.app.Application
import com.yuanbao.promptfloat.data.PromptRepository

class PromptApp : Application() {

    lateinit var prompts: PromptRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prompts = PromptRepository(this)
    }
}
