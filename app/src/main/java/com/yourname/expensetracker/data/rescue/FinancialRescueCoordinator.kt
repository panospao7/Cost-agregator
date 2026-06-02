package com.yourname.expensetracker.data.rescue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yourname.expensetracker.data.database.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * Coordinates the financial rescue path when Room migrations fail.
 *
 * ## High-level flow
 * 1. Opens the old DB with raw Android SQLite (NO Room, NO migrations)
 * 2. Reads only financial tables into plain data classes
 * 3. Backs up the old DB file (and WAL/SHM journals)
 * 4. Moves the old DB aside (renamed to .legacy.<timestamp>) so Room can create a fresh one at latest schema
 * 5. Creates a new Room database (fresh install at version APP_DATABASE_SCHEMA_VERSION)
 * 6. Imports recovered financial rows via SupportSQLiteDatabase (INSERT OR REPLACE)
 * 7. Writes a done-marker so rescue only runs once
 */
class FinancialRescueCoordinator(private val context: Context) {

    companion object {
        private const val TAG = "FinancialRescue"
        private const val DONE_MARKER = "rescue_completed.txt"
        private const val BACKUP_SUFFIX = ".rescue_backup"
        private const val SNAPSHOT_FILENAME = "rescue_snapshot.json"

        // Table names as they exist in the old database
        private const val TABLE_CATEGORIES = "categories"
        private const val TABLE_EXPENSES = "expenses"
        private const val TABLE_EXPENSE_GROUPS = "expense_groups"
        private const val TABLE_GROUP_MEMBERS = "group_members"
        private const val TABLE_GROUP_EXPENSES = "group_expenses"
        private const val TABLE_SPLIT_ASSIGNMENTS = "split_item_assignments"
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Main entry point. Checks rescue config, looks for an existing DB,
     * runs the rescue if needed. Safe to call on every app startup.
     */
    fun runRescueIfNeeded(): RescueResult {
        if (!RescueConfig.ENABLE_FINANCIAL_RESCUE) {
            log("Rescue disabled via RescueConfig.ENABLE_FINANCIAL_RESCUE = false")
            return RescueResult.SKIPPED
        }

        if (isRescueDone()) {
            log("Rescue already completed, skipping")
            return RescueResult.ALREADY_DONE
        }

        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        if (!dbFile.exists()) {
            log("No database file found at ${dbFile.absolutePath}, nothing to rescue")
            markRescueDone()
            return RescueResult.NO_DB
        }

        log("Starting financial rescue from ${dbFile.absolutePath}")
        return try {
            val oldUserVersion = readUserVersion(dbFile)

            // 1. Read old data
            val snapshot = readOldDatabaseSnapshot(dbFile)
            log("Snapshot: ${snapshot.categories.size} categories, ${snapshot.expenses.size} expenses, " +
                    "${snapshot.expenseGroups.size} groups, ${snapshot.groupMembers.size} members, " +
                    "${snapshot.groupExpenses.size} group expenses, ${snapshot.splitAssignments.size} splits")

            // 2. Write JSON snapshot to filesDir for safety
            writeJsonSnapshot(snapshot)

            // 3. Backup old DB files
            backupDatabaseFiles(dbFile)

            // 4. Move old DB aside so Room creates fresh
            moveDatabaseFilesAside(dbFile)
            log("Old database files moved aside")

            // 5. Create fresh Room DB and import
            createFreshRoomDatabaseAndImport(snapshot)

            // 6. Mark done
            markRescueDone()
            log("Financial rescue completed successfully")

            RescueResult.SUCCESS
        } catch (e: Exception) {
            log("Rescue FAILED: ${e.message}", e)
            restoreDatabaseFiles(dbFile)
            RescueResult.FAILURE(e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Reading the old database (raw SQLite — no Room)
    // ──────────────────────────────────────────────────────────────

    /**
     * Opens the old database with raw Android SQLite in read-only mode
     * and reads all financial tables into a snapshot.
     */
    fun readOldDatabaseSnapshot(dbFile: File): FinancialRescueSnapshot {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            val oldUserVersion = db.version

            val categories = readCategories(db)
            val expenses = readExpenses(db)
            val expenseGroups = readExpenseGroups(db)
            val groupMembers = readGroupMembers(db)
            val groupExpenses = readGroupExpenses(db)
            val splitAssignments = readSplitItemAssignments(db)

            return FinancialRescueSnapshot(
                oldUserVersion = oldUserVersion,
                categories = categories,
                expenses = expenses,
                expenseGroups = expenseGroups,
                groupMembers = groupMembers,
                groupExpenses = groupExpenses,
                splitAssignments = splitAssignments
            )
        } finally {
            db.close()
        }
    }

    fun readCategories(db: SQLiteDatabase): List<RescueCategory> {
        if (!hasTable(db, TABLE_CATEGORIES)) return emptyList()
        val cols = columns(db, TABLE_CATEGORIES)
        val result = mutableListOf<RescueCategory>()

        db.rawQuery("SELECT * FROM $TABLE_CATEGORIES", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    RescueCategory(
                        id = cursor.getLong(cols.getValue("id")),
                        name = cursor.getString(cols.getValue("name")) ?: "",
                        icon = stringOrNull(cursor, cols.getOrDefault("icon", -1)) ?: "",
                        color = stringOrNull(cursor, cols.getOrDefault("color", -1)) ?: "#000000",
                        isDefault = boolOrDefault(cursor, cols, "isDefault", false)
                    )
                )
            }
        }
        return result
    }

    fun readExpenses(db: SQLiteDatabase): List<RescueExpense> {
        if (!hasTable(db, TABLE_EXPENSES)) return emptyList()
        val cols = columns(db, TABLE_EXPENSES)
        val result = mutableListOf<RescueExpense>()

        // Query all columns (unknown which exist — old schemas vary)
        db.rawQuery("SELECT * FROM $TABLE_EXPENSES", null).use { cursor ->
            while (cursor.moveToNext()) {
                val dateCol = cols.getValue("date")
                val createdAtCol = cols.getOrDefault("createdAt", -1)
                val creationTime = if (createdAtCol >= 0 && !cursor.isNull(createdAtCol)) {
                    cursor.getLong(createdAtCol)
                } else {
                    cursor.getLong(dateCol)
                }

                result.add(
                    RescueExpense(
                        id = cursor.getLong(cols.getValue("id")),
                        amount = cursor.getDouble(cols.getValue("amount")),
                        currency = sanitizeCurrency(stringOrNull(cursor, cols.getOrDefault("currency", -1)) ?: "EUR"),
                        merchant = cursor.getString(cols.getValue("merchant")) ?: "",
                        transactionType = sanitizeTransactionType(stringOrNull(cursor, cols.getOrDefault("transactionType", -1)) ?: "UNKNOWN"),
                        date = cursor.getLong(dateCol),
                        categoryId = longOrNull(cursor, cols.getOrDefault("categoryId", -1)),
                        createdAt = creationTime,
                        source = stringOrNull(cursor, cols.getOrDefault("source", -1)),
                        paymentMethod = sanitizePaymentMethod(stringOrNull(cursor, cols.getOrDefault("paymentMethod", -1)) ?: "UNKNOWN"),
                        isManualEntry = boolOrDefault(cursor, cols, "isManualEntry", false),
                        notes = stringOrNull(cursor, cols.getOrDefault("notes", -1)),
                        transferDirection = sanitizeTransferDirection(stringOrNull(cursor, cols.getOrDefault("transferDirection", -1))),
                        transferAccountName = stringOrNull(cursor, cols.getOrDefault("transferAccountName", -1)),
                        isNotMine = boolOrDefault(cursor, cols, "isNotMine", false),
                        ownerName = stringOrNull(cursor, cols.getOrDefault("ownerName", -1)),
                        isSharedExpense = boolOrDefault(cursor, cols, "isSharedExpense", false),
                        sharedWithName = stringOrNull(cursor, cols.getOrDefault("sharedWithName", -1)),
                        mySharePercentage = intOrNull(cursor, cols.getOrDefault("mySharePercentage", -1)),
                        myShareAmount = doubleOrNull(cursor, cols.getOrDefault("myShareAmount", -1)),
                        splitVisualization = stringOrNull(cursor, cols.getOrDefault("splitVisualization", -1))
                    )
                )
            }
        }
        return result
    }

    fun readExpenseGroups(db: SQLiteDatabase): List<RescueExpenseGroup> {
        if (!hasTable(db, TABLE_EXPENSE_GROUPS)) return emptyList()
        val cols = columns(db, TABLE_EXPENSE_GROUPS)
        val result = mutableListOf<RescueExpenseGroup>()

        db.rawQuery("SELECT * FROM $TABLE_EXPENSE_GROUPS", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    RescueExpenseGroup(
                        id = cursor.getLong(cols.getValue("id")),
                        name = cursor.getString(cols.getValue("name")) ?: "",
                        description = stringOrNull(cursor, cols.getOrDefault("description", -1)),
                        defaultCurrency = sanitizeCurrency(stringOrNull(cursor, cols.getOrDefault("defaultCurrency", -1)) ?: "EUR"),
                        isActive = boolOrDefault(cursor, cols, "isActive", true),
                        createdAt = longOrNull(cursor, cols.getOrDefault("createdAt", -1)) ?: 0L,
                        createdBy = stringOrNull(cursor, cols.getOrDefault("createdBy", -1)) ?: "me"
                    )
                )
            }
        }
        return result
    }

    fun readGroupMembers(db: SQLiteDatabase): List<RescueGroupMember> {
        if (!hasTable(db, TABLE_GROUP_MEMBERS)) return emptyList()
        val cols = columns(db, TABLE_GROUP_MEMBERS)
        val result = mutableListOf<RescueGroupMember>()

        db.rawQuery("SELECT * FROM $TABLE_GROUP_MEMBERS", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    RescueGroupMember(
                        id = cursor.getLong(cols.getValue("id")),
                        groupId = cursor.getLong(cols.getValue("groupId")),
                        name = cursor.getString(cols.getValue("name")) ?: "",
                        email = stringOrNull(cursor, cols.getOrDefault("email", -1)),
                        isCurrentUser = boolOrDefault(cursor, cols, "isCurrentUser", false),
                        joinedAt = longOrNull(cursor, cols.getOrDefault("joinedAt", -1)) ?: 0L
                    )
                )
            }
        }
        return result
    }

    fun readGroupExpenses(db: SQLiteDatabase): List<RescueGroupExpense> {
        if (!hasTable(db, TABLE_GROUP_EXPENSES)) return emptyList()
        val cols = columns(db, TABLE_GROUP_EXPENSES)
        val result = mutableListOf<RescueGroupExpense>()

        db.rawQuery("SELECT * FROM $TABLE_GROUP_EXPENSES", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    RescueGroupExpense(
                        id = cursor.getLong(cols.getValue("id")),
                        groupId = cursor.getLong(cols.getValue("groupId")),
                        expenseId = longOrNull(cursor, cols.getOrDefault("expenseId", -1)),
                        paidById = cursor.getLong(cols.getValue("paidById")),
                        date = cursor.getLong(cols.getValue("date")),
                        description = cursor.getString(cols.getValue("description")) ?: "",
                        totalAmount = cursor.getDouble(cols.getValue("totalAmount")),
                        currency = sanitizeCurrency(stringOrNull(cursor, cols.getOrDefault("currency", -1)) ?: "EUR"),
                        splitType = sanitizeSplitType(stringOrNull(cursor, cols.getOrDefault("splitType", -1)) ?: "EQUAL"),
                        customSplitsJson = stringOrNull(cursor, cols.getOrDefault("customSplitsJson", -1)),
                        isReimbursable = boolOrDefault(cursor, cols, "isReimbursable", false),
                        reimbursedAmount = doubleOrNull(cursor, cols.getOrDefault("reimbursedAmount", -1)) ?: 0.0,
                        settledAt = longOrNull(cursor, cols.getOrDefault("settledAt", -1)),
                        myShareAmount = doubleOrNull(cursor, cols.getOrDefault("myShareAmount", -1))
                    )
                )
            }
        }
        return result
    }

    fun readSplitItemAssignments(db: SQLiteDatabase): List<RescueSplitItemAssignment> {
        if (!hasTable(db, TABLE_SPLIT_ASSIGNMENTS)) return emptyList()
        val cols = columns(db, TABLE_SPLIT_ASSIGNMENTS)
        val result = mutableListOf<RescueSplitItemAssignment>()

        db.rawQuery("SELECT * FROM $TABLE_SPLIT_ASSIGNMENTS", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    RescueSplitItemAssignment(
                        id = cursor.getLong(cols.getValue("id")),
                        expenseId = cursor.getLong(cols.getValue("expenseId")),
                        receiptItemId = longOrNull(cursor, cols.getOrDefault("receiptItemId", -1)),
                        participantName = cursor.getString(cols.getValue("participantName")) ?: "",
                        participantIndex = intOrNull(cursor, cols.getOrDefault("participantIndex", -1)) ?: 0,
                        assignedAmount = cursor.getDouble(cols.getValue("assignedAmount")),
                        isPaid = boolOrDefault(cursor, cols, "isPaid", false),
                        paidAt = longOrNull(cursor, cols.getOrDefault("paidAt", -1)),
                        createdAt = longOrNull(cursor, cols.getOrDefault("createdAt", -1)) ?: 0L
                    )
                )
            }
        }
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // Creating fresh Room DB and importing data
    // ──────────────────────────────────────────────────────────────

    /**
     * Creates a fresh Room database (which will run all migrations from scratch
     * on an empty file) and imports the rescued snapshot inside a transaction.
     */
    fun createFreshRoomDatabaseAndImport(snapshot: FinancialRescueSnapshot) {
        // Move aside any leftover files (Room's builder may have cached something)
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        moveDatabaseFilesAside(dbFile)

        // Build a fresh Room database — this will create all tables at latest schema
        val roomDb = AppDatabase.fileBuilder(context).build()

        // Import through the writable database
        val supportDb = roomDb.openHelper.writableDatabase
        try {
            // Build FK lookup sets from imported data
            val validCategoryIds = snapshot.categories.map { it.id }.toSet()
            val validGroupIds = snapshot.expenseGroups.map { it.id }.toSet()
            val validMemberIds = snapshot.groupMembers.map { it.id }.toSet()
            val validExpenseIds = snapshot.expenses.map { it.id }.toSet()

            supportDb.beginTransaction()
            try {
                importCategories(supportDb, snapshot.categories)
                importExpenses(supportDb, snapshot.expenses, validCategoryIds)
                importExpenseGroups(supportDb, snapshot.expenseGroups)
                importGroupMembers(supportDb, snapshot.groupMembers)
                importGroupExpenses(supportDb, snapshot.groupExpenses, validGroupIds, validMemberIds, validExpenseIds)
                importSplitItemAssignments(supportDb, snapshot.splitAssignments, validExpenseIds)

                supportDb.setTransactionSuccessful()
                log("Import transaction committed successfully")
            } finally {
                supportDb.endTransaction()
            }
        } finally {
            roomDb.close()
        }
    }

    private fun importCategories(db: SupportSQLiteDatabase, categories: List<RescueCategory>) {
        if (categories.isEmpty()) return
        log("Importing ${categories.size} categories")
        val sql = "INSERT OR REPLACE INTO categories (id, name, icon, color, isDefault) VALUES (?, ?, ?, ?, ?)"
        for (cat in categories) {
            db.execSQL(sql, arrayOf(cat.id, cat.name, cat.icon, cat.color, if (cat.isDefault) 1 else 0))
        }
    }

    private fun importExpenses(db: SupportSQLiteDatabase, expenses: List<RescueExpense>, validCategoryIds: Set<Long>) {
        val filtered = expenses.filter { exp ->
            exp.categoryId == null || exp.categoryId in validCategoryIds
        }
        val skippedCount = expenses.size - filtered.size
        if (filtered.isEmpty()) {
            log("No expenses to import after filtering ${skippedCount} with invalid categoryId")
            return
        }
        log("Importing ${filtered.size} expenses (filtered $skippedCount with invalid categoryId)")

        // Discover what columns the fresh expenses table has
        val cols = readSupportColumnNames(db, TABLE_EXPENSES)

        // Build the column set dynamically
        val baseCols = listOf(
            "id", "amount", "currency", "merchant", "transactionType", "date",
            "categoryId", "createdAt", "source", "paymentMethod", "isManualEntry",
            "notes", "transferDirection", "transferAccountName", "isNotMine",
            "ownerName", "isSharedExpense", "sharedWithName", "mySharePercentage",
            "myShareAmount", "splitVisualization",
            // Rules from spec:
            "rawNotificationId", "dedupeKey",
            "baseAmount", "baseCurrency", "exchangeRateUsed"
        )
        val availableCols = baseCols.filter { it in cols }
        val placeholders = availableCols.joinToString(", ") { "?" }
        val columnNames = availableCols.joinToString(", ") { "`$it`" }
        val sql = "INSERT OR REPLACE INTO expenses ($columnNames) VALUES ($placeholders)"

        for (exp in filtered) {
            val values = mutableListOf<Any?>()
            for (col in availableCols) {
                values.add(when (col) {
                    "id" -> exp.id
                    "amount" -> exp.amount
                    "currency" -> exp.currency
                    "merchant" -> exp.merchant
                    "transactionType" -> exp.transactionType
                    "date" -> exp.date
                    "categoryId" -> exp.categoryId
                    "createdAt" -> exp.createdAt
                    "source" -> exp.source
                    "paymentMethod" -> exp.paymentMethod
                    "isManualEntry" -> if (exp.isManualEntry) 1 else 0
                    "notes" -> exp.notes
                    "transferDirection" -> exp.transferDirection
                    "transferAccountName" -> exp.transferAccountName
                    "isNotMine" -> if (exp.isNotMine) 1 else 0
                    "ownerName" -> exp.ownerName
                    "isSharedExpense" -> if (exp.isSharedExpense) 1 else 0
                    "sharedWithName" -> exp.sharedWithName
                    "mySharePercentage" -> exp.mySharePercentage
                    "myShareAmount" -> exp.myShareAmount
                    "splitVisualization" -> exp.splitVisualization
                    // Override by spec:
                    "rawNotificationId" -> null
                    "dedupeKey" -> null
                    "baseAmount" -> exp.amount
                    "baseCurrency" -> exp.currency
                    "exchangeRateUsed" -> 1.0
                    else -> null
                })
            }
            db.execSQL(sql, values.toTypedArray())
        }
    }

    private fun importExpenseGroups(db: SupportSQLiteDatabase, groups: List<RescueExpenseGroup>) {
        if (groups.isEmpty()) return
        log("Importing ${groups.size} expense groups")
        val sql = "INSERT OR REPLACE INTO expense_groups (id, name, description, defaultCurrency, isActive, createdAt, createdBy) VALUES (?, ?, ?, ?, ?, ?, ?)"
        for (g in groups) {
            db.execSQL(sql, arrayOf(g.id, g.name, g.description, g.defaultCurrency, if (g.isActive) 1 else 0, g.createdAt, g.createdBy))
        }
    }

    private fun importGroupMembers(db: SupportSQLiteDatabase, members: List<RescueGroupMember>) {
        if (members.isEmpty()) return

        // Dedupe by (groupId, name) — keep the first occurrence
        val seen = mutableSetOf<Pair<Long, String>>()
        val deduped = mutableListOf<RescueGroupMember>()
        // Ensure only one current user per group: track which groups already have one
        val groupsWithCurrentUser = mutableSetOf<Long>()

        for (m in members) {
            val key = m.groupId to m.name
            if (key in seen) continue
            seen.add(key)

            if (m.isCurrentUser) {
                if (m.groupId in groupsWithCurrentUser) {
                    // Skip duplicate current user for this group
                    continue
                }
                groupsWithCurrentUser.add(m.groupId)
            }
            deduped.add(m)
        }

        val skippedCount = members.size - deduped.size
        if (deduped.isEmpty()) {
            log("No group members to import after filtering $skippedCount duplicates")
            return
        }
        log("Importing ${deduped.size} group members (filtered $skippedCount duplicates)")

        val cols = readSupportColumnNames(db, TABLE_GROUP_MEMBERS)
        val hasCurrentUserKey = "currentUserGroupKey" in cols

        val baseCols = listOf("id", "groupId", "name", "email", "isCurrentUser", "joinedAt")
        val availableCols = if (hasCurrentUserKey) baseCols + "currentUserGroupKey" else baseCols
        val placeholders = availableCols.joinToString(", ") { "?" }
        val columnNames = availableCols.joinToString(", ") { "`$it`" }
        val sql = "INSERT OR REPLACE INTO group_members ($columnNames) VALUES ($placeholders)"

        for (m in deduped) {
            val values = mutableListOf<Any?>()
            values.add(m.id)
            values.add(m.groupId)
            values.add(m.name)
            values.add(m.email)
            values.add(if (m.isCurrentUser) 1 else 0)
            values.add(m.joinedAt)
            if (hasCurrentUserKey) {
                values.add(if (m.isCurrentUser) m.groupId else null)
            }
            db.execSQL(sql, values.toTypedArray())
        }
    }

    private fun importGroupExpenses(
        db: SupportSQLiteDatabase,
        groupExpenses: List<RescueGroupExpense>,
        validGroupIds: Set<Long>,
        validMemberIds: Set<Long>,
        validExpenseIds: Set<Long>
    ) {
        if (groupExpenses.isEmpty()) return

        val seenExpenseIds = mutableSetOf<Long?>()
        val filtered = groupExpenses.filter { ge ->
            // groupId must be valid
            if (ge.groupId !in validGroupIds) return@filter false
            // paidById must be a known member
            if (ge.paidById !in validMemberIds) return@filter false
            // expenseId must be null or in validExpenseIds and unique
            if (ge.expenseId != null) {
                if (ge.expenseId !in validExpenseIds) return@filter false
                if (ge.expenseId in seenExpenseIds) return@filter false
                seenExpenseIds.add(ge.expenseId)
            }
            true
        }

        val skippedCount = groupExpenses.size - filtered.size
        if (filtered.isEmpty()) {
            log("No group expenses to import after filtering $skippedCount with invalid FK")
            return
        }
        log("Importing ${filtered.size} group expenses (filtered $skippedCount with invalid FK)")

        val cols = readSupportColumnNames(db, TABLE_GROUP_EXPENSES)
        val baseCols = listOf(
            "id", "groupId", "expenseId", "paidById", "date", "description",
            "totalAmount", "currency", "splitType", "customSplitsJson",
            "isReimbursable", "reimbursedAmount", "settledAt", "myShareAmount"
        )
        val availableCols = baseCols.filter { it in cols }
        val placeholders = availableCols.joinToString(", ") { "?" }
        val columnNames = availableCols.joinToString(", ") { "`$it`" }
        val sql = "INSERT OR REPLACE INTO group_expenses ($columnNames) VALUES ($placeholders)"

        for (ge in filtered) {
            val values = mutableListOf<Any?>()
            for (col in availableCols) {
                values.add(when (col) {
                    "id" -> ge.id
                    "groupId" -> ge.groupId
                    "expenseId" -> ge.expenseId
                    "paidById" -> ge.paidById
                    "date" -> ge.date
                    "description" -> ge.description
                    "totalAmount" -> ge.totalAmount
                    "currency" -> ge.currency
                    "splitType" -> ge.splitType
                    "customSplitsJson" -> ge.customSplitsJson
                    "isReimbursable" -> if (ge.isReimbursable) 1 else 0
                    "reimbursedAmount" -> ge.reimbursedAmount
                    "settledAt" -> ge.settledAt
                    "myShareAmount" -> ge.myShareAmount
                    else -> null
                })
            }
            db.execSQL(sql, values.toTypedArray())
        }
    }

    private fun importSplitItemAssignments(db: SupportSQLiteDatabase, splits: List<RescueSplitItemAssignment>, validExpenseIds: Set<Long>) {
        if (splits.isEmpty()) return

        val filtered = splits.filter { s -> s.expenseId in validExpenseIds }
        val skippedCount = splits.size - filtered.size
        if (filtered.isEmpty()) {
            log("No split assignments to import after filtering $skippedCount with invalid expenseId")
            return
        }
        log("Importing ${filtered.size} split item assignments (filtered $skippedCount with invalid expenseId)")

        val sql = "INSERT OR REPLACE INTO split_item_assignments (id, expenseId, receiptItemId, participantName, participantIndex, assignedAmount, isPaid, paidAt, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        for (s in filtered) {
            db.execSQL(sql, arrayOf(
                s.id, s.expenseId, s.receiptItemId, s.participantName,
                s.participantIndex, s.assignedAmount, if (s.isPaid) 1 else 0,
                s.paidAt, s.createdAt
            ))
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Backup / move-aside helpers
    // ──────────────────────────────────────────────────────────────

    private fun backupDatabaseFiles(dbFile: File) {
        val backupDir = File(context.filesDir, "db_backups")
        backupDir.mkdirs()
        val timestamp = System.currentTimeMillis()

        for (ext in listOf("", "-wal", "-shm", "-journal")) {
            val src = File(dbFile.absolutePath + ext)
            if (src.exists()) {
                val dst = File(backupDir, "${dbFile.name}${ext}.$timestamp$BACKUP_SUFFIX")
                src.copyTo(dst, overwrite = true)
                log("Backed up ${src.name} → ${dst.name}")
            }
        }
    }

    /** Timestamp used by the most recent [moveDatabaseFilesAside] call. */
    private var moveTimestamp: Long = 0L

    /**
     * Renames database files (.db, -wal, -shm, -journal) to
     * `.legacy.<timestamp>` so they can be restored later if the
     * rescue fails.
     */
    private fun moveDatabaseFilesAside(dbFile: File) {
        moveTimestamp = System.currentTimeMillis()
        for (ext in listOf("", "-wal", "-shm", "-journal")) {
            val src = File(dbFile.absolutePath + ext)
            if (src.exists()) {
                val dst = File(dbFile.absolutePath + ext + ".legacy.$moveTimestamp")
                src.renameTo(dst)
                log("Moved aside ${src.name} → ${dst.name}")
            }
        }
    }

    /**
     * Restores database files that were moved aside by
     * [moveDatabaseFilesAside].  Safe no-op if no files were moved.
     */
    private fun restoreDatabaseFiles(dbFile: File) {
        if (moveTimestamp == 0L) return
        for (ext in listOf("", "-wal", "-shm", "-journal")) {
            val src = File(dbFile.absolutePath + ext + ".legacy.$moveTimestamp")
            if (src.exists()) {
                val dst = File(dbFile.absolutePath + ext)
                src.renameTo(dst)
                log("Restored ${src.name} → ${dst.name}")
            }
        }
        moveTimestamp = 0L
    }

    // ──────────────────────────────────────────────────────────────
    // JSON snapshot (safety net)
    // ──────────────────────────────────────────────────────────────

    private fun writeJsonSnapshot(snapshot: FinancialRescueSnapshot) {
        val json = JSONObject().apply {
            put("oldUserVersion", snapshot.oldUserVersion)
            put("categories", JSONArray().apply {
                snapshot.categories.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("icon", c.icon)
                        put("color", c.color)
                        put("isDefault", c.isDefault)
                    })
                }
            })
            put("expenses", JSONArray().apply {
                snapshot.expenses.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("amount", e.amount)
                        put("currency", e.currency)
                        put("merchant", e.merchant)
                        put("transactionType", e.transactionType)
                        put("date", e.date)
                        put("categoryId", e.categoryId)
                        put("createdAt", e.createdAt)
                        put("source", e.source)
                        put("paymentMethod", e.paymentMethod)
                        put("isManualEntry", e.isManualEntry)
                        put("notes", e.notes)
                        put("transferDirection", e.transferDirection)
                        put("transferAccountName", e.transferAccountName)
                        put("isNotMine", e.isNotMine)
                        put("ownerName", e.ownerName)
                        put("isSharedExpense", e.isSharedExpense)
                        put("sharedWithName", e.sharedWithName)
                        put("mySharePercentage", e.mySharePercentage)
                        put("myShareAmount", e.myShareAmount)
                        put("splitVisualization", e.splitVisualization)
                    })
                }
            })
            put("expenseGroups", JSONArray().apply {
                snapshot.expenseGroups.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id)
                        put("name", g.name)
                        put("description", g.description)
                        put("defaultCurrency", g.defaultCurrency)
                        put("isActive", g.isActive)
                        put("createdAt", g.createdAt)
                        put("createdBy", g.createdBy)
                    })
                }
            })
            put("groupMembers", JSONArray().apply {
                snapshot.groupMembers.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("groupId", m.groupId)
                        put("name", m.name)
                        put("email", m.email)
                        put("isCurrentUser", m.isCurrentUser)
                        put("joinedAt", m.joinedAt)
                    })
                }
            })
            put("groupExpenses", JSONArray().apply {
                snapshot.groupExpenses.forEach { ge ->
                    put(JSONObject().apply {
                        put("id", ge.id)
                        put("groupId", ge.groupId)
                        put("expenseId", ge.expenseId)
                        put("paidById", ge.paidById)
                        put("date", ge.date)
                        put("description", ge.description)
                        put("totalAmount", ge.totalAmount)
                        put("currency", ge.currency)
                        put("splitType", ge.splitType)
                        put("customSplitsJson", ge.customSplitsJson)
                        put("isReimbursable", ge.isReimbursable)
                        put("reimbursedAmount", ge.reimbursedAmount)
                        put("settledAt", ge.settledAt)
                        put("myShareAmount", ge.myShareAmount)
                    })
                }
            })
            put("splitAssignments", JSONArray().apply {
                snapshot.splitAssignments.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("expenseId", s.expenseId)
                        put("receiptItemId", s.receiptItemId)
                        put("participantName", s.participantName)
                        put("participantIndex", s.participantIndex)
                        put("assignedAmount", s.assignedAmount)
                        put("isPaid", s.isPaid)
                        put("paidAt", s.paidAt)
                        put("createdAt", s.createdAt)
                    })
                }
            })
        }

        val snapshotFile = File(context.filesDir, SNAPSHOT_FILENAME)
        FileWriter(snapshotFile).use { writer ->
            json.write(writer)
        }
        log("Wrote JSON snapshot to ${snapshotFile.absolutePath}")
    }

    // ──────────────────────────────────────────────────────────────
    // Rescue done marker
    // ──────────────────────────────────────────────────────────────

    private fun isRescueDone(): Boolean {
        return File(context.filesDir, DONE_MARKER).exists()
    }

    private fun markRescueDone() {
        File(context.filesDir, DONE_MARKER).writeText("Rescue completed at ${System.currentTimeMillis()}")
    }

    // ──────────────────────────────────────────────────────────────
    // Low-level helpers
    // ──────────────────────────────────────────────────────────────

    private fun readUserVersion(dbFile: File): Int {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.version
        } finally {
            db.close()
        }
    }

    /**
     * Returns whether the given table exists in the old database.
     */
    fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(tableName)
        )
        return cursor.use { it.moveToFirst() }
    }

    /**
     * Returns a map of column name → column index for the given table.
     */
    fun columns(db: SQLiteDatabase, tableName: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val cursor = db.rawQuery("PRAGMA table_info(`$tableName`)", null)
        cursor.use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                val name = c.getString(nameIdx)
                // We don't know the index yet — we'll get it from the SELECT * cursor
                result[name] = -1 // placeholder, we'll resolve later
            }
        }
        // Re-resolve from the actual table schema by opening a dummy query
        // Actually we'll resolve on first access. Better approach: use PRAGMA result directly.
        // Let's redo: PRAGMA returns columns in order. But we need the column INDEX
        // in the SELECT * cursor, not the PRAGMA order.
        // So we'll just query a single row and build the map.
        val dummy = db.rawQuery("SELECT * FROM `$tableName` LIMIT 1", null)
        dummy.use { d ->
            result.clear()
            for (i in 0 until d.columnCount) {
                result[d.getColumnName(i)] = i
            }
        }
        return result
    }

    /**
     * Returns column names from a [SupportSQLiteDatabase] (used for import).
     */
    private fun readSupportColumnNames(db: SupportSQLiteDatabase, tableName: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return names
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIndex)
            }
        }
        return names
    }

    // Null-safe accessors

    private fun stringOrNull(cursor: android.database.Cursor, colIndex: Int): String? {
        if (colIndex < 0) return null
        return if (cursor.isNull(colIndex)) null else cursor.getString(colIndex)
    }

    private fun longOrNull(cursor: android.database.Cursor, colIndex: Int): Long? {
        if (colIndex < 0) return null
        return if (cursor.isNull(colIndex)) null else cursor.getLong(colIndex)
    }

    private fun doubleOrNull(cursor: android.database.Cursor, colIndex: Int): Double? {
        if (colIndex < 0) return null
        return if (cursor.isNull(colIndex)) null else cursor.getDouble(colIndex)
    }

    private fun intOrNull(cursor: android.database.Cursor, colIndex: Int): Int? {
        if (colIndex < 0) return null
        return if (cursor.isNull(colIndex)) null else cursor.getInt(colIndex)
    }

    private fun boolOrDefault(
        cursor: android.database.Cursor,
        colMap: Map<String, Int>,
        colName: String,
        default: Boolean
    ): Boolean {
        val idx = colMap[colName] ?: return default
        if (idx < 0) return default
        return if (cursor.isNull(idx)) default else cursor.getInt(idx) != 0
    }

    // ──────────────────────────────────────────────────────────────
    // Sanitizers
    // ──────────────────────────────────────────────────────────────

    private fun sanitizeCurrency(value: String): String {
        val trimmed = value.trim().uppercase()
        if (trimmed.length == 3 && trimmed.all { it.isLetter() }) return trimmed
        return "EUR"
    }

    private fun sanitizeTransactionType(value: String): String {
        val upper = value.trim().uppercase()
        return when (upper) {
            "PURCHASE", "WITHDRAWAL", "TRANSFER", "DEPOSIT", "UNKNOWN" -> upper
            else -> "UNKNOWN"
        }
    }

    private fun sanitizePaymentMethod(value: String): String {
        val upper = value.trim().uppercase()
        return when (upper) {
            "CARD", "CASH", "BANK_TRANSFER", "UNKNOWN" -> upper
            else -> "UNKNOWN"
        }
    }

    private fun sanitizeTransferDirection(value: String?): String? {
        if (value == null) return null
        val upper = value.trim().uppercase()
        return when (upper) {
            "INCOMING", "OUTGOING" -> upper
            else -> null
        }
    }

    private fun sanitizeSplitType(value: String): String {
        val upper = value.trim().uppercase()
        return when (upper) {
            "EQUAL", "CUSTOM_AMOUNT", "CUSTOM_PERCENT", "UNEQUAL" -> upper
            else -> "EQUAL"
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Logging
    // ──────────────────────────────────────────────────────────────

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            android.util.Log.e(TAG, message, throwable)
        } else {
            android.util.Log.i(TAG, message)
        }
    }
}

/** Result of a rescue attempt. */
sealed class RescueResult {
    data object SUCCESS : RescueResult()
    data object SKIPPED : RescueResult()
    data object ALREADY_DONE : RescueResult()
    data object NO_DB : RescueResult()
    data class FAILURE(val error: Throwable) : RescueResult()
}
