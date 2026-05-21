package com.yourname.expensetracker.domain.sideeffect

data class PostCommitActionBatch(
    val correlationId: String,
    val actions: List<PostCommitAction>
) {
    companion object {
        fun empty(correlationId: String): PostCommitActionBatch =
            PostCommitActionBatch(correlationId, emptyList())
    }

    operator fun plus(other: PostCommitActionBatch): PostCommitActionBatch =
        copy(actions = actions + other.actions)

    fun normalized(): PostCommitActionBatch {
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<PostCommitAction>()
        for (action in actions) {
            if (seen.add(action.idempotencyKey)) {
                deduped.add(action)
            }
        }
        return copy(actions = deduped)
    }
}
