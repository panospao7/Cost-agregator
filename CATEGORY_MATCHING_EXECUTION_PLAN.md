# Detailed Execution Plan: Smart Category Matching Enhancement

## Overview
This document outlines the step-by-step execution plan for implementing the 5-layer hybrid semantic matching system. It specifically addresses and incorporates critical fixes for Greeklish normalization (diphthongs, accents), regex word boundaries, fuzzy matching (Levenshtein distance), Greek business entity prefixes/suffixes, and contextual inference improvements (feminine surnames, accurate grocery price brackets).

---

## Phase 1: Database & Architecture Foundation (Week 1)
**Goal:** Optimize database lookups to prevent runtime normalization of the entire merchant dictionary.

**Tasks:**
1. **Database Migration:** 
   - Add a `normalized_canonical_name` column to the `MerchantCategory` (or similar) database table.
2. **Backfill Existing Data:**
   - Create a migration script or one-off worker to populate this new column for all existing merchants.
   - The value should be lowercase, stripped of accents, and converted to basic Latin characters (e.g., "Σκλαβενίτης" -> "sklavenitis").
3. **DAO Updates:**
   - Update `MerchantCategoryDao` to index the new column and allow querying by `normalized_canonical_name`.
   - Ensure any new manual category entries (user corrections) automatically generate and save their canonical name.

---

## Phase 2: Advanced Greeklish Normalization Engine (Week 2)
**Goal:** Accurately handle Greek characters, accents, and complex diphthongs to create a uniform, robust Latin representation.

**Tasks:**
1. **Accent Stripping:**
   - Implement native string normalization (e.g., `java.text.Normalizer.normalize(text, Normalizer.Form.NFD)`) to safely remove all diacritics (`ά, έ, ή, ί, ό, ύ, ώ, ϊ, ϋ`) before any character mapping.
2. **Diphthong & Multi-Char Preprocessing:**
   - Create rules to convert multi-character vowels and consonants **before** falling back to character-by-character mapping:
     - `ου` -> `ou` (not `oy`)
     - `ευ` -> `ev` or `ef` (handle appropriately)
     - `αυ` -> `av` or `af`
     - `μπ` -> `b` (or `mp` as a known variation)
     - `γκ` -> `g`
     - `ντ` -> `d` or `nt`
3. **Character Mapping:**
   - Implement the updated `GREEK_TO_LATIN` mapping for single characters.
4. **Testing:**
   - Add unit tests verifying "Σκλαβενίτης" -> "sklavenitis" (no accents), "Σούπερ Μάρκετ" -> "souper market", etc.

---

## Phase 3: Robust Merchant Canonicalization Engine (Week 3)
**Goal:** Remove noise (locations, prefixes, suffixes) to extract the core merchant entity name.

**Tasks:**
1. **Business Entity Lexicon:**
   - Update `TYPE_SUFFIXES` to include Greek corporate structures: `epe`, `o.e.`, `e.e.`, `ike`, `monoprosopi`, `sa`, `ae`, `ltd`, `inc`.
2. **Prefix Stripping:**
   - Implement logic to strip prefixes, as Greek business types often precede the name (e.g., "ΙΚΕ ΓΕΩΡΓΙΑΔΗΣ" -> "ΓΕΩΡΓΙΑΔΗΣ", "ΑΦΟΙ ΠΑΠΑΔΟΠΟΥΛΟΙ" -> "ΠΑΠΑΔΟΠΟΥΛΟΙ", "ΥΙΟΙ...").
3. **Suffix Stripping:**
   - Strip generic location names (e.g., "athens", "lagka") and generic business descriptions (e.g., "stores", "market").
4. **Canonicalizer Implementation:**
   - Ensure the canonicalizer runs the string through the Normalization Engine (Phase 2) first, followed by Prefix/Suffix stripping.

---

## Phase 4: Exact & Fuzzy Matching Layer (Week 4)
**Goal:** Implement Layer 1 (Exact) and Layer 2 (Canonical + Fuzzy) of the architecture.

**Tasks:**
1. **Layer 1 (Exact Match):**
   - Query exact unmodified merchant name against DB.
2. **Layer 2 (Canonical Match):**
   - Query canonicalized merchant name against the new `normalized_canonical_name` DB column.
