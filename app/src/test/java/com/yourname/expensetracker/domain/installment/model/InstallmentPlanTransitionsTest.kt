package com.yourname.expensetracker.domain.installment.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate
import org.junit.Test
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus as ES
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus as PS
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode as Code
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason as Reason

/**
 * Pure unit tests of [InstallmentPlanTransitions]. The oracle is the loan-installments domain
 * spec: section 3.1 (plan table), section 3.2 (entry table), INV-12 to INV-14 (auto-record),
 * INV-19 (paid entries immutable), INV-20 (terminal plans), INV-21 (automatic completion) and
 * INV-32 (PAID_BEFORE_TRACKING, OQ-18), with the examples AE-14, AE-16, AE-26, AE-27 and AE-28.
 *
 * Every cell of both tables is exercised, the allowed ones and the refused ones. Matrix helpers
 * take the expected result as a literal map from the spec; every cell missing from a map is
 * expected to be refused. Moves are compared without their stored reason unless a test is about
 * the reason. No test reads a clock or a default zone.
 */
class InstallmentPlanTransitionsTest {

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    private val declaredOn = day(2027, 6, 20)
    private val dueToday = day(2027, 7, 5)

    private val illegal = PlanTransitionResult.Illegal(Code.ILLEGAL_TRANSITION)
    private val illegalEntry = EntryTransitionResult.Illegal(Code.ILLEGAL_TRANSITION)
    private val invalidPbt = EntryTransitionResult.Illegal(Code.INVALID_PAID_BEFORE_TRACKING)

    // ---------------------------------------------------------------------------------------
    // Plan table (3.1)
    // ---------------------------------------------------------------------------------------

    private fun assertPlanColumn(trigger: PlanTrigger, expected: Map<PS, PlanTransitionResult>) {
        // Every plan status must have an explicit expectation: no cell is left untested.
        assertThat(expected.keys).containsExactlyElementsIn(PS.entries)
        for ((from, want) in expected) {
            assertWithMessage("plan transition from %s on %s", from, trigger)
                .that(InstallmentPlanTransitions.planTransition(from, trigger))
                .isEqualTo(want)
        }
    }

    @Test
    fun plan_table_STOP_moves_only_ACTIVE_to_CANCELLED_with_USER_STOP() {
        assertPlanColumn(
            PlanTrigger.STOP,
            mapOf(
                PS.ACTIVE to PlanTransitionResult.Move(PS.CANCELLED, Reason.USER_STOP),
                PS.COMPLETED to illegal,
                PS.CANCELLED to PlanTransitionResult.AlreadyInState(PS.CANCELLED),
                PS.REFUNDED to illegal,
                PS.PAID_OFF to illegal
            )
        )
    }

    @Test
    fun plan_table_REFUND_moves_ACTIVE_and_COMPLETED_to_REFUNDED_with_USER_REFUND() {
        assertPlanColumn(
            PlanTrigger.REFUND,
            mapOf(
                PS.ACTIVE to PlanTransitionResult.Move(PS.REFUNDED, Reason.USER_REFUND),
                PS.COMPLETED to PlanTransitionResult.Move(PS.REFUNDED, Reason.USER_REFUND),
                PS.CANCELLED to illegal,
                PS.REFUNDED to PlanTransitionResult.AlreadyInState(PS.REFUNDED),
                PS.PAID_OFF to illegal
            )
        )
    }

    @Test
    fun plan_table_PAY_OFF_moves_only_ACTIVE_to_PAID_OFF_and_is_illegal_from_COMPLETED() {
        assertPlanColumn(
            PlanTrigger.PAY_OFF,
            mapOf(
                PS.ACTIVE to PlanTransitionResult.Move(PS.PAID_OFF, Reason.USER_PAYOFF),
                PS.COMPLETED to illegal,
                PS.CANCELLED to illegal,
                PS.REFUNDED to illegal,
                PS.PAID_OFF to PlanTransitionResult.AlreadyInState(PS.PAID_OFF)
            )
        )
    }

    @Test
    fun plan_table_AUTO_COMPLETE_moves_only_ACTIVE_to_COMPLETED_with_AUTO_COMPLETED() {
        assertPlanColumn(
            PlanTrigger.AUTO_COMPLETE,
            mapOf(
                PS.ACTIVE to PlanTransitionResult.Move(PS.COMPLETED, Reason.AUTO_COMPLETED),
                PS.COMPLETED to PlanTransitionResult.AlreadyInState(PS.COMPLETED),
                PS.CANCELLED to illegal,
                PS.REFUNDED to illegal,
                PS.PAID_OFF to illegal
            )
        )
    }

    @Test
    fun plan_table_REOPEN_moves_only_COMPLETED_back_to_ACTIVE() {
        assertPlanColumn(
            PlanTrigger.REOPEN,
            mapOf(
                PS.ACTIVE to PlanTransitionResult.AlreadyInState(PS.ACTIVE),
                PS.COMPLETED to PlanTransitionResult.Move(PS.ACTIVE, null),
                PS.CANCELLED to illegal,
                PS.REFUNDED to illegal,
                PS.PAID_OFF to illegal
            )
        )
    }

    @Test
    fun plan_table_DELETE_without_paid_or_reversed_entries_is_allowed_only_for_ACTIVE_and_CANCELLED() {
        // Spec 3.1 and 7.5: Delete is allowed only from ACTIVE, or CANCELLED without payments.
        for (from in listOf(PS.ACTIVE, PS.CANCELLED)) {
            assertWithMessage("delete from %s without payments", from)
                .that(InstallmentPlanTransitions.planTransition(from, PlanTrigger.DELETE, hasPaidOrReversedEntries = false))
                .isEqualTo(PlanTransitionResult.Delete)
        }
    }

    @Test
    fun plan_table_DELETE_without_payments_is_illegal_from_COMPLETED_REFUNDED_and_PAID_OFF() {
        for (from in listOf(PS.COMPLETED, PS.REFUNDED, PS.PAID_OFF)) {
            assertWithMessage("delete from %s without payments", from)
                .that(InstallmentPlanTransitions.planTransition(from, PlanTrigger.DELETE, hasPaidOrReversedEntries = false))
                .isEqualTo(illegal)
        }
    }

    @Test
    fun plan_table_DELETE_with_a_paid_or_reversed_entry_is_refused_PLAN_HAS_PAYMENTS_from_every_status() {
        for (from in PS.entries) {
            assertWithMessage("delete from %s with payments", from)
                .that(InstallmentPlanTransitions.planTransition(from, PlanTrigger.DELETE, hasPaidOrReversedEntries = true))
                .isEqualTo(PlanTransitionResult.Illegal(Code.PLAN_HAS_PAYMENTS))
        }
    }

    @Test
    fun AE14_delete_of_an_active_plan_without_payments_is_the_undo_and_with_one_paid_entry_is_refused() {
        assertThat(InstallmentPlanTransitions.planTransition(PS.ACTIVE, PlanTrigger.DELETE, false))
            .isEqualTo(PlanTransitionResult.Delete)
        assertThat(InstallmentPlanTransitions.planTransition(PS.ACTIVE, PlanTrigger.DELETE, true))
            .isEqualTo(PlanTransitionResult.Illegal(Code.PLAN_HAS_PAYMENTS))
    }

    @Test
    fun AE28_delete_after_stop_is_allowed_because_paid_before_tracking_entries_are_not_payments() {
        // The caller derives the flag from PAID and REVERSED entries only (INV-20). With 4
        // PAID_BEFORE_TRACKING and 8 CANCELLED entries that flag is false.
        assertThat(InstallmentPlanTransitions.planTransition(PS.CANCELLED, PlanTrigger.DELETE, hasPaidOrReversedEntries = false))
            .isEqualTo(PlanTransitionResult.Delete)
    }

