/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huanli233.hibari.runtime.snapshots

import android.annotation.SuppressLint
import android.os.Parcel
import android.os.Parcelable
import com.huanli233.hibari.runtime.Stable
import com.huanli233.hibari.runtime.external.kotlinx.collections.immutable.PersistentSet
import com.huanli233.hibari.runtime.external.kotlinx.collections.immutable.persistentSetOf
import com.huanli233.hibari.runtime.platform.makeSynchronizedObject

/**
 * An implementation of [MutableSet] that can be observed and snapshot.
 */
@Stable
@SuppressLint("BanParcelableUsage")
// Warning: The code of this class is duplicated in SnapshotStateSet.nonAndroid.kt. Any changes
// made here should be considered to be applied there as well.
class SnapshotStateSet<T> : Parcelable, StateObject, MutableSet<T>, RandomAccess {
    override var firstStateRecord: StateRecord = stateRecordWith(persistentSetOf())
        private set

    override fun prependStateRecord(value: StateRecord) {
        value.next = firstStateRecord
        @Suppress("UNCHECKED_CAST")
        firstStateRecord = value as StateSetStateRecord<T>
    }

    /**
     * Return a set containing all the elements of this set.
     *
     * The set returned is immutable and returned will not change even if the content of the set is
     * changed in the same snapshot. It also will be the same instance until the content is changed.
     * It is not, however, guaranteed to be the same instance for the same set as adding and
     * removing the same item from the this set might produce a different instance with the same
     * content.
     *
     * This operation is O(1) and does not involve a physically copying the set. It instead returns
     * the underlying immutable set used internally to store the content of the set.
     *
     * It is recommended to use [toSet] when returning the value of this set from
     * [androidx.compose.runtime.snapshotFlow].
     */
    fun toSet(): Set<T> = readable.set

    override val size: Int
        get() = readable.set.size

    override fun contains(element: T): Boolean = readable.set.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean =
        readable.set.containsAll(elements)

    override fun isEmpty(): Boolean = readable.set.isEmpty()

    override fun iterator(): MutableIterator<T> =
        StateSetIterator(this, readable.set.iterator())

    @Suppress("UNCHECKED_CAST")
    override fun toString(): String =
        (firstStateRecord as StateSetStateRecord<T>).withCurrent {
            "SnapshotStateSet(value=${it.set})@${hashCode()}"
        }

    override fun add(element: T): Boolean = conditionalUpdate { it.add(element) }

    override fun addAll(elements: Collection<T>): Boolean = conditionalUpdate {
        it.addAll(elements)
    }

    override fun clear(): Unit = clearImpl()

    override fun remove(element: T): Boolean = conditionalUpdate { it.remove(element) }

    override fun removeAll(elements: Collection<T>): Boolean = conditionalUpdate {
        it.removeAll(elements)
    }

    override fun retainAll(elements: Collection<T>): Boolean = mutateBoolean {
        it.retainAll(elements.toSet())
    }

    /**
     * An internal function used by the debugger to display the value of the current set without
     * triggering read observers.
     */
    @Suppress("unused")
    internal val debuggerDisplayValue: Set<T>
        @JvmName("getDebuggerDisplayValue") get() = withCurrent { set }