3. **Layer 2 (Levenshtein Fuzzy Match):**
   - If DB lookup fails, run a Levenshtein distance check against predefined canonical merchants.
   - You can fetch a limited set of merchants (e.g., grouped by first letter) and check distance.
   - Use a tight threshold (e.g., distance $\le$ 2 for words $>5$ characters) to catch typos like "Sklavvenitis".
   - *Dependency:* Ensure `StringDistanceUtils.kt` is fully utilized here.

---

## Phase 5: Semantic Keyword Matcher Layer (Week 5)
**Goal:** Implement Layer 3 to catch unknown merchants using safe, boundary-aware semantic keyword triggers.

**Tasks:**
1. **Keyword Dictionaries:**
   - Establish weighted dictionaries for major categories (Food, Groceries, Transport, etc.).
2. **Regex Word Boundary Implementation:**
   - **Crucial:** Change the matching logic from `.contains()` to regex word boundaries to prevent collision.
   - E.g., Use `\b(pizza|coffee|cafe)\b` to match "Coffee Island" but ignore "Coffeehouse" (if 'coffeehouse' needs its own weight, list it explicitly).
3. **Multi-Word Regex Patterns:**
   - Implement broader fallback patterns (e.g., `^pizza\s+.+$`).
4. **Score Calculation:**
   - Ensure highest matching semantic keyword assigns the category with a capped confidence (e.g., 0.60 - 0.90) depending on the keyword's exclusivity.

---

## Phase 6: Contextual Inference Engine (Week 6)
**Goal:** Implement Layer 4 heuristics using time, amount, and broad surname detection.

**Tasks:**
1. **Enhanced Surname Detection:**
   - Broaden the heuristic to include feminine Greek surname endings: `-a`, `-i`, `-ou`.
   - Update list to handle variations: `-is, -as, -os, -ou, -akis, -idis, -opoulos, -a, -i`.
2. **Accurate Amount Brackets:**
   - Redefine context logic for Groceries: Allocate a strong weight for the `€20.0 - €150.0` bracket for `Groceries`, alongside `Shopping`.
   - Combine with day/time: A €60 purchase on a Saturday morning has a high likelihood of being `Groceries` over generic `Shopping`.
3. **Contextual Score Aggregation:**
   - Aggregate time, amount, and day context. If the combined score exceeds a threshold (e.g., 0.50) and a surname is detected, flag it for "Review" with the suggested category.

---

## Phase 7: UI Feedback & System Integration (Week 7-8)
**Goal:** Connect all layers in `CategorizationEngine.kt` and build the user review loop.

**Tasks:**
1. **Pipeline Execution:**
   - Wire `CategorizationEngine` to waterfall through Layers 1 to 5.
   - Fast-fail early layers to save computation (e.g., if Exact Match works, exit immediately).
2. **Confidence Thresholds:**
   - `MatchType.EXACT` / `CANONICAL` -> Auto-categorize (Confidence > 90%).
   - `MatchType.KEYWORD` / `FUZZY` / `CONTEXT` -> Auto-categorize if Confidence > 75%, otherwise assign `Uncategorized` and queue for user review.
3. **"Suggest for Review" UI:**
   - Expose the system's `confidence` and `explanation` ("inferred from context") in the UI to allow 1-tap categorization confirmations by the user.
4. **Continuous Learning Loop:**
   - When a user confirms a suggestion, save that exact merchant string -> category to the database. Next time, it hits Layer 1 (Exact Match).

---

## Technical Debt / Risks Checklist
- [ ] Ensure Regex compilation happens once (e.g., in `init` block or static constants) and not dynamically during `categorize()` to save memory and CPU.
- [ ] Keep DB footprint light. The fuzzy matching should ideally limit DB records fetched (e.g., fetch merchants matching the first 2 letters, then run Levenshtein) instead of running algorithm against 1,000+ entries.
- [ ] Create integration tests that pass an array of tricky strings through the `CategorizationEngine` pipeline (e.g., `listOf("ΙΚΕ ΣΚΛΑΒΕΝΙΤΗΣ", "sklavvenitis", "G. PAPADOPOULOU")`) to verify output paths.
