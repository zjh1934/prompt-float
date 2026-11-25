package com.yuanbao.promptfloat

import android.app.Application
import com.yuanbao.promptfloat.core.DevLogger
import com.yuanbao.promptfloat.data.PromptRepository

class PromptApp : Application() {

    lateinit var prompts: PromptRepository
        private set

    override fun onCreate() {
        super.onCreate()
        DevLogger.init(this)
        prompts = PromptRepository(this)
        DevLogger.i("APP", "仓库初始化完成")
    }
}
