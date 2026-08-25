package com.oshi.desktop.mesh

/**
 * Snapshot a concurrent collection without Kotlin's `toList()`.
 *
 * `toList()` LOOKS like the safe way to iterate a `ConcurrentHashMap` view while another
 * thread mutates the map. It is not, and the way it fails is nasty because it is
 * size-dependent. `Collection<T>.toList()` branches on `size` first:
 *
 *     0 -> emptyList()
 *     1 -> listOf(if (this is List) get(0) else iterator().next())   // <-- here
 *     else -> toMutableList()
 *
 * A `ConcurrentHashMap` view's `size` is weakly consistent: it is a snapshot of a moving
 * number, not a lock. So when the map holds exactly one entry and that entry is removed
 * between the `size` read and the `iterator().next()`, `next()` finds nothing and throws
 * `NoSuchElementException`. Not a `ConcurrentModificationException` — a
 * `NoSuchElementException`, from code that is doing everything right by appearances.
 *
 * OBSERVED, not theorised (2026-08-25, `MeshNodeTest.tearDown` under a loaded machine):
 *
 *     java.util.NoSuchElementException
 *       at java.util.concurrent.ConcurrentHashMap$ValueIterator.next(ConcurrentHashMap.java:3480)
 *       at kotlin.collections.CollectionsKt___CollectionsKt.toList(_Collections.kt:1329)
 *       at com.oshi.desktop.mesh.MeshNode.stop(MeshNode.kt:156)
 *
 * `_Collections.kt:1329` is the `1 ->` branch above. The consequence was not a cosmetic
 * test failure: `stop()` threw halfway, so the connections were never closed and the peer,
 * routing and seen-id tables were never cleared. A node that fails to stop keeps its
 * sockets and goes on answering.
 *
 * The size-two-or-more branch is safe, which is exactly why this survives casual testing —
 * it only bites when the map happens to be down to its LAST entry, which for a mesh
 * teardown is the common case rather than the rare one.
 *
 * `java.util.ArrayList(Collection)` has no such fast path: it calls `toArray()`, which
 * `ConcurrentHashMap`'s views implement to tolerate the collection growing or shrinking
 * underneath it (it re-reads, grows, and trims with `Arrays.copyOf`). That is the whole
 * fix.
 *
 * Use this anywhere a concurrent view is iterated, and ESPECIALLY where the loop body
 * removes from the same map.
 */
internal fun <T> Collection<T>.concurrentSnapshot(): List<T> = java.util.ArrayList(this)
