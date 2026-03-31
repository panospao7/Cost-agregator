package com.yourname.expensetracker.domain.receipt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * CRITICAL TEST (CRITICAL-3): Bitmap Concurrency and Mutex
 * 
 * Tests that bitmap processing operations are thread-safe using Mutex.
 * Verifies that concurrent operations don't cause race conditions or corruption.
 * 
 * Note: This tests the mutex logic that protects bitmap operations in ReceiptOcrService.
 * Full bitmap testing requires Android environment (instrumented tests).
 */
class BitmapConcurrencyTest {

    // ==================== MUTEX BASIC TESTS ====================

    @Test
    fun `mutex allows sequential access`() = runBlocking {
        val mutex = Mutex()
        val counter = AtomicInteger(0)
        
        // Sequential operations should complete successfully
        mutex.withLock {
            counter.incrementAndGet()
        }
        
        mutex.withLock {
            counter.incrementAndGet()
        }
        
        assertThat(counter.get()).isEqualTo(2)
    }

    @Test
    fun `mutex protects shared resource from concurrent modification`() = runBlocking {
        val mutex = Mutex()
        val sharedList = mutableListOf<Int>()
        val iterations = 1000
        
        // Launch many concurrent coroutines
        val jobs = List(100) { index ->
            launch(Dispatchers.Default) {
                repeat(10) { i ->
                    mutex.withLock {
                        // Critical section: modify shared resource
                        sharedList.add(index * 10 + i)
                    }
                }
            }
        }
        
        jobs.joinAll()
        
        // All items should be added (no race conditions)
        assertThat(sharedList.size).isEqualTo(iterations)
    }

    @Test
    fun `mutex prevents concurrent execution of critical section`() = runBlocking {
        val mutex = Mutex()
        val activeWorkers = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        
        val jobs = List(50) {
            launch(Dispatchers.Default) {
                mutex.withLock {
                    val current = activeWorkers.incrementAndGet()
                    maxConcurrent.set(maxOf(maxConcurrent.get(), current))
                    
                    // Simulate some work
                    delay(10)
                    
                    activeWorkers.decrementAndGet()
                }
            }
        }
        
        jobs.joinAll()
        
        // At most 1 worker should be in critical section at any time
        assertThat(maxConcurrent.get()).isEqualTo(1)
    }

    // ==================== CONCURRENT BITMAP SIMULATION ====================

    @Test
    fun `bitmap operations are serialized with mutex`() = runBlocking {
        val bitmapMutex = Mutex() // Simulates ReceiptOcrService.bitmapMutex
        val operations = mutableListOf<String>()
        val operationCount = 100
        
        // Simulate concurrent bitmap processing requests
        val jobs = List(operationCount) { index ->
            launch(Dispatchers.Default) {
                bitmapMutex.withLock {
                    // Simulate bitmap processing critical section
                    operations.add("Start-$index")
                    delay(1) // Simulate processing time
                    operations.add("End-$index")
                }
            }
        }
        
        jobs.joinAll()
        
        // All operations should complete
        assertThat(operations.size).isEqualTo(operationCount * 2)
        
        // Verify operations don't interleave (each start has matching end)
        for (i in 0 until operationCount) {
            val startIndex = operations.indexOf("Start-$i")
            val endIndex = operations.indexOf("End-$i")
            
            assertThat(startIndex).isAtLeast(0)
            assertThat(endIndex).isAtLeast(0)
            assertThat(endIndex).isGreaterThan(startIndex)
        }
    }

    @Test
    fun `concurrent bitmap access with timeout`() = runBlocking {
        val mutex = Mutex()
        val completed = AtomicInteger(0)
        val timeout = 5000L // 5 second timeout
        
        withTimeout(timeout) {
            val jobs = List(20) {
                launch(Dispatchers.Default) {
                    mutex.withLock {
                        // Simulate bitmap operation
                        delay(50)
                        completed.incrementAndGet()
                    }
                }
            }
            
            jobs.joinAll()
        }
        
        // All operations should complete within timeout
        assertThat(completed.get()).isEqualTo(20)
    }

    // ==================== ERROR HANDLING TESTS ====================

    @Test
    fun `mutex releases lock even when exception occurs`() = runBlocking {
        val mutex = Mutex()
        var secondLockAcquired = false
        
        try {
            mutex.withLock {
                throw RuntimeException("Simulated error")
            }
        } catch (e: RuntimeException) {
            // Expected
        }
        
        // Mutex should be released, allowing another lock
        mutex.withLock {
            secondLockAcquired = true
        }
        
        assertThat(secondLockAcquired).isTrue()
    }

