package com.yourname.expensetracker.domain.dto

/**
 * Lightweight domain-level category reference for AI categorization inputs.
 *
 * Carries only the fields that domain / AI model consumers actually need,
 * without dragging the full Room [com.yourname.expensetracker.data.database.entity.Category]
 * entity (which includes icon, color, isDefault, and Room annotations).
 *
 * Mappers in the data / adapter layer should convert [Category] entities to [CategoryRef]
 * before crossing into domain code.
 */
data class CategoryRef(
    val id: Long,
    val name: String
)