    // android specific additions below

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        val set = toSet()
        parcel.writeInt(size)
        val iterator = set.iterator()
        if (iterator.hasNext()) {
            parcel.writeValue(iterator.next())
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    internal companion object {
        @Suppress("unused", "NullableCollectionElement")
        @JvmField
        val CREATOR: Parcelable.Creator<SnapshotStateSet<Any?>> =
            object : Parcelable.ClassLoaderCreator<SnapshotStateSet<Any?>> {
                override fun createFromParcel(
                    parcel: Parcel,
                    loader: ClassLoader?,
                ): SnapshotStateSet<Any?> =
                    SnapshotStateSet<Any?>().apply {
                        val classLoader = loader ?: javaClass.classLoader
                        repeat(times = parcel.readInt()) { add(parcel.readValue(classLoader)) }
                    }

                override fun createFromParcel(parcel: Parcel) = createFromParcel(parcel, null)

                override fun newArray(size: Int) = arrayOfNulls<SnapshotStateSet<Any?>?>(size)
            }
    }
}

internal class StateSetStateRecord<T>
internal constructor(snapshotId: SnapshotId, internal var set: PersistentSet<T>) :
    StateRecord(snapshotId) {
    internal var modification = 0

    override fun assign(value: StateRecord) {
        synchronized(sync) {
            @Suppress("UNCHECKED_CAST")
            set = (value as StateSetStateRecord<T>).set
            modification = value.modification
        }
    }

    override fun create(): StateRecord = StateSetStateRecord(currentSnapshot().snapshotId, set)

    override fun create(snapshotId: SnapshotId): StateRecord = StateSetStateRecord(snapshotId, set)
}

internal val <T> SnapshotStateSet<T>.modification: Int
    get() = withCurrent { modification }

@Suppress("UNCHECKED_CAST")
internal val <T> SnapshotStateSet<T>.readable: StateSetStateRecord<T>
    get() = (firstStateRecord as StateSetStateRecord<T>).readable(this)

internal inline fun <R, T> SnapshotStateSet<T>.writable(block: StateSetStateRecord<T>.() -> R): R =
    @Suppress("UNCHECKED_CAST") (firstStateRecord as StateSetStateRecord<T>).writable(this, block)

internal inline fun <R, T> SnapshotStateSet<T>.withCurrent(
    block: StateSetStateRecord<T>.() -> R
): R = @Suppress("UNCHECKED_CAST") (firstStateRecord as StateSetStateRecord<T>).withCurrent(block)

internal fun <T> SnapshotStateSet<T>.mutateBoolean(block: (MutableSet<T>) -> Boolean): Boolean =
    mutate(block)

internal inline fun <R, T> SnapshotStateSet<T>.mutate(block: (MutableSet<T>) -> R): R {
    var result: R
    while (true) {
        var oldSet: PersistentSet<T>? = null
        var currentModification = 0
        synchronized(sync) {
            val current = withCurrent { this }
            currentModification = current.modification
            oldSet = current.set
        }
        val builder = oldSet?.builder() ?: error("No set to mutate")
        result = block(builder)
        val newSet = builder.build()
        if (newSet == oldSet || writable { attemptUpdate(currentModification, newSet) }) break
    }
    return result
}

internal inline fun <T> SnapshotStateSet<T>.conditionalUpdate(
    block: (PersistentSet<T>) -> PersistentSet<T>
) = run {
    val result: Boolean
    while (true) {
        var oldSet: PersistentSet<T>? = null
        var currentModification = 0
        synchronized(sync) {
            val current = withCurrent { this }
            currentModification = current.modification
            oldSet = current.set
        }
        val newSet = block(oldSet!!)
        if (newSet == oldSet) {
            result = false
            break
        }
        if (writable { attemptUpdate(currentModification, newSet) }) {
            result = true
            break
        }
    }
    result
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <T> SnapshotStateSet<T>.clearImpl() {
    writable {
        synchronized(sync) {
            set = persistentSetOf()
            modification++
        }
    }
}

// NOTE: do not inline this method to avoid class verification failures, see b/369909868
internal fun <T> StateSetStateRecord<T>.attemptUpdate(
    currentModification: Int,
    newSet: PersistentSet<T>,
): Boolean =
    synchronized(sync) {
        if (modification == currentModification) {
            set = newSet
            modification++
            true
        } else false
    }

internal fun <T> SnapshotStateSet<T>.stateRecordWith(set: PersistentSet<T>): StateRecord {
    return StateSetStateRecord(currentSnapshot().snapshotId, set).also {
        if (Snapshot.isInSnapshot) {
            it.next = StateSetStateRecord(Snapshot.PreexistingSnapshotId.toSnapshotId(), set)
        }
    }
}

/**
 * This lock is used to ensure that the value of modification and the set in the state record, when
 * used together, are atomically read and written.
 *
 * A global sync object is used to avoid having to allocate a sync object and initialize a monitor
 * for each instance the set. This avoids additional allocations but introduces some contention
 * between sets. As there is already contention on the global snapshot lock to write so the
 * additional contention introduced by this lock is nominal.
 *
 * In code the requires this lock and calls `writable` (or other operation that acquires the
 * snapshot global lock), this lock *MUST* be acquired last to avoid deadlocks. In other words, the
 * lock must be taken in the `writable` lambda, if `writable` is used.
 */
private val sync = makeSynchronizedObject()

internal class StateSetIterator<T>(val set: SnapshotStateSet<T>, val iterator: Iterator<T>) :
    MutableIterator<T> {
    var current: T? = null
    var next: T? = null
    var modification = set.modification

    init {
        advance()
    }

    override fun hasNext(): Boolean {
        return next != null
    }

    override fun next(): T {
        validateModification()
        advance()
        return current ?: throw IllegalStateException()
    }

    override fun remove() = modify {
        val value = current

        if (value != null) {
            set.remove(value)
            current = null
        } else {
            throw IllegalStateException()
        }
    }

    private fun advance() {
        current = next
        next = if (iterator.hasNext()) iterator.next() else null
    }

    private inline fun <T> modify(block: () -> T): T {
        validateModification()
        return block().also { modification = set.modification }
    }

    private fun validateModification() {
        if (set.modification != modification) {
            throw ConcurrentModificationException()
        }
    }
}