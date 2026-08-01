package space.linuxct.glyphmatrixtoycompat.ai

import android.content.Context
import space.linuxct.glyphmatrixtoycompat.designs.DesignStore

/**
 * Wires "a design was deleted" to "its conversation goes with it", from the AI
 * side of the fence.
 *
 * ## Why this file exists at all
 *
 * `DesignStore.delete` used to hold a `ChatStore` and call it directly. The
 * behaviour was right — deletion that cannot be forgotten belongs in the one
 * function that deletes — but the direction was wrong. `designs/` is a storage
 * layer that `AodToyService` leans on before the first unlock; `ai/` is an
 * optional, explicitly unpublishable feature. Having the first import the second
 * meant a build without this package would not compile, and it put an OAuth-
 * flavoured dependency underneath the always-on display.
 *
 * So the arrow is turned round. `DesignStore` knows only that *something* may
 * care about a deletion and offers a listener; this object is that something, and
 * it lives on the side that is allowed to know about both. Deleting `ai/`
 * wholesale now costs one line in `Core.init` and nothing else.
 *
 * ## Direct Boot
 *
 * [install] is called from `Core.init`, which runs before the first unlock when
 * `AodToyService` starts. Credential-protected `filesDir` cannot even be created
 * in that state, and [ChatStore] deliberately refuses a device-protected context,
 * so nothing here may touch storage at registration time. The store is therefore
 * built by a `lazy` that the listener forces — which, on a device that never
 * deletes a design before unlocking, is simply never forced.
 */
object DesignChatCleanup {

    /**
     * Makes [designs] take conversations with it. Call once, from the object
     * graph, immediately after the store is built.
     *
     * The listener is not allowed to fail loudly and does not have to try: a
     * throw here would be caught by `DesignDeletionHooks` and logged, and the
     * user's design would still be gone. `ChatStore.delete` already returns false
     * rather than throwing for the ordinary case of a design nobody ever talked
     * to.
     */
    fun install(context: Context, designs: DesignStore) {
        val app = context.applicationContext
        val chats = lazy {
            // A device-protected context would make ChatStore's constructor throw
            // by design — conversations are credential-protected on purpose — and
            // a store built from one would have no chats to clean up anyway.
            if (app.isDeviceProtectedStorage) null else ChatStore(app) { designs.storedIds() }
        }
        designs.addDeletionListener { id -> chats.value?.delete(id) }
    }
}
