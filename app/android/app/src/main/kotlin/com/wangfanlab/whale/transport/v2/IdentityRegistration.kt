package com.wangfanlab.whale.transport.v2

/** Idempotent for one dispatcher instance; a rebuilt client must register its new dispatcher. */
class IdentityRegistration<T : Any> {
    private var current: T? = null

    @Synchronized fun ensure(target: T, register: () -> Unit) {
        if (current === target) return
        register()
        current = target // failed registrations remain retryable
    }

    @Synchronized fun clear() { current = null }
}
