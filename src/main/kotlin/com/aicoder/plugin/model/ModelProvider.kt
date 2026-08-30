package com.aicoder.plugin.model

/**
 * 所有厂商适配器需要实现的统一接口。
 * 上层（聊天面板）只依赖这个接口，不关心具体是哪家的 API。
 */
interface ModelProvider {
    /**
     * 发起一次流式对话请求。
     * onToken：每收到一小段文本就回调一次（用于打字机效果）
     * onComplete：流结束
     * onError：出错时回调（网络错误、鉴权失败、返回体格式不对等）
     *
     * 注意：这个方法内部会另起线程做网络请求，不会阻塞调用方；
     * 所有回调都会切回调用时线程池的线程，UI 层需要自己 invokeLater 到 EDT。
     */
    fun chatStream(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    )
}