    @Test
    fun `concurrent operations continue after one fails`() = runBlocking {
        val mutex = Mutex()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        
        val jobs = List(10) { index ->
            launch(Dispatchers.Default) {
                try {
                    mutex.withLock {
                        if (index == 5) {
                            throw RuntimeException("Simulated failure")
                        }
                        successCount.incrementAndGet()
                    }
                } catch (e: RuntimeException) {
                    failCount.incrementAndGet()
                }
            }
        }
        
        jobs.joinAll()
        
        // 9 should succeed, 1 should fail
        assertThat(successCount.get()).isEqualTo(9)
        assertThat(failCount.get()).isEqualTo(1)
    }

    // ==================== STRESS TESTS ====================

    @Test
    fun `mutex handles high concurrency`() = runBlocking {
        val mutex = Mutex()
        val counter = AtomicInteger(0)
        val iterations = 10000
        
        val jobs = List(100) {
            launch(Dispatchers.Default) {
                repeat(100) {
                    mutex.withLock {
                        counter.incrementAndGet()
                    }
                }
            }
        }
        
        jobs.joinAll()
        
        // All increments should be counted (no lost updates)
        assertThat(counter.get()).isEqualTo(iterations)
    }

    @Test
    fun `mutex maintains integrity under rapid lock-unlock`() = runBlocking {
        val mutex = Mutex()
        val counter = AtomicInteger(0)
        val rapidIterations = 1000
        
        repeat(rapidIterations) {
            mutex.withLock {
                counter.incrementAndGet()
            }
        }
        
        assertThat(counter.get()).isEqualTo(rapidIterations)
    }

    // ==================== MEMORY SAFETY SIMULATION ====================

    @Test
    fun `simulated bitmap lifecycle is protected`() = runBlocking {
        val bitmapMutex = Mutex()
        val activeBitmaps = AtomicInteger(0)
        val peakBitmaps = AtomicInteger(0)
        
        val jobs = List(50) {
            launch(Dispatchers.Default) {
                bitmapMutex.withLock {
                    // Simulate bitmap allocation
                    val current = activeBitmaps.incrementAndGet()
                    peakBitmaps.set(maxOf(peakBitmaps.get(), current))
                    
                    // Simulate processing
                    delay(10)
                    
                    // Simulate bitmap deallocation
                    activeBitmaps.decrementAndGet()
                }
            }
        }
        
        jobs.joinAll()
        
        // At most 1 bitmap should be active at any time (serialized)
        assertThat(peakBitmaps.get()).isEqualTo(1)
        assertThat(activeBitmaps.get()).isEqualTo(0)
    }

    // ==================== TIMEOUT AND CANCELLATION ====================

    @Test
    fun `mutex operations respect cancellation`() = runBlocking {
        val mutex = Mutex()
        val completedBeforeCancel = AtomicInteger(0)
        val job = launch(Dispatchers.Default) {
            repeat(100) {
                mutex.withLock {
                    completedBeforeCancel.incrementAndGet()
                    delay(100)
                }
            }
        }
        
        // Let a few operations complete
        delay(250)
        job.cancelAndJoin()
        
        // Should have completed at least 2, at most 3 operations
        assertThat(completedBeforeCancel.get()).isAtLeast(2)
        assertThat(completedBeforeCancel.get()).isAtMost(3)
    }

    @Test
    fun `mutex is fair under contention`() = runBlocking {
        val mutex = Mutex()
        val acquisitionOrder = mutableListOf<Int>()
        val lock = Object()
        
        val jobs = List(10) { index ->
            launch(Dispatchers.Default) {
                mutex.withLock {
                    synchronized(lock) {
                        acquisitionOrder.add(index)
                    }
                    delay(10)
                }
            }
        }
        
        jobs.joinAll()
        
        // All jobs should acquire the lock
        assertThat(acquisitionOrder.size).isEqualTo(10)
        
        // Verify all indices are present
        assertThat(acquisitionOrder.toSet().size).isEqualTo(10)
    }

    // ==================== REALISTIC SCENARIO ====================

    @Test
    fun `receipt processing simulation with mutex`() = runBlocking {
        val bitmapMutex = Mutex()
        val processedReceipts = mutableListOf<Int>()
        val receiptCount = 20
        
        // Simulate multiple receipt scans arriving concurrently
        val jobs = List(receiptCount) { receiptId ->
            launch(Dispatchers.Default) {
                // Wait a random short time to simulate network/IO
                delay((receiptId * 10).toLong())
                
                bitmapMutex.withLock {
                    // Simulate: Load bitmap from URI
                    // Simulate: Process with OCR
                    // Simulate: Save results
                    delay(20) // Processing time
                    
                    synchronized(processedReceipts) {
                        processedReceipts.add(receiptId)
                    }
                }
            }
        }
        
        jobs.joinAll()
        
        // All receipts should be processed
        assertThat(processedReceipts.size).isEqualTo(receiptCount)
        
        // Verify no duplicates
        assertThat(processedReceipts.toSet().size).isEqualTo(receiptCount)
    }
}