package com.fnmusic.tv.core.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

enum class PlaybackTransportOwnership { Restoring, Normal, Roam }

enum class PlaybackTransportDirection { Previous, Next }

object PlaybackTransportBridge {
    private data class Registration(
        val ownership: PlaybackTransportOwnership,
        val handler: ((PlaybackTransportDirection) -> Unit)?,
    )

    private val registration = AtomicReference(
        Registration(PlaybackTransportOwnership.Restoring, null),
    )
    private val ownershipListeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(handler: (PlaybackTransportDirection) -> Unit) {
        updateRegistration { it.copy(handler = handler) }
    }

    fun setOwnership(ownership: PlaybackTransportOwnership) {
        val previous = updateRegistration { it.copy(ownership = ownership) }
        if (previous.ownership != ownership) ownershipListeners.forEach { it() }
    }

    fun unregister() {
        val previous = registration.getAndSet(
            Registration(PlaybackTransportOwnership.Restoring, null),
        )
        if (previous.ownership != PlaybackTransportOwnership.Restoring) {
            ownershipListeners.forEach { it() }
        }
    }

    internal fun route(direction: PlaybackTransportDirection): PlaybackTransportOwnership {
        val current = registration.get()
        if (current.ownership == PlaybackTransportOwnership.Roam) {
            val handler = current.handler ?: return PlaybackTransportOwnership.Restoring
            handler(direction)
        }
        return current.ownership
    }

    internal fun ownership(): PlaybackTransportOwnership = registration.get().ownership

    internal fun addOwnershipListener(listener: () -> Unit) {
        ownershipListeners += listener
    }

    internal fun removeOwnershipListener(listener: () -> Unit) {
        ownershipListeners -= listener
    }

    private fun updateRegistration(transform: (Registration) -> Registration): Registration {
        while (true) {
            val current = registration.get()
            if (registration.compareAndSet(current, transform(current))) return current
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class RoutingPlayer(
    private val delegate: Player,
) : ForwardingPlayer(delegate) {
    private val ownershipCallbacks = ConcurrentHashMap<Player.Listener, () -> Unit>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        val callback = { listener.onAvailableCommandsChanged(availableCommands) }
        ownershipCallbacks[listener] = callback
        PlaybackTransportBridge.addOwnershipListener(callback)
    }

    override fun removeListener(listener: Player.Listener) {
        ownershipCallbacks.remove(listener)?.let(PlaybackTransportBridge::removeOwnershipListener)
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands {
        val commands = delegate.availableCommands
        if (PlaybackTransportBridge.ownership() != PlaybackTransportOwnership.Roam) return commands
        return Player.Commands.Builder()
            .addAll(commands)
            .addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (PlaybackTransportBridge.ownership() == PlaybackTransportOwnership.Roam && command in ROAM_COMMANDS) true
        else delegate.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean =
        PlaybackTransportBridge.ownership() == PlaybackTransportOwnership.Roam || delegate.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean =
        PlaybackTransportBridge.ownership() == PlaybackTransportOwnership.Roam || delegate.hasPreviousMediaItem()

    override fun seekToNextMediaItem() = routeNext()

    override fun seekToNext() = routeNext()

    override fun seekToPreviousMediaItem() = routePrevious()

    override fun seekToPrevious() = routePrevious()

    private fun routeNext() {
        when (PlaybackTransportBridge.route(PlaybackTransportDirection.Next)) {
            PlaybackTransportOwnership.Normal -> {
                if (delegate.repeatMode == Player.REPEAT_MODE_ONE && delegate.mediaItemCount > 1) {
                    val nextIndex = (delegate.currentMediaItemIndex + 1) % delegate.mediaItemCount
                    delegate.seekTo(nextIndex, 0L)
                } else {
                    delegate.seekToNextMediaItem()
                }
            }
            PlaybackTransportOwnership.Roam,
            PlaybackTransportOwnership.Restoring,
            -> Unit
        }
    }

    private fun routePrevious() {
        when (PlaybackTransportBridge.route(PlaybackTransportDirection.Previous)) {
            PlaybackTransportOwnership.Normal -> {
                if (delegate.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
                    delegate.seekTo(0L)
                } else if (delegate.repeatMode == Player.REPEAT_MODE_ONE && delegate.mediaItemCount > 1) {
                    val previousIndex = (delegate.currentMediaItemIndex - 1 + delegate.mediaItemCount) %
                        delegate.mediaItemCount
                    delegate.seekTo(previousIndex, 0L)
                } else {
                    delegate.seekToPreviousMediaItem()
                }
            }
            PlaybackTransportOwnership.Roam,
            PlaybackTransportOwnership.Restoring,
            -> Unit
        }
    }

    private companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        val ROAM_COMMANDS = setOf(
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
        )
    }
}
