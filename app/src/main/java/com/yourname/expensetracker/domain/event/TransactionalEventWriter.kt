package com.yourname.expensetracker.domain.event

/**
 * Marker interface for event writers that participate in a [com.yourname.expensetracker.domain.transaction.TransactionContext].
 *
 * Implementations must be called inside a [com.yourname.expensetracker.domain.transaction.DomainTransactionRunner]
 * transaction when atomicity with the caller's state writes is required.
 *
 * This is a documentation contract, not a runtime enforcement mechanism. The static guard
 * (`DirectEventDaoInsertGuardTest`) enforces that external callers cannot call event DAO
 * insert methods directly — they must route through an approved writer.
 *
 * PR 3 — MIT-031: Transaction-scoped mutation model.
 */
interface TransactionalEventWriter