    // Interpretation 3 (handoff S5, reviewer fix): spec 3.1 and 7.5 name ACTIVE and CANCELLED as the
    // only statuses Delete is allowed from, so a COMPLETED plan without payments (every entry
    // skipped or paid before tracking) is refused. This is the fail-closed reading; owner
    // confirmation is pending. Payments are checked first, so a COMPLETED plan with payments is
    // PLAN_HAS_PAYMENTS.
    @Test
    fun interpretation_3_delete_of_a_COMPLETED_plan_is_refused_ILLEGAL_TRANSITION_without_payments() {
        assertThat(InstallmentPlanTransitions.planTransition(PS.COMPLETED, PlanTrigger.DELETE, hasPaidOrReversedEntries = false))
            .isEqualTo(illegal)
        assertThat(InstallmentPlanTransitions.planTransition(PS.COMPLETED, PlanTrigger.DELETE, hasPaidOrReversedEntries = true))
            .isEqualTo(PlanTransitionResult.Illegal(Code.PLAN_HAS_PAYMENTS))
    }

    @Test
    fun INV_20_terminal_plans_never_move_on_any_trigger_except_delete() {
        val nonDelete = PlanTrigger.entries.filter { it != PlanTrigger.DELETE }
        for (terminal in listOf(PS.CANCELLED, PS.REFUNDED, PS.PAID_OFF)) {
            assertThat(terminal.isTerminal).isTrue()
            for (trigger in nonDelete) {
                val result = InstallmentPlanTransitions.planTransition(terminal, trigger)
                assertWithMessage("terminal %s on %s", terminal, trigger)
                    .that(result)
                    .isNotInstanceOf(PlanTransitionResult.Move::class.java)
                assertWithMessage("terminal %s on %s", terminal, trigger)
                    .that(result)
                    .isNotEqualTo(PlanTransitionResult.Delete)
            }
        }
    }

    @Test
    fun INV_20_only_COMPLETED_may_reopen_and_ACTIVE_and_COMPLETED_are_not_terminal() {
        assertThat(PS.ACTIVE.isTerminal).isFalse()
        assertThat(PS.COMPLETED.isTerminal).isFalse()
        val movers = PS.entries.filter {
            InstallmentPlanTransitions.planTransition(it, PlanTrigger.REOPEN) is PlanTransitionResult.Move
        }
        assertThat(movers).containsExactly(PS.COMPLETED)
    }

