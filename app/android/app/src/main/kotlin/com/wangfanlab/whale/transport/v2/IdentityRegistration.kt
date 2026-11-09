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

/** Reattach handlers to a rebuilt dispatcher without restarting a stateful resource. */
fun <R : Any> retainAndBind(existing: R?, create: () -> R, bind: (R) -> Unit): R {
    val resource = existing ?: create()
    bind(resource)
    return resource
}
