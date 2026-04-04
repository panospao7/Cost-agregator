package com.yourname.expensetracker.ui.screens.debug

typealias DebugData = com.yourname.expensetracker.domain.debug.DebugData
typealias DebugIssue = com.yourname.expensetracker.domain.debug.DebugIssue
typealias IssueSeverity = com.yourname.expensetracker.domain.debug.IssueSeverity

@Deprecated(
    message = "Use com.yourname.expensetracker.domain.debug.DebugIssueDetector instead",
    replaceWith = ReplaceWith("DebugIssueDetector", "com.yourname.expensetracker.domain.debug.DebugIssueDetector")
)
typealias DebugIssueDetector = com.yourname.expensetracker.domain.debug.DebugIssueDetector