    // ---------------------------------------------------------------------------------------
    // INV-21: automatic completion and reopen after an entry change
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_21_an_ACTIVE_plan_with_zero_scheduled_entries_becomes_COMPLETED() {
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.ACTIVE, 0)).isEqualTo(PS.COMPLETED)
    }

    @Test
    fun INV_21_an_ACTIVE_plan_with_at_least_one_scheduled_entry_stays_ACTIVE() {
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.ACTIVE, 1)).isEqualTo(PS.ACTIVE)
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.ACTIVE, 8)).isEqualTo(PS.ACTIVE)
    }

    @Test
    fun INV_21_AE27_a_declaration_that_leaves_no_scheduled_entry_completes_the_plan() {
        // 3 of 3 entries PAID_BEFORE_TRACKING: PAID_BEFORE_TRACKING counts as non-SCHEDULED.
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.ACTIVE, scheduledEntryCount = 0))
            .isEqualTo(PS.COMPLETED)
    }

    @Test
    fun INV_21_AE11_a_COMPLETED_plan_with_a_scheduled_entry_reopens_to_ACTIVE() {
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.COMPLETED, 1)).isEqualTo(PS.ACTIVE)
    }

    @Test
    fun INV_21_a_COMPLETED_plan_with_zero_scheduled_entries_stays_COMPLETED() {
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.COMPLETED, 0)).isEqualTo(PS.COMPLETED)
    }

    @Test
    fun INV_21_INV_20_terminal_plans_never_change_after_an_entry_change() {
        for (terminal in listOf(PS.CANCELLED, PS.REFUNDED, PS.PAID_OFF)) {
            for (scheduled in listOf(0, 1, 5)) {
                assertWithMessage("terminal %s with %s scheduled", terminal, scheduled)
                    .that(InstallmentPlanTransitions.planStatusAfterEntryChange(terminal, scheduled))
                    .isEqualTo(terminal)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Plan-ending operations acting on entries (3.1 effect column, 7.1 to 7.4, INV-32)
    // ---------------------------------------------------------------------------------------

    private fun assertEffectColumn(operation: PlanEntryOperation, expected: Map<ES, EntryEffect>) {
        for (status in ES.entries) {
            assertWithMessage("%s on an entry in %s", operation, status)
                .that(InstallmentPlanTransitions.entryEffect(operation, status))
                .isEqualTo(expected[status])
        }
    }

    @Test
    fun AE7_stop_cancels_SCHEDULED_with_PLAN_STOPPED_and_leaves_every_other_status_untouched() {
        assertEffectColumn(
            PlanEntryOperation.STOP,
            mapOf(ES.SCHEDULED to EntryEffect(ES.CANCELLED, Reason.PLAN_STOPPED))
        )
    }

    @Test
    fun AE8_refund_cancels_SCHEDULED_with_PLAN_REFUNDED_and_reverses_PAID_flagging_the_expense() {
        assertEffectColumn(
            PlanEntryOperation.REFUND,
            mapOf(
                ES.SCHEDULED to EntryEffect(ES.CANCELLED, Reason.PLAN_REFUNDED),
                ES.PAID to EntryEffect(ES.REVERSED, null, flagExpenseReversed = true)
            )
        )
    }

    @Test
    fun AE9_pay_off_cancels_SCHEDULED_with_PAID_OFF_EARLY_and_leaves_every_other_status_untouched() {
        assertEffectColumn(
            PlanEntryOperation.PAY_OFF,
            mapOf(ES.SCHEDULED to EntryEffect(ES.CANCELLED, Reason.PAID_OFF_EARLY))
        )
    }

    @Test
    fun shorten_cancels_SCHEDULED_with_SHORTENED_and_leaves_every_other_status_untouched() {
        assertEffectColumn(
            PlanEntryOperation.SHORTEN,
            mapOf(ES.SCHEDULED to EntryEffect(ES.CANCELLED, Reason.SHORTENED))
        )
    }

    @Test
    fun INV_32_PAID_BEFORE_TRACKING_entries_are_untouched_by_stop_refund_pay_off_and_shorten() {
        for (operation in PlanEntryOperation.entries) {
            assertWithMessage("%s on PAID_BEFORE_TRACKING", operation)
                .that(InstallmentPlanTransitions.entryEffect(operation, ES.PAID_BEFORE_TRACKING))
                .isNull()
        }
    }

    @Test
    fun INV_19_only_refund_touches_a_PAID_entry_and_it_flags_the_expense_in_the_same_transition() {
        for (operation in PlanEntryOperation.entries) {
            val effect = InstallmentPlanTransitions.entryEffect(operation, ES.PAID)
            if (operation == PlanEntryOperation.REFUND) {
                assertThat(effect?.flagExpenseReversed).isTrue()
            } else {
                assertWithMessage("%s on PAID", operation).that(effect).isNull()
            }
        }
    }

    @Test
    fun only_the_refund_effect_flags_a_linked_expense_reversed() {
        for (operation in PlanEntryOperation.entries) {
            for (status in ES.entries) {
                val effect = InstallmentPlanTransitions.entryEffect(operation, status) ?: continue
                assertWithMessage("%s on %s", operation, status)
                    .that(effect.flagExpenseReversed)
                    .isEqualTo(operation == PlanEntryOperation.REFUND && status == ES.PAID)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Entry table (3.2): context helpers
    // ---------------------------------------------------------------------------------------

    private fun ctx(
        plan: PS = PS.ACTIVE,
        status: ES = ES.SCHEDULED,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR,
        linked: Boolean = status == ES.PAID || status == ES.REVERSED,
        due: Long = dueToday,
        declared: Long = declaredOn,
        consumed: Boolean = false,
        today: Long? = null,
        mode: InstallmentFulfilmentMode? = null,
        skip: Reason? = null
    ) = EntryTransitionContext(
        planStatus = plan,
        entryKind = kind,
        entryStatus = status,
        hasLinkedExpense = linked,
        dueEpochDay = due,
        declaredOnEpochDay = declared,
        autoRecordConsumed = consumed,
        todayEpochDay = today,
        fulfilmentMode = mode,
        skipReason = skip
    )

    /** Context in which a transition of [trigger] is meaningful for an entry in [status]. */
    private fun ctxFor(trigger: EntryTrigger, plan: PS, status: ES): EntryTransitionContext = when (trigger) {
        EntryTrigger.AUTO_RECORD -> ctx(plan, status, today = dueToday, mode = InstallmentFulfilmentMode.AUTO_RECORD)
        EntryTrigger.MARK_PAID_BEFORE_TRACKING,
        EntryTrigger.REVERT_PAID_BEFORE_TRACKING -> ctx(plan, status, due = declaredOn - 1)

        // INV-14: CONFIRM needs a known today with due <= today; the default entry is due today.
        EntryTrigger.CONFIRM -> ctx(plan, status, today = dueToday)
        EntryTrigger.SKIP -> ctx(plan, status, skip = Reason.USER_SKIP_LENDER_HOLIDAY)

        else -> ctx(plan, status)
    }

    private fun normalized(result: EntryTransitionResult): EntryTransitionResult =
        if (result is EntryTransitionResult.Move) result.copy(reason = null) else result

    private fun run(context: EntryTransitionContext, trigger: EntryTrigger): EntryTransitionResult =
        InstallmentPlanTransitions.entryTransition(context, trigger)

    /** One column of the entry table in a fixed plan status, over every entry status. */
    private fun assertEntryStatusColumn(
        trigger: EntryTrigger,
        plan: PS,
        expected: Map<ES, EntryTransitionResult>,
        refusal: EntryTransitionResult = illegalEntry
    ) {
        for (status in ES.entries) {
            assertWithMessage("trigger %s in a %s plan on an entry in %s", trigger, plan, status)
                .that(normalized(run(ctxFor(trigger, plan, status), trigger)))
                .isEqualTo(expected[status] ?: refusal)
        }
    }

    /** One row of the entry table for an entry in [entry], over every plan status. */
    private fun assertPlanStatusRow(
        trigger: EntryTrigger,
        entry: ES,
        expected: Map<PS, EntryTransitionResult>,
        skipPlans: Set<PS> = emptySet(),
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR
    ) {
        for (plan in PS.entries) {
            if (plan in skipPlans) continue
            val base = ctxFor(trigger, plan, entry)
            assertWithMessage("trigger %s on an entry in %s in a %s plan", trigger, entry, plan)
                .that(normalized(run(base.copy(entryKind = kind), trigger)))
                .isEqualTo(expected[plan] ?: illegalEntry)
        }
    }

    private fun move(
        to: ES,
        delta: Int = 0,
        consumed: Boolean = false
    ) = EntryTransitionResult.Move(to, recordGenerationDelta = delta, reason = null, setsAutoRecordConsumed = consumed)

    private fun already(status: ES) = EntryTransitionResult.AlreadyInState(status)

    // ---------------------------------------------------------------------------------------
    // SCHEDULED to PAID: auto-record, confirm, link
    // ---------------------------------------------------------------------------------------

    @Test
    fun entry_table_AUTO_RECORD_over_entry_status_in_an_ACTIVE_plan() {
        assertEntryStatusColumn(
            EntryTrigger.AUTO_RECORD,
            PS.ACTIVE,
            mapOf(
                ES.SCHEDULED to move(ES.PAID, consumed = true),
                ES.PAID to already(ES.PAID)
            )
        )
    }

    @Test
    fun entry_table_AUTO_RECORD_over_plan_status_for_a_scheduled_entry() {
        assertPlanStatusRow(
            EntryTrigger.AUTO_RECORD,
            ES.SCHEDULED,
            mapOf(PS.ACTIVE to move(ES.PAID, consumed = true))
        )
    }

    @Test
    fun entry_table_CONFIRM_over_entry_status_in_an_ACTIVE_plan() {
        assertEntryStatusColumn(
            EntryTrigger.CONFIRM,
            PS.ACTIVE,
            mapOf(
                ES.SCHEDULED to move(ES.PAID),
                ES.PAID to already(ES.PAID)
            )
        )
    }

    @Test
    fun entry_table_CONFIRM_over_plan_status_for_a_scheduled_entry() {
        assertPlanStatusRow(EntryTrigger.CONFIRM, ES.SCHEDULED, mapOf(PS.ACTIVE to move(ES.PAID)))
    }

    @Test
    fun INV_14_confirm_of_an_entry_due_tomorrow_is_refused() {
        assertThat(run(ctx(due = dueToday + 1, today = dueToday), EntryTrigger.CONFIRM)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_14_confirm_of_an_entry_due_today_or_earlier_is_allowed() {
        assertThat(normalized(run(ctx(due = dueToday, today = dueToday), EntryTrigger.CONFIRM))).isEqualTo(move(ES.PAID))
        assertThat(normalized(run(ctx(due = dueToday - 30, today = dueToday), EntryTrigger.CONFIRM))).isEqualTo(move(ES.PAID))
    }

    @Test
    fun INV_14_confirm_fails_closed_when_today_is_unknown() {
        assertThat(run(ctx(due = dueToday - 30, today = null), EntryTrigger.CONFIRM)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_14_link_of_a_future_due_entry_stays_allowed() {
        // LINK attaches an expense that already exists, so the entry due date does not gate it.
        assertThat(normalized(run(ctx(due = dueToday + 30, today = null), EntryTrigger.LINK))).isEqualTo(move(ES.PAID))
    }

    @Test
    fun entry_table_LINK_over_entry_status_in_an_ACTIVE_plan_a_second_link_on_PAID_is_illegal() {
        assertEntryStatusColumn(
            EntryTrigger.LINK,
            PS.ACTIVE,
            mapOf(ES.SCHEDULED to move(ES.PAID))
        )
    }

    @Test
    fun entry_table_LINK_over_plan_status_for_a_scheduled_entry() {
        assertPlanStatusRow(EntryTrigger.LINK, ES.SCHEDULED, mapOf(PS.ACTIVE to move(ES.PAID)))
    }

    @Test
    fun AE13_a_second_link_on_a_PAID_entry_is_illegal_not_idempotent() {
        assertThat(run(ctx(status = ES.PAID), EntryTrigger.LINK)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_32_confirm_and_link_on_a_PAID_BEFORE_TRACKING_entry_are_illegal() {
        for (trigger in listOf(EntryTrigger.CONFIRM, EntryTrigger.LINK)) {
            assertWithMessage("%s on PAID_BEFORE_TRACKING", trigger)
                .that(run(ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1, today = dueToday), trigger))
                .isEqualTo(illegalEntry)
        }
    }

    @Test
    fun AE16_after_the_auto_recorded_expense_was_deleted_the_entry_is_SCHEDULED_and_consumed_so_auto_record_is_illegal() {
        val reopened = ctx(
            status = ES.SCHEDULED,
            consumed = true,
            today = dueToday,
            mode = InstallmentFulfilmentMode.AUTO_RECORD
        )

        assertThat(run(reopened, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
        // The user can still resolve it by hand.
        assertThat(normalized(run(reopened, EntryTrigger.CONFIRM))).isEqualTo(move(ES.PAID))
        assertThat(normalized(run(reopened.copy(skipReason = Reason.USER_SKIP_WAIVED), EntryTrigger.SKIP)))
            .isEqualTo(move(ES.SKIPPED))
    }

    @Test
    fun INV_12_auto_record_is_allowed_exactly_on_the_declaration_date_and_exactly_today() {
        val auto = InstallmentFulfilmentMode.AUTO_RECORD

        val onDeclaredOn = ctx(due = declaredOn, today = dueToday, mode = auto)
        val onToday = ctx(due = dueToday, today = dueToday, mode = auto)

        assertThat(normalized(run(onDeclaredOn, EntryTrigger.AUTO_RECORD))).isEqualTo(move(ES.PAID, consumed = true))
        assertThat(normalized(run(onToday, EntryTrigger.AUTO_RECORD))).isEqualTo(move(ES.PAID, consumed = true))
    }

    @Test
    fun INV_12_AE26_auto_record_is_refused_for_an_entry_due_one_day_before_the_declaration_date() {
        val beforeDeclared = ctx(
            due = declaredOn - 1,
            today = dueToday,
            mode = InstallmentFulfilmentMode.AUTO_RECORD
        )

        assertThat(run(beforeDeclared, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_14_auto_record_is_refused_for_an_entry_due_one_day_after_today() {
        val future = ctx(
            due = dueToday + 1,
            today = dueToday,
            mode = InstallmentFulfilmentMode.AUTO_RECORD
        )

        assertThat(run(future, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_12_auto_record_is_refused_for_a_CONFIRM_mode_plan() {
        val confirmMode = ctx(today = dueToday, mode = InstallmentFulfilmentMode.CONFIRM)

        assertThat(run(confirmMode, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_12_auto_record_fails_closed_when_today_or_the_mode_is_unknown() {
        val noToday = ctx(today = null, mode = InstallmentFulfilmentMode.AUTO_RECORD)
        val noMode = ctx(today = dueToday, mode = null)

        assertThat(run(noToday, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
        assertThat(run(noMode, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_12_auto_record_is_refused_for_a_PAYOFF_entry() {
        val payoff = ctx(
            kind = InstallmentEntryKind.PAYOFF,
            today = dueToday,
            mode = InstallmentFulfilmentMode.AUTO_RECORD
        )

        assertThat(run(payoff, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_32_AE27_auto_record_never_applies_to_a_reverted_entry_because_it_is_due_before_the_declaration_date() {
        // Entry 3 reverted from PAID_BEFORE_TRACKING: SCHEDULED again, due 2027-04-05, not consumed.
        val reverted = ctx(
            status = ES.SCHEDULED,
            due = day(2027, 4, 5),
            consumed = false,
            today = day(2027, 6, 21),
            mode = InstallmentFulfilmentMode.AUTO_RECORD
        )

        assertThat(run(reverted, EntryTrigger.AUTO_RECORD)).isEqualTo(illegalEntry)
    }

    // ---------------------------------------------------------------------------------------
    // autoRecordDecision (INV-12, INV-13, INV-14, INV-32)
    // ---------------------------------------------------------------------------------------

    private fun autoCtx(
        plan: PS = PS.ACTIVE,
        status: ES = ES.SCHEDULED,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR,
        linked: Boolean = false,
        due: Long = dueToday,
        consumed: Boolean = false,
        today: Long? = dueToday,
        mode: InstallmentFulfilmentMode? = InstallmentFulfilmentMode.AUTO_RECORD
    ) = ctx(plan, status, kind, linked, due, declaredOn, consumed, today, mode)

    @Test
    fun INV_13_AE17_a_candidate_charge_defers_auto_record_for_this_run() {
        assertThat(InstallmentPlanTransitions.autoRecordDecision(autoCtx(), hasCandidateCharge = true))
            .isEqualTo(AutoRecordDecision.Defer(InstallmentDeferralReason.AUTO_RECORD_DEFERRED_CANDIDATE))
    }

    @Test
    fun INV_12_control_without_a_candidate_charge_the_entry_is_recorded() {
        assertThat(InstallmentPlanTransitions.autoRecordDecision(autoCtx(), hasCandidateCharge = false))
            .isEqualTo(AutoRecordDecision.Record)
    }

    @Test
    fun INV_12_a_candidate_charge_does_not_matter_when_auto_record_does_not_apply_at_all() {
        val notApplicable = listOf(
            "CONFIRM mode" to autoCtx(mode = InstallmentFulfilmentMode.CONFIRM),
            "due before declaredOn" to autoCtx(due = declaredOn - 1),
            "due after today" to autoCtx(due = dueToday + 1),
            "already consumed" to autoCtx(consumed = true),
            "linked" to autoCtx(linked = true),
            "PAYOFF kind" to autoCtx(kind = InstallmentEntryKind.PAYOFF),
            "PAID_BEFORE_TRACKING" to autoCtx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1),
            "SKIPPED" to autoCtx(status = ES.SKIPPED),
            "unknown today" to autoCtx(today = null),
            "unknown mode" to autoCtx(mode = null)
        ) + listOf(PS.COMPLETED, PS.CANCELLED, PS.REFUNDED, PS.PAID_OFF).map { "plan $it" to autoCtx(plan = it) }

        for ((label, context) in notApplicable) {
            for (candidate in listOf(false, true)) {
                assertWithMessage("%s with candidate=%s", label, candidate)
                    .that(InstallmentPlanTransitions.autoRecordDecision(context, candidate))
                    .isEqualTo(AutoRecordDecision.NotApplicable)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // PAID to SCHEDULED: unlink and expense removed
    // ---------------------------------------------------------------------------------------

    @Test
    fun entry_table_UNLINK_over_entry_status_in_an_ACTIVE_plan() {
        assertEntryStatusColumn(
            EntryTrigger.UNLINK,
            PS.ACTIVE,
            mapOf(
                ES.PAID to move(ES.SCHEDULED, delta = 1),
                ES.SCHEDULED to already(ES.SCHEDULED)
            )
        )
    }

    @Test
    fun entry_table_UNLINK_over_plan_status_for_a_paid_entry_only_ACTIVE_and_COMPLETED_reopen_it() {
        assertPlanStatusRow(
            EntryTrigger.UNLINK,
            ES.PAID,
            mapOf(
                PS.ACTIVE to move(ES.SCHEDULED, delta = 1),
                PS.COMPLETED to move(ES.SCHEDULED, delta = 1)
            )
        )
    }

    @Test
    fun entry_table_EXPENSE_REMOVED_over_entry_status_in_an_ACTIVE_plan() {
        assertEntryStatusColumn(
            EntryTrigger.EXPENSE_REMOVED,
            PS.ACTIVE,
            mapOf(
                ES.PAID to move(ES.SCHEDULED, delta = 1),
                ES.SCHEDULED to already(ES.SCHEDULED),
                ES.CANCELLED to already(ES.CANCELLED)
            )
        )
    }

    @Test
    fun entry_table_EXPENSE_REMOVED_reopening_a_paid_entry_carries_no_reason_exact() {
        for (plan in listOf(PS.ACTIVE, PS.COMPLETED)) {
            assertWithMessage("plan %s", plan)
                .that(run(ctx(plan = plan, status = ES.PAID), EntryTrigger.EXPENSE_REMOVED))
                .isEqualTo(move(ES.SCHEDULED, delta = 1))
        }
    }

    @Test
    fun entry_table_EXPENSE_REMOVED_over_plan_status_for_a_paid_entry() {
        assertPlanStatusRow(
            EntryTrigger.EXPENSE_REMOVED,
            ES.PAID,
            mapOf(
                PS.ACTIVE to move(ES.SCHEDULED, delta = 1),
                PS.COMPLETED to move(ES.SCHEDULED, delta = 1),
                PS.CANCELLED to move(ES.CANCELLED),
                PS.PAID_OFF to move(ES.CANCELLED)
                // REFUNDED: a PAID entry cannot exist there (refund reverses them), so it is refused.
            )
        )
    }

    @Test
    fun AE11_a_removed_expense_in_a_COMPLETED_plan_reopens_the_entry_and_the_plan() {
        val result = run(ctx(plan = PS.COMPLETED, status = ES.PAID), EntryTrigger.EXPENSE_REMOVED)

        assertThat(normalized(result)).isEqualTo(move(ES.SCHEDULED, delta = 1))
        // Exact equality, no normalization: the reopened SCHEDULED entry carries no reason.
        assertThat(result).isEqualTo(move(ES.SCHEDULED, delta = 1))
        // One SCHEDULED entry now exists, so the plan goes back to ACTIVE in the same transaction.
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.COMPLETED, 1)).isEqualTo(PS.ACTIVE)
    }

    // Spec 3.2 and R4: a currency or type change of the linked expense is the same EXPENSE_REMOVED
    // trigger as a delete, and the CANCELLED target in a terminal plan carries EXPENSE_REMOVED for
    // every detach cause.
    @Test
    fun entry_table_EXPENSE_REMOVED_in_a_terminal_plan_cancels_with_the_bounded_reason_EXPENSE_REMOVED() {
        for (plan in listOf(PS.CANCELLED, PS.PAID_OFF)) {
            assertWithMessage("plan %s", plan)
                .that(run(ctx(plan = plan, status = ES.PAID), EntryTrigger.EXPENSE_REMOVED))
                .isEqualTo(
                    EntryTransitionResult.Move(ES.CANCELLED, recordGenerationDelta = 0, reason = Reason.EXPENSE_REMOVED)
                )
        }
    }

    @Test
    fun entry_table_a_PAYOFF_entry_whose_expense_is_removed_in_a_PAID_OFF_plan_is_cancelled() {
        val result = run(
            ctx(plan = PS.PAID_OFF, status = ES.PAID, kind = InstallmentEntryKind.PAYOFF),
            EntryTrigger.EXPENSE_REMOVED
        )

        assertThat(result)
            .isEqualTo(EntryTransitionResult.Move(ES.CANCELLED, recordGenerationDelta = 0, reason = Reason.EXPENSE_REMOVED))
    }

    // Spec 3.2 and R4: a currency or type change of the linked expense is the same EXPENSE_REMOVED
    // trigger as a delete, so it reopens a paid entry in an ACTIVE plan with a generation bump and
    // no reason (the same as UNLINK). There is no separate currency-changed or type-changed reason
    // code (see the test below).
    @Test
    fun R4_a_currency_or_type_change_reopens_a_paid_entry_in_an_active_plan_with_a_generation_bump() {
        val result = run(ctx(plan = PS.ACTIVE, status = ES.PAID), EntryTrigger.EXPENSE_REMOVED)

        assertThat(normalized(result)).isEqualTo(move(ES.SCHEDULED, delta = 1))
        // Exact equality, no normalization: the reopened entry carries no reason.
        assertThat(result).isEqualTo(move(ES.SCHEDULED, delta = 1))
    }

    @Test
    fun R4_there_are_no_separate_currency_changed_or_type_changed_reason_codes() {
        assertThat(Reason.entries.map { it.name }).containsNoneOf(
            "LINKED_EXPENSE_CURRENCY_CHANGED",
            "LINKED_EXPENSE_TYPE_CHANGED"
        )
    }

    @Test
    fun INV_12_a_reopened_entry_does_not_reset_the_auto_record_flag() {
        // The transition never reports setsAutoRecordConsumed on a reopen, and never a reset.
        val result = run(ctx(plan = PS.ACTIVE, status = ES.PAID, consumed = true), EntryTrigger.EXPENSE_REMOVED)

        assertThat((result as EntryTransitionResult.Move).setsAutoRecordConsumed).isFalse()
    }

    // Pin (not a numbered interpretation): spec 3.2 describes unlink for entries that came from a
    // linked expense; a PAYOFF entry returning to SCHEDULED is not described anywhere and would
    // leave a scheduled payoff row. The implementation refuses it.
    @Test
    fun pin_unlink_of_a_PAID_PAYOFF_entry_is_refused() {
        for (plan in listOf(PS.ACTIVE, PS.COMPLETED, PS.PAID_OFF)) {
            assertWithMessage("plan %s", plan)
                .that(run(ctx(plan = plan, status = ES.PAID, kind = InstallmentEntryKind.PAYOFF), EntryTrigger.UNLINK))
                .isEqualTo(illegalEntry)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Skip, unskip, adjust
    // ---------------------------------------------------------------------------------------

    @Test
    fun entry_table_SKIP_over_entry_status_in_an_ACTIVE_plan_PAID_to_SKIPPED_is_illegal() {
        assertEntryStatusColumn(
            EntryTrigger.SKIP,
            PS.ACTIVE,
            mapOf(
                ES.SCHEDULED to move(ES.SKIPPED),
                ES.SKIPPED to already(ES.SKIPPED)
            )
        )
    }

    @Test
    fun entry_table_SKIP_over_plan_status_for_a_scheduled_entry() {
        assertPlanStatusRow(EntryTrigger.SKIP, ES.SCHEDULED, mapOf(PS.ACTIVE to move(ES.SKIPPED)))
    }

    @Test
    fun entry_table_SKIP_of_a_linked_scheduled_entry_is_illegal_the_expense_must_be_deleted_first() {
        assertThat(run(ctx(status = ES.SCHEDULED, linked = true, skip = Reason.USER_SKIP_WAIVED), EntryTrigger.SKIP))
            .isEqualTo(illegalEntry)
    }

    @Test
    fun entry_table_UNSKIP_over_entry_status_in_an_ACTIVE_plan() {
        assertEntryStatusColumn(
            EntryTrigger.UNSKIP,
            PS.ACTIVE,
            mapOf(
                ES.SKIPPED to move(ES.SCHEDULED),
                ES.SCHEDULED to already(ES.SCHEDULED)
            )
        )
    }

    @Test
    fun entry_table_UNSKIP_over_plan_status_for_a_skipped_entry_a_COMPLETED_plan_reopens() {
        assertPlanStatusRow(
            EntryTrigger.UNSKIP,
            ES.SKIPPED,
            mapOf(
                PS.ACTIVE to move(ES.SCHEDULED),
                PS.COMPLETED to move(ES.SCHEDULED)
            )
        )
    }

    @Test
    fun entry_table_ADJUST_over_entry_status_in_an_ACTIVE_plan_only_a_scheduled_entry_may_change() {
        assertEntryStatusColumn(
            EntryTrigger.ADJUST,
            PS.ACTIVE,
            mapOf(ES.SCHEDULED to move(ES.SCHEDULED))
        )
    }

    @Test
    fun entry_table_ADJUST_over_plan_status_for_a_scheduled_entry() {
        assertPlanStatusRow(EntryTrigger.ADJUST, ES.SCHEDULED, mapOf(PS.ACTIVE to move(ES.SCHEDULED)))
    }

    @Test
    fun INV_19_any_change_of_a_PAID_entry_amount_is_illegal() {
        assertThat(run(ctx(status = ES.PAID), EntryTrigger.ADJUST)).isEqualTo(illegalEntry)
        assertThat(run(ctx(status = ES.REVERSED), EntryTrigger.ADJUST)).isEqualTo(illegalEntry)
    }

    @Test
    fun INV_32_changing_a_PAID_BEFORE_TRACKING_entry_amount_or_date_is_illegal() {
        assertThat(run(ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1), EntryTrigger.ADJUST))
            .isEqualTo(illegalEntry)
    }

    // Interpretation 4 (handoff S5): the spec says "Skip (bounded reason)" and "bounded adjustReason".
    // The owner-approved OQ-20 fixes the skip codes to exactly three; the adjust codes stay a
    // minimal set.
    @Test
    fun interpretation_4_skip_reason_codes_are_exactly_the_three_codes_of_OQ_20() {
        val skipCodes = Reason.entries.filter { it.name.startsWith("USER_SKIP") }

        assertThat(skipCodes).containsExactly(
            Reason.USER_SKIP_LENDER_HOLIDAY,
            Reason.USER_SKIP_WAIVED,
            Reason.USER_SKIP_OTHER
        )
        assertThat(Reason.entries.filter { it.isUserSkipReason }).containsExactlyElementsIn(skipCodes)
    }

    @Test
    fun interpretation_4_adjust_reason_set_is_the_five_minimal_codes() {
        assertThat(InstallmentAdjustReason.entries.map { it.name }).containsExactly(
            "USER_AMOUNT",
            "USER_DATE",
            "USER_AMOUNT_AND_DATE",
            "RESPREAD",
            "DUE_DAY_CHANGED"
        )
    }

    @Test
    fun interpretation_4_the_skip_transition_returns_the_supplied_user_skip_reason() {
        for (reason in listOf(Reason.USER_SKIP_LENDER_HOLIDAY, Reason.USER_SKIP_WAIVED, Reason.USER_SKIP_OTHER)) {
            val result = run(ctx(status = ES.SCHEDULED, skip = reason), EntryTrigger.SKIP)

            assertWithMessage("skip reason %s", reason)
                .that(result)
                .isEqualTo(EntryTransitionResult.Move(ES.SKIPPED, reason = reason))
        }
    }

    @Test
    fun interpretation_4_a_skip_without_a_reason_or_with_a_non_user_reason_is_refused() {
        assertThat(run(ctx(status = ES.SCHEDULED, skip = null), EntryTrigger.SKIP)).isEqualTo(illegalEntry)
        for (reason in Reason.entries.filter { !it.isUserSkipReason }) {
            assertWithMessage("skip reason %s", reason)
                .that(run(ctx(status = ES.SCHEDULED, skip = reason), EntryTrigger.SKIP))
                .isEqualTo(illegalEntry)
        }
    }

    @Test
    fun interpretation_4_a_retry_of_a_skip_on_an_already_skipped_entry_is_AlreadyInState_whatever_the_reason() {
        assertThat(run(ctx(status = ES.SKIPPED, skip = Reason.USER_SKIP_OTHER), EntryTrigger.SKIP))
            .isEqualTo(already(ES.SKIPPED))
        assertThat(run(ctx(status = ES.SKIPPED, skip = null), EntryTrigger.SKIP)).isEqualTo(already(ES.SKIPPED))
    }

    // ---------------------------------------------------------------------------------------
    // REVERSED to CANCELLED (reversed expense deleted)
    // ---------------------------------------------------------------------------------------

    @Test
    fun entry_table_REVERSED_EXPENSE_REMOVED_over_entry_status_in_a_REFUNDED_plan() {
        assertEntryStatusColumn(
            EntryTrigger.REVERSED_EXPENSE_REMOVED,
            PS.REFUNDED,
            mapOf(
                ES.REVERSED to move(ES.CANCELLED),
                ES.CANCELLED to already(ES.CANCELLED)
            )
        )
    }

    @Test
    fun entry_table_REVERSED_EXPENSE_REMOVED_over_plan_status_for_a_reversed_entry_only_REFUNDED_plans_hold_them() {
        assertPlanStatusRow(
            EntryTrigger.REVERSED_EXPENSE_REMOVED,
            ES.REVERSED,
            mapOf(PS.REFUNDED to move(ES.CANCELLED))
        )
    }

    @Test
    fun R2_a_deleted_reversed_expense_cancels_the_entry_with_the_bounded_reason_EXPENSE_REMOVED() {
        val result = run(ctx(plan = PS.REFUNDED, status = ES.REVERSED), EntryTrigger.REVERSED_EXPENSE_REMOVED)

        assertThat(result).isEqualTo(
            EntryTransitionResult.Move(ES.CANCELLED, recordGenerationDelta = 0, reason = Reason.EXPENSE_REMOVED)
        )
    }

    // ---------------------------------------------------------------------------------------
    // INV-32 / OQ-18: PAID_BEFORE_TRACKING mark and revert
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_32_mark_over_entry_status_in_an_ACTIVE_plan_only_an_eligible_scheduled_entry_moves() {
        assertEntryStatusColumn(
            EntryTrigger.MARK_PAID_BEFORE_TRACKING,
            PS.ACTIVE,
            mapOf(
                ES.SCHEDULED to move(ES.PAID_BEFORE_TRACKING),
                ES.PAID_BEFORE_TRACKING to already(ES.PAID_BEFORE_TRACKING)
            ),
            refusal = invalidPbt
        )
    }

    @Test
    fun INV_32_mark_over_plan_status_for_an_eligible_scheduled_entry_only_ACTIVE_may_mark() {
        assertPlanStatusRow(
            EntryTrigger.MARK_PAID_BEFORE_TRACKING,
            ES.SCHEDULED,
            mapOf(PS.ACTIVE to move(ES.PAID_BEFORE_TRACKING)),
            // A COMPLETED plan has no SCHEDULED entry; an actual move still requires ACTIVE, so a
            // SCHEDULED entry in a COMPLETED plan is refused (checked separately below).
            skipPlans = setOf(PS.COMPLETED)
        )
        assertThat(run(ctxFor(EntryTrigger.MARK_PAID_BEFORE_TRACKING, PS.COMPLETED, ES.SCHEDULED), EntryTrigger.MARK_PAID_BEFORE_TRACKING))
            .isEqualTo(illegalEntry)
    }

    @Test
    fun INV_32_mark_retry_over_plan_status_for_an_already_marked_entry_is_idempotent_in_ACTIVE_and_COMPLETED_and_illegal_in_terminal_plans() {
        assertPlanStatusRow(
            EntryTrigger.MARK_PAID_BEFORE_TRACKING,
            ES.PAID_BEFORE_TRACKING,
            mapOf(
                PS.ACTIVE to already(ES.PAID_BEFORE_TRACKING),
                PS.COMPLETED to already(ES.PAID_BEFORE_TRACKING)
            )
        )
    }

    @Test
    fun INV_32_AE27_a_mark_retry_after_the_mark_completed_the_plan_reports_AlreadyInState() {
        // The first mark moved the last SCHEDULED entry, so the plan completed in that transaction.
        val retry = ctx(plan = PS.COMPLETED, status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1)

        assertThat(run(retry, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(already(ES.PAID_BEFORE_TRACKING))
    }

    @Test
    fun INV_32_a_revert_retry_has_the_same_idempotency_an_unmarked_entry_reports_AlreadyInState_in_ACTIVE_and_COMPLETED() {
        for (plan in listOf(PS.ACTIVE, PS.COMPLETED)) {
            assertWithMessage("revert retry in %s", plan)
                .that(run(ctx(plan = plan, status = ES.SCHEDULED, due = declaredOn - 1), EntryTrigger.REVERT_PAID_BEFORE_TRACKING))
                .isEqualTo(already(ES.SCHEDULED))
        }
    }

    @Test
    fun INV_32_AE27_mark_and_revert_on_a_stopped_refunded_or_paid_off_plan_are_IllegalTransition_even_for_a_marked_entry() {
        for (plan in listOf(PS.CANCELLED, PS.REFUNDED, PS.PAID_OFF)) {
            val marked = ctx(plan = plan, status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1)
            val scheduled = ctx(plan = plan, status = ES.SCHEDULED, due = declaredOn - 1)

            assertWithMessage("revert in %s", plan)
                .that(run(marked, EntryTrigger.REVERT_PAID_BEFORE_TRACKING)).isEqualTo(illegalEntry)
            assertWithMessage("repeat mark of a marked entry in %s", plan)
                .that(run(marked, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(illegalEntry)
            assertWithMessage("mark of a scheduled entry in %s", plan)
                .that(run(scheduled, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(illegalEntry)
        }
    }

    @Test
    fun INV_32_mark_boundary_an_entry_due_the_day_before_declaredOn_is_eligible_and_due_on_declaredOn_is_not() {
        val dayBefore = ctx(due = declaredOn - 1)
        val onDeclaredOn = ctx(due = declaredOn)
        val afterDeclaredOn = ctx(due = declaredOn + 1)

        assertThat(normalized(run(dayBefore, EntryTrigger.MARK_PAID_BEFORE_TRACKING))).isEqualTo(move(ES.PAID_BEFORE_TRACKING))
        assertThat(run(onDeclaredOn, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(invalidPbt)
        assertThat(run(afterDeclaredOn, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(invalidPbt)
    }

    @Test
    fun INV_32_mark_is_refused_for_a_PAYOFF_entry_and_for_a_linked_entry() {
        val payoff = ctx(kind = InstallmentEntryKind.PAYOFF, due = declaredOn - 1)
        val linked = ctx(linked = true, due = declaredOn - 1)

        assertThat(run(payoff, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(invalidPbt)
        assertThat(run(linked, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(invalidPbt)
    }

    @Test
    fun INV_32_AE27_repeating_the_same_mark_is_AlreadyInState_and_changes_nothing() {
        val marked = ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1)

        assertThat(run(marked, EntryTrigger.MARK_PAID_BEFORE_TRACKING)).isEqualTo(already(ES.PAID_BEFORE_TRACKING))
    }

    @Test
    fun INV_32_revert_over_entry_status_in_an_ACTIVE_plan() {
        assertEntryStatusColumn(
            EntryTrigger.REVERT_PAID_BEFORE_TRACKING,
            PS.ACTIVE,
            mapOf(
                ES.PAID_BEFORE_TRACKING to move(ES.SCHEDULED),
                ES.SCHEDULED to already(ES.SCHEDULED)
            )
        )
    }

    @Test
    fun INV_32_revert_over_plan_status_for_a_marked_entry_ACTIVE_and_COMPLETED_may_revert() {
        assertPlanStatusRow(
            EntryTrigger.REVERT_PAID_BEFORE_TRACKING,
            ES.PAID_BEFORE_TRACKING,
            mapOf(
                PS.ACTIVE to move(ES.SCHEDULED),
                PS.COMPLETED to move(ES.SCHEDULED)
            )
        )
    }

    @Test
    fun INV_32_AE27_revert_keeps_recordGeneration_and_autoRecordConsumed_unchanged() {
        val result = run(
            ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1, consumed = false),
            EntryTrigger.REVERT_PAID_BEFORE_TRACKING
        ) as EntryTransitionResult.Move

        assertThat(result.to).isEqualTo(ES.SCHEDULED)
        assertThat(result.recordGenerationDelta).isEqualTo(0)
        assertThat(result.setsAutoRecordConsumed).isFalse()
    }

    @Test
    fun INV_32_AE27_reverting_an_entry_of_a_COMPLETED_plan_reopens_the_plan_in_the_same_transaction() {
        val result = run(ctx(plan = PS.COMPLETED, status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1), EntryTrigger.REVERT_PAID_BEFORE_TRACKING)

        assertThat(normalized(result)).isEqualTo(move(ES.SCHEDULED))
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.COMPLETED, 1)).isEqualTo(PS.ACTIVE)
    }

    @Test
    fun INV_32_the_unlink_path_bumps_recordGeneration_but_the_revert_path_does_not() {
        val unlink = run(ctx(status = ES.PAID), EntryTrigger.UNLINK) as EntryTransitionResult.Move
        val revertContext = ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1)
        val revert = run(revertContext, EntryTrigger.REVERT_PAID_BEFORE_TRACKING) as EntryTransitionResult.Move

        assertThat(unlink.recordGenerationDelta).isEqualTo(1)
        assertThat(revert.recordGenerationDelta).isEqualTo(0)
    }

    @Test
    fun INV_32_a_PAID_BEFORE_TRACKING_entry_has_no_direct_move_to_PAID_SKIPPED_or_CANCELLED() {
        val marked = ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1)
        val markedAuto = marked.copy(todayEpochDay = dueToday, fulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD)
        val triggers = EntryTrigger.entries.filter {
            it != EntryTrigger.MARK_PAID_BEFORE_TRACKING && it != EntryTrigger.REVERT_PAID_BEFORE_TRACKING
        }

        for (trigger in triggers) {
            assertWithMessage("%s on a PAID_BEFORE_TRACKING entry", trigger)
                .that(run(markedAuto, trigger))
                .isEqualTo(illegalEntry)
        }
        // Plan operations leave it untouched rather than cancelling it.
        for (operation in PlanEntryOperation.entries) {
            assertThat(InstallmentPlanTransitions.entryEffect(operation, ES.PAID_BEFORE_TRACKING)).isNull()
        }
    }

    @Test
    fun INV_32_the_only_exit_of_a_PAID_BEFORE_TRACKING_entry_is_the_revert_to_SCHEDULED() {
        val marked = ctx(status = ES.PAID_BEFORE_TRACKING, due = declaredOn - 1)
        val markedAuto = marked.copy(todayEpochDay = dueToday, fulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD)

        val moving = EntryTrigger.entries.filter { run(markedAuto, it) is EntryTransitionResult.Move }

        assertThat(moving).containsExactly(EntryTrigger.REVERT_PAID_BEFORE_TRACKING)
    }

    // ---------------------------------------------------------------------------------------
    // Idempotency of retries (3.1 retry paragraph, 3.2)
    // ---------------------------------------------------------------------------------------

    @Test
    fun a_retry_on_an_entry_already_in_the_target_state_reports_AlreadyInState_even_after_the_plan_completed() {
        // CONFIRM retried after the confirmed entry completed the plan.
        assertThat(run(ctx(plan = PS.COMPLETED, status = ES.PAID), EntryTrigger.CONFIRM))
            .isEqualTo(already(ES.PAID))
        // UNSKIP retried after the unskipped entry made the plan ACTIVE again is the same shape.
        assertThat(run(ctx(plan = PS.ACTIVE, status = ES.SCHEDULED), EntryTrigger.UNSKIP))
            .isEqualTo(already(ES.SCHEDULED))
    }

    // ---------------------------------------------------------------------------------------
    // Bulk mark paid before tracking (3.2, INV-32, AE-27)
    // ---------------------------------------------------------------------------------------

    private fun candidate(
        id: Long,
        due: Long,
        status: ES = ES.SCHEDULED,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR,
        linked: Boolean = false
    ) = PaidBeforeTrackingCandidate(
        entryId = id,
        entryKind = kind,
        entryStatus = status,
        hasLinkedExpense = linked,
        dueEpochDay = due
    )

    private fun bulk(planStatus: PS, vararg candidates: PaidBeforeTrackingCandidate): BulkMarkResult =
        InstallmentPlanTransitions.bulkMarkPaidBeforeTracking(planStatus, declaredOn, candidates.toList())

    // AE-27: entry 5 is due 2027-06-05, entry 6 is due 2027-07-05, declaredOnDate is 2027-06-20.
    private val entry5Due = day(2027, 6, 5)
    private val entry6Due = day(2027, 7, 5)

    @Test
    fun AE27_bulk_marking_entry_5_is_allowed() {
        assertThat(bulk(PS.ACTIVE, candidate(5, entry5Due)))
            .isEqualTo(BulkMarkResult.Allowed(toMark = listOf(5L), alreadyMarked = emptyList()))
    }

    @Test
    fun AE27_repeating_the_same_bulk_request_returns_AlreadyInState_for_the_entry_and_writes_nothing() {
        assertThat(bulk(PS.ACTIVE, candidate(5, entry5Due, status = ES.PAID_BEFORE_TRACKING)))
            .isEqualTo(BulkMarkResult.Allowed(toMark = emptyList(), alreadyMarked = listOf(5L)))
    }

    @Test
    fun AE27_bulk_marking_entries_5_and_6_is_refused_as_a_whole_because_entry_6_is_due_after_declaredOn() {
        assertThat(bulk(PS.ACTIVE, candidate(5, entry5Due), candidate(6, entry6Due)))
            .isEqualTo(BulkMarkResult.Refused(Code.INVALID_PAID_BEFORE_TRACKING))
    }

    @Test
    fun INV_32_bulk_mark_is_all_or_nothing_when_the_last_selected_entry_is_the_ineligible_one() {
        val result = bulk(
            PS.ACTIVE,
            candidate(3, day(2027, 4, 5)),
            candidate(4, day(2027, 5, 5)),
            candidate(5, entry5Due),
            candidate(6, entry6Due)
        )

        assertThat(result).isEqualTo(BulkMarkResult.Refused(Code.INVALID_PAID_BEFORE_TRACKING))
    }

    @Test
    fun INV_32_bulk_mark_mixes_newly_marked_and_already_marked_entries() {
        val result = bulk(
            PS.ACTIVE,
            candidate(3, day(2027, 4, 5), status = ES.PAID_BEFORE_TRACKING),
            candidate(4, day(2027, 5, 5))
        )

        assertThat(result).isEqualTo(BulkMarkResult.Allowed(toMark = listOf(4L), alreadyMarked = listOf(3L)))
    }

    @Test
    fun INV_32_bulk_mark_boundary_due_the_day_before_declaredOn_is_allowed_and_due_on_declaredOn_is_refused() {
        assertThat(bulk(PS.ACTIVE, candidate(1, declaredOn - 1)))
            .isEqualTo(BulkMarkResult.Allowed(toMark = listOf(1L), alreadyMarked = emptyList()))
        assertThat(bulk(PS.ACTIVE, candidate(1, declaredOn)))
            .isEqualTo(BulkMarkResult.Refused(Code.INVALID_PAID_BEFORE_TRACKING))
    }

    @Test
    fun INV_32_bulk_mark_refuses_linked_PAYOFF_and_non_scheduled_entries() {
        val refusedCandidates = mapOf(
            "linked" to candidate(1, entry5Due, linked = true),
            "PAYOFF" to candidate(1, entry5Due, kind = InstallmentEntryKind.PAYOFF),
            "PAID" to candidate(1, entry5Due, status = ES.PAID, linked = true),
            "SKIPPED" to candidate(1, entry5Due, status = ES.SKIPPED),
            "CANCELLED" to candidate(1, entry5Due, status = ES.CANCELLED),
            "REVERSED" to candidate(1, entry5Due, status = ES.REVERSED, linked = true)
        )

        for ((label, bad) in refusedCandidates) {
            assertWithMessage("bulk with a %s entry", label)
                .that(bulk(PS.ACTIVE, bad))
                .isEqualTo(BulkMarkResult.Refused(Code.INVALID_PAID_BEFORE_TRACKING))
        }
    }

    @Test
    fun AE27_bulk_mark_on_a_stopped_refunded_or_paid_off_plan_is_IllegalTransition() {
        for (plan in listOf(PS.CANCELLED, PS.REFUNDED, PS.PAID_OFF)) {
            assertWithMessage("bulk in %s", plan)
                .that(bulk(plan, candidate(1, entry5Due)))
                .isEqualTo(BulkMarkResult.Refused(Code.ILLEGAL_TRANSITION))
        }
    }

    @Test
    fun INV_32_bulk_mark_on_a_COMPLETED_plan_is_refused_when_an_entry_needs_a_move() {
        // A COMPLETED plan has no SCHEDULED entry, and an actual move still requires ACTIVE.
        assertThat(bulk(PS.COMPLETED, candidate(1, entry5Due)))
            .isEqualTo(BulkMarkResult.Refused(Code.ILLEGAL_TRANSITION))
        assertThat(
            bulk(PS.COMPLETED, candidate(1, entry5Due, status = ES.PAID_BEFORE_TRACKING), candidate(2, entry5Due))
        ).isEqualTo(BulkMarkResult.Refused(Code.ILLEGAL_TRANSITION))
    }

    @Test
    fun INV_32_AE27_bulk_mark_retry_on_a_COMPLETED_plan_with_every_entry_already_marked_is_allowed_with_nothing_to_write() {
        // The first bulk request marked the last SCHEDULED entries, so the plan completed.
        assertThat(
            bulk(
                PS.COMPLETED,
                candidate(4, day(2027, 5, 5), status = ES.PAID_BEFORE_TRACKING),
                candidate(5, entry5Due, status = ES.PAID_BEFORE_TRACKING)
            )
        ).isEqualTo(BulkMarkResult.Allowed(toMark = emptyList(), alreadyMarked = listOf(4L, 5L)))
    }

    @Test
    fun INV_32_bulk_mark_on_an_ACTIVE_plan_is_the_only_plan_status_that_can_allow_it() {
        val allowing = PS.entries.filter { bulk(it, candidate(1, entry5Due)) is BulkMarkResult.Allowed }

        assertThat(allowing).containsExactly(PS.ACTIVE)
    }

    // Pin (not a numbered interpretation): the spec describes "selected entries" without defining
    // an empty selection or a repeated id. The implementation refuses both as invalid requests.
    @Test
    fun pin_bulk_mark_with_an_empty_selection_or_a_repeated_entry_id_is_refused_as_invalid() {
        assertThat(bulk(PS.ACTIVE))
            .isEqualTo(BulkMarkResult.Refused(Code.INVALID_PAID_BEFORE_TRACKING))
        assertThat(bulk(PS.ACTIVE, candidate(5, entry5Due), candidate(5, entry5Due)))
            .isEqualTo(BulkMarkResult.Refused(Code.INVALID_PAID_BEFORE_TRACKING))
    }

    @Test
    fun INV_21_AE27_marking_the_last_scheduled_entry_leaves_zero_scheduled_so_the_plan_completes() {
        // One SCHEDULED entry, due before declaredOn: the mark moves it, then the plan has 0 SCHEDULED.
        val result = run(ctxFor(EntryTrigger.MARK_PAID_BEFORE_TRACKING, PS.ACTIVE, ES.SCHEDULED), EntryTrigger.MARK_PAID_BEFORE_TRACKING)

        assertThat(normalized(result)).isEqualTo(move(ES.PAID_BEFORE_TRACKING))
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.ACTIVE, 0)).isEqualTo(PS.COMPLETED)
    }

    @Test
    fun INV_21_the_last_scheduled_entry_resolving_by_confirm_or_skip_completes_the_plan() {
        assertThat(normalized(run(ctx(status = ES.SCHEDULED, today = dueToday), EntryTrigger.CONFIRM)))
            .isEqualTo(move(ES.PAID))
        assertThat(
            normalized(run(ctx(status = ES.SCHEDULED, skip = Reason.USER_SKIP_LENDER_HOLIDAY), EntryTrigger.SKIP))
        ).isEqualTo(move(ES.SKIPPED))
        assertThat(InstallmentPlanTransitions.planStatusAfterEntryChange(PS.ACTIVE, 0)).isEqualTo(PS.COMPLETED)
    }
}
