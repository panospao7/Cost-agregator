package com.yourname.expensetracker.domain.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BK-Tree (Burkhard-Keller Tree) for efficient fuzzy string searching.
 * 
 * Allows finding all strings within a certain edit distance in O(log n)
 * instead of O(n) for linear search.
 */
class StringBKTree private constructor(
    private val distanceFunction: (String, String) -> Int
) {
    private data class Node(
        val item: String,
        val children: MutableMap<Int, Node> = mutableMapOf()
    )

    private var root: Node? = null
    private var _size = 0
    private val mutex = Mutex()
    
    val size: Int get() = _size
    val isEmpty: Boolean get() = root == null

    companion object {
        /**
         * Create a BK-Tree using Levenshtein distance.
         */
        fun create(): StringBKTree {
            return StringBKTree { s1, s2 -> 
                StringDistanceUtils.levenshteinDistance(s1, s2) 
            }
        }
    }

    /**
     * Insert an item into the tree.
     */
    suspend fun insert(item: String) = mutex.withLock {
        val normalized = item.lowercase().trim()
        
        if (root == null) {
            root = Node(normalized)
            _size = 1
            return@withLock
        }

        var current = root!!
        while (true) {
            val dist = distanceFunction(current.item, normalized)
            
            if (dist == 0) return@withLock // Duplicate
            
            val child = current.children[dist]
            if (child == null) {
                current.children[dist] = Node(normalized)
                _size++
                return@withLock
            }
            current = child
        }
    }

    /**
     * Insert multiple items.
     */
    suspend fun insertAll(items: Collection<String>) {
        items.forEach { insert(it) }
    }

    /**
     * Find all items within a maximum distance from the query.
     */
    suspend fun search(query: String, maxDistance: Int): List<Pair<String, Int>> = mutex.withLock {
        val results = mutableListOf<Pair<String, Int>>()
        val normalized = query.lowercase().trim()
        searchRecursive(root, normalized, maxDistance, results)
        results.sortedBy { it.second }
    }

    private fun searchRecursive(
        node: Node?,
        query: String,
        maxDistance: Int,
        results: MutableList<Pair<String, Int>>
    ) {
        if (node == null) return

        val dist = distanceFunction(node.item, query)
        
        if (dist <= maxDistance) {
            results.add(node.item to dist)
        }

        val minDist = maxOf(0, dist - maxDistance)
        val maxDist = dist + maxDistance

        for ((edgeDist, child) in node.children) {
            if (edgeDist in minDist..maxDist) {
                searchRecursive(child, query, maxDistance, results)
            }
        }
    }

    /**
     * Find the single best match within maxDistance.
     */
    suspend fun findBestMatch(query: String, maxDistance: Int): Pair<String, Int>? {
        return search(query, maxDistance).minByOrNull { it.second }
    }

    /**
     * Clear all items.
     */
    suspend fun clear() = mutex.withLock {
        root = null
        _size = 0
    }
}
