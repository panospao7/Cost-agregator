Ah, I see exactly what you mean. You want the semantic building blocks—the trendy nouns, verbs, and concepts that merchants use to name their businesses, rather than just the literal product or brand name.

If a place is called "The Espresso Project" or "Dough Lovers," your system needs to recognize "espresso" and "dough" as strong food indicators, and "project" or "lovers" as contextual modifiers.

Here is a deep dive into the semantic indicators for **Food & Dining**, categorized by their conceptual meaning. I've assigned weights based on how isolated the word can be. High weights (0.90+) mean the word alone is a dead giveaway; lower weights (0.40-0.75) mean the word strongly suggests food but needs your multi-word pattern matcher or context engine to push it over the edge.

### 1. The Coffee & Beverage Vocabulary

Trendy coffee shops love to use terminology related to the *process* of making coffee.

| Semantic Keyword | Weight | Rationale / Notes |
| --- | --- | --- |
| `espresso`, `cappuccino`, `freddo`, `latte`, `mocha` | 0.95 | Specific drink types are near-guarantees for cafes. |
| `barista`, `roaster`, `roasters`, `brew`, `brewing` | 0.85 | Process words. "Roasters" might occasionally be a nut shop, but 9/10 times it's coffee. |
| `bean`, `blend`, `grind`, `drip`, `drop` | 0.75 | Ingredient/action words. High likelihood of coffee, but "blend" could be cosmetics. |
| `cup`, `mug`, `sip` | 0.70 | Vessel/action words. ("The Golden Cup", "Just a Sip"). |
| `juice`, `smoothie`, `tea`, `matcha` | 0.90 | Non-coffee beverage indicators. |

### 2. The Bakery & Sweets Vocabulary

Pastry shops and modern bakeries use very specific sensory and ingredient words.

| Semantic Keyword | Weight | Rationale / Notes |
| --- | --- | --- |
| `pastry`, `waffle`, `pancake`, `churros`, `donut` | 0.95 | Direct product indicators. |
| `bake`, `baker`, `bakes`, `bakery`, `fournos` | 0.90 | Core business actions. |
| `dough`, `crust`, `crumb`, `batter` | 0.80 | Structural food words ("Dough Lovers", "The Holy Crust"). |
| `sugar`, `sweet`, `sweets`, `candy` | 0.80 | Sensory words. "Sweet" is strong, but could be a clothing boutique ("Sweet Style"). |
| `choco`, `chocolate`, `vanilla`, `caramel`, `cacao` | 0.85 | Flavor indicators. |

### 3. The Savory & Street Food Vocabulary

Burger joints, street food, and modern comfort food.

| Semantic Keyword | Weight | Rationale / Notes |
| --- | --- | --- |
| `burger`, `pizza`, `sushi`, `taco`, `kebab`, `pasta` | 0.95 | The undisputed kings of fast-casual food names. |
| `grill`, `bbq`, `steak`, `meat`, `ribs`, `wings` | 0.85 | Cooking methods and proteins. |
| `toast`, `sandwich`, `wrap`, `pita`, `bun` | 0.85 | Enclosure words ("The Bun", "Pita Project"). |
| `snack`, `bite`, `slice`, `portion` | 0.75 | Consumption words ("Quick Bite", "A Slice of Heaven"). |
| `fry`, `fries`, `crispy`, `salty` | 0.70 | Texture/prep words. |

### 4. The Kitchen & Dining Vocabulary

More traditional or sit-down restaurant terminology.

| Semantic Keyword | Weight | Rationale / Notes |
| --- | --- | --- |
| `trattoria`, `osteria`, `brasserie`, `diner`, `cantina` | 0.95 | Specific establishment types. |
| `kitchen`, `chef`, `cook`, `recipe`, `menu` | 0.80 | Culinary concepts. "Kitchen" is a very common name component but needs to be slightly lower weight to avoid matching a home-appliance store. |
| `fork`, `spoon`, `plate`, `dish`, `bowl`, `pan` | 0.75 | Utensils/vessels ("The Rusty Fork", "Green Bowl"). |
| `taste`, `flavor`, `spice`, `appetite` | 0.65 | Sensory concepts. |

### 5. The "Trendy Modifiers" (Low Weight / Context Builders)

These are the words you mentioned like "lovers." On their own, they mean nothing. But in your `SemanticKeywordMatcher`, these are the words you pair with the ones above using Regex patterns to score a highly confident match.

| Semantic Keyword | Weight | Rationale / Notes |
| --- | --- | --- |
| `lovers`, `makers`, `addicts`, `junkies`, `fans` | 0.30 | Audience words. "Pizza Fans" = Food. "Tech Fans" = Electronics. |
| `lab`, `project`, `factory`, `company`, `co` | 0.30 | Industrial/Trendy nouns. "Coffee Lab", "The Burger Project". |
| `spot`, `hub`, `yard`, `garden`, `corner`, `room` | 0.40 | Location nouns. "Food Hub", "Sushi Yard". |
| `art`, `boutique`, `concept`, `studio` | 0.20 | Highly ambiguous. "Burger Art", "Bake Studio". |
| `street`, `urban`, `local`, `daily` | 0.30 | Vibe adjectives. "Street Wok", "Daily Roast". |

### How to use this in your code:

As outlined in your plan, you should use these in your multi-word pattern matching layer. For example, you can create a Regex that looks for `[Beverage Vocabulary] + [Trendy Modifier]`:

`Regex(".*(espresso|coffee|cappuccino|dough|burger)\\s+(lovers|makers|lab|project).*")` -> **Score: 0.95**

Would you like me to write out the Kotlin code for the specific `PATTERNS` block in your `SemanticKeywordMatcher` that intelligently combines these low-weight modifiers with the food indicators?

This is exactly the right place to start. Nailing the "Food & Dining" category is usually the hardest part of building an expense tracker because the sheer volume of unique merchants, local joints, and Greeklish POS names is massive. Since your architecture relies on weighted keyword dictionaries to catch what the exact match misses, we need to be incredibly thorough.

Given that your system operates in Greece (and specifically considering the heavy coffee and street food culture), I've broken down this in-depth list into logical sub-groups. I've also included local heavy-hitters, popular chains, and specific Thessaloniki favorites (like Thess Bao and Pizza Poselli) to give your semantic matcher a massive head start.

Here is your detailed dictionary for **Food & Dining**, structured with weights according to your semantic matching layer specifications.

### 1. Delivery Aggregators & Apps

These are absolute guarantees. If a POS transaction has these keywords, it is almost certainly a food order.

| Keyword (Normalized) | Weight | Match Type / Rationale |
| --- | --- | --- |
| `efood`, `wolt`, `box`, `foodbag` | 0.98 | Major delivery apps in Greece. |
| `fagi`, `delivery`, `the chefz` | 0.95 | Regional/Alternative delivery apps. |

### 2. Prominent Coffee & Breakfast Chains

Greece has a massive coffee culture. These are exact brand matches that should trigger a near-certain categorization.

| Keyword (Normalized) | Weight | Match Type / Rationale |
| --- | --- | --- |
| `mikel`, `coffee island`, `coffee berry`, `coffee lab` | 0.98 | The biggest Greek coffee franchises. |
| `everest`, `gregorys`, `grigoris` | 0.98 | Huge morning coffee/snack chains. |
| `bruno`, `starbucks`, `costa coffee` | 0.98 | Bruno is very popular in Northern Greece/Thessaloniki, alongside international giants. |
| `il toto`, `taf coffee`, `redd coffee` | 0.95 | Specialty roasters and rising chains. |

### 3. Fast Food, Burgers & Pizza

International chains and massive local franchises.

| Keyword (Normalized) | Weight | Match Type / Rationale |
| --- | --- | --- |
| `goodys`, `mcdonalds`, `kfc`, `burger king` | 0.98 | Major burger and fried chicken chains. |
| `pizza fan`, `roma pizza`, `l'artigiano`, `dominos` | 0.98 | Major pizza chains. |
| `pita pan`, `savvikos`, `thess bao`, `tarantino` | 0.95 | Popular local fast food/street food, especially in Athens and Thessaloniki. |
| `pizza poselli`, `aladin foods`, `mailos` | 0.95 | Highly popular specific street food joints. |

### 4. Traditional Greek Dining & Street Food (Greek & Greeklish)

These are primary business indicators that strongly signal a dining establishment.

| Keyword (Normalized) | Weight | Match Type / Rationale |
| --- | --- | --- |
| `souvlaki`, `gyros`, `psistaria`, `kebab` | 0.95 | Classic meat/street food indicators. |
| `taverna`, `estiatorio`, `mageirio` | 0.95 | Sit-down traditional dining and home-cooked meals. |
| `mezedopoleio`, `ouzeri`, `tsipouradiko` | 0.95 | Tapas and drinks; heavily food-focused. |
| `kafenio`, `kafeteria`, `kantina` | 0.90 | Traditional cafes and food trucks. |
| `moussaka`, `dolmades`, `bifteki` | 0.85 | Specific food dishes that sometimes appear in POS names. |

### 5. Bakeries & Sweets

These blur the line slightly with "Groceries", but in an expense tracker, a bakery run is usually considered Food/Snacks.

| Keyword (Normalized) | Weight | Match Type / Rationale |
| --- | --- | --- |
| `zaxaroplasteio`, `fournos`, `bakery` | 0.95 | Primary indicators for bakeries and pastry shops. |
| `bougatsa`, `krepari`, `crepe`, `koulouri` | 0.95 | Specific breakfast/sweet indicators. |
| `terkenlis`, `choureal`, `ble` | 0.98 | Famous high-volume pastry/bakery chains. |
| `gelato`, `ice cream`, `pagoto` | 0.95 | Desserts and summer treats. |

### 6. Generic & Ambiguous Keywords

These keywords require a lower weight (0.60 - 0.85) because they can easily cross over into other categories (e.g., "Kitchen" could be a restaurant or a home goods store).

| Keyword (Normalized) | Weight | Match Type / Rationale |
| --- | --- | --- |
| `restaurant`, `pizza`, `coffee`, `cafe`, `burger`, `sushi`, `steak` | 0.90 | High confidence generic food terms. |
| `bistro`, `grill`, `pub`, `bar`, `drink` | 0.80 | Medium confidence. |
| `roasters`, `kitchen`, `deli` | 0.70 | Context needed. Could be coffee/food, could be retail. |
| `house`, `corner`, `street`, `room` | 0.40 | Highly context-dependent (e.g., "Coffee Corner" vs. "Street Corner Kiosk"). |

---

### Implementation Note for your Code:

To maximize the efficiency of this list in your `SemanticKeywordMatcher`, make sure you run the input string through your `GreeklishNormalizer` *before* checking against these keywords. That way, you only need to store the latinized versions (e.g., `fournos`) rather than both (`φουρνος` and `fournos`).

Would you like me to map out the next highest-volume category for you, such as **Groceries & Supermarkets** or **Transport & Auto**?

Building a semantic keyword matcher is a massive step up from strict dictionary lookups. Your 5-layer hybrid architecture is a very solid approach for handling the chaos of raw POS data, especially with the unique challenges of Greeklish and regional suffixes.

To get your `SemanticKeywordMatcher` off the ground, we should start with the most common, high-volume expense categories: **Food & Dining**, **Groceries**, **Transport**, **Shopping**, and **Bills/Utilities**.

Here is an organized starting dictionary tailored to your Greek market context, breaking down keywords by confidence weight. As outlined in your plan, high-confidence keywords act as primary business indicators (0.90–0.98), medium-confidence words might be ambiguous (0.65–0.80), and low-confidence words are context-dependent (0.40).

### 1. Food & Dining

This category has the highest variation but also the most obvious semantic indicators.

| Keyword / Pattern | Weight | Rationale / Notes |
| --- | --- | --- |
| `pizza`, `coffee`, `cafe`, `restaurant`, `taverna`, `souvlaki`, `burger`, `sushi`, `steak` | 0.95 | Primary business indicators. |
| `efood`, `wolt`, `box`, `mcdonalds`, `kfc`, `goodys`, `gregorys`, `everest` | 0.98 | Exact brand matches or delivery aggregators. |
| `psistaria`, `krep`, `crepe`, `gyros`, `zaxaroplasteio`, `fournos` | 0.95 | Strong local Greek dining/snack indicators. |
| `bistro`, `grill`, `roasters`, `kitchen` | 0.65 - 0.80 | Medium confidence. Can be ambiguous (e.g., kitchen store vs. restaurant). |
| `bar`, `pub`, `club`, `drink` | 0.85 | Usually maps to Food/Dining or a specific Entertainment subcategory. |
| `house`, `corner` | 0.40 | Context-dependent (e.g., Pizza House vs. House of Fashion). |

### 2. Groceries

Supermarkets and local food markets.

| Keyword / Pattern | Weight | Rationale / Notes |
| --- | --- | --- |
| `sklavenitis`, `ab`, `lidl` | 0.98 | High confidence brand matches. |
| `masoutis`, `mymarket`, `galaxias`, `kritis`, `discount` | 0.98 | Additional major Greek supermarket chains. |
| `supermarket`, `market`, `grocery`, `bakery`, `butcher`, `fishmarket` | 0.85 - 0.95 | Strong generic grocery indicators. |
| `kava`, `mini market`, `minimarket`, `manaviko`, `frouta` | 0.90 | Local grocery subsets. |
| `deli`, `delicatessen` | 0.80 | High likelihood of being groceries, but could be a restaurant. |

### 3. Transport & Auto

Fuel, public transit, and vehicle maintenance.

| Keyword / Pattern | Weight | Rationale / Notes |
| --- | --- | --- |
| `gas`, `fuel`, `petrol`, `esso`, `taxi`, `uber`, `parking`, `tolls` | 0.90 - 0.95 | Primary transport indicators. |
| `freenow`, `beat`, `oasa`, `oasth`, `trainose`, `hellenic train`, `ktel` | 0.95 | High confidence local transit and taxi apps. |
| `aegean`, `ryanair`, `sky express`, `olympic` | 0.95 | Travel/Flights subcategory. |
| `eko`, `avin`, `revoil`, `aegean oil`, `coral` | 0.95 | Major local gas station chains. |
| `shell`, `bp` | 0.75 | Medium confidence. Shell could be jewelry, BP could be British Petroleum. |

### 4. Shopping & Retail

General merchandise, electronics, and clothing.

| Keyword / Pattern | Weight | Rationale / Notes |
| --- | --- | --- |
| `zara`, `h&m`, `bershka`, `pull&bear`, `attica` | 0.95 | Major clothing retailers. |
| `public`, `kotsovolos`, `plaisio`, `skroutz`, `germanos` | 0.95 | Electronics and general retail. |
| `jumbo`, `ikea`, `leroy merlin`, `praktiker` | 0.95 | Home and department stores. |
| `pharmacy`, `farmakeio`, `hondos`, `sephora` | 0.95 | Health & Beauty retail. |
| `shop`, `store`, `boutique`, `mall`, `outlet` | 0.60 | Very ambiguous. Needs the contextual layer to push confidence higher. |

### 5. Bills & Utilities

Recurring essential expenses.

| Keyword / Pattern | Weight | Rationale / Notes |
| --- | --- | --- |
| `dei`, `heron`, `protergia`, `nrg`, `elpedison`, `volton` | 0.98 | Power providers. |
| `cosmote`, `vodafone`, `nova`, `wind` | 0.98 | Telecommunications. |
| `eydap`, `eyath` | 0.98 | Water utilities. |
| `telecom`, `internet`, `mobile` | 0.85 | Generic utility keywords. |
| `energy`, `power`, `water` | 0.70 | Medium confidence, might appear in other contexts (e.g., Power Fitness). |

---

Would you like me to map out the exact Regex multi-word patterns for these new categories, similar to the `Regex("^pizza\\s+.+$")` pattern you have in your plan?

Groceries is a brilliant category to tackle next. In Greece, categorizing groceries is tricky because you have a massive split between giant national chains and the hyper-local neighborhood shops (the butcher, the greengrocer, the local *kava*).

Since your `GreeklishNormalizer` will convert everything to Latin characters first, we only need to worry about the normalized Latin roots.

Here is an in-depth semantic breakdown for **Groceries**, structured to feed directly into your 5-layer system's `SemanticKeywordMatcher`.

### 1. The Supermarket Giants & Discounters

These are the anchors. If these exact names (or variations generated by your canonicalizer) appear, it is a 98% guaranteed grocery expense.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `sklavenitis`, `ab`, `vassilopoulos`, `lidl`, `masoutis` | 0.98 | The biggest players. "Masoutis" is especially dominant since you are based in Northern Greece/Thessaloniki. |
| `mymarket`, `galaxias`, `kritikos`, `market in` | 0.98 | Major national and regional supermarket chains. |
| `bazaar`, `discount markt`, `ellinika market` | 0.95 | Popular discount and franchise supermarket networks. |

### 2. General Store Formats & Types

These words describe the *type* of business. They are strong standalone indicators, though slightly less guaranteed than a direct brand name.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `supermarket`, `minimarket`, `mini market` | 0.95 | Direct format descriptors. Near certainty. |
| `grocery`, `groceries`, `pantopolio` | 0.95 | The core category names in English and Greeklish. |
| `kava`, `cava`, `liquor` | 0.90 | Liquor stores. In expense trackers, these usually roll up into Groceries (or a specific Alcohol subcategory if you have one). |
| `deli`, `delicatessen`, `allantopoieio` | 0.85 | High confidence for groceries, though sometimes they have a sit-down dining element. |

### 3. The Neighborhood Specialists

Greeks do a lot of decentralized grocery shopping. Identifying the specific trade is crucial for catching local merchants.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `kreopolio`, `butcher`, `kreatagora` | 0.95 | Butcher shops. Highly specific to groceries. |
| `manaviko`, `greengrocer`, `froutagora` | 0.95 | Greengrocers / fruit and vegetable markets. |
| `ichthuopolio`, `psaradiko`, `fishmarket` | 0.95 | Fishmongers. |
| `tirokomika`, `galaktokomika`, `dairy` | 0.95 | Specialized dairy and cheese shops. |
| `ksiroi karpoi`, `nuts`, `kafekopteio` | 0.90 | Nut and traditional coffee grinding shops. (While *kafekopteio* grinds coffee, the transactions are bulk grocery purchases, not a cup of coffee). |

### 4. Core Products & Semantic Roots

Sometimes a POS machine just registers the primary product being sold. These need slightly lower weights because the word alone could be used in a restaurant name (e.g., "The Meat Bar").

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `kreas`, `meat`, `beef`, `pork`, `chicken` | 0.80 | Primary proteins. |
| `frouta`, `lachanika`, `fruit`, `veg` | 0.80 | Produce indicators. |
| `tiri`, `cheese`, `feta`, `milk` | 0.80 | Dairy indicators. |
| `pota`, `drinks`, `wine`, `beer`, `craft` | 0.75 | Alcohol indicators. Needs context—could easily be a bar/pub. |
| `paradosiaka`, `traditional`, `topika` | 0.70 | "Traditional products" is a very common name for local deli shops, but could also be a taverna ("Traditional Grill"). |

### 5. Modifiers & Vibe Words (Context Builders)

Just like the "Food & Dining" modifiers, these are low-weight words. They are risky on their own but become powerful when matched with the core products using Regex.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `fresh`, `fresco`, `fresko` | 0.50 | Quality modifier. "Fresh Market" vs. "Fresh Hair Salon". |
| `farm`, `farma`, `agro`, `agrotika` | 0.50 | Origin modifiers. |
| `bio`, `organic`, `vegan`, `green` | 0.50 | Niche grocery modifiers. |
| `market`, `agora` | 0.40 | Highly ambiguous. "Meat Market" = Groceries. "Tech Market" = Shopping. "Flea Market" = Entertainment. |
| `discount`, `offers`, `economy` | 0.30 | Pricing modifiers. |

### Putting it together in your SemanticKeywordMatcher

To catch those ambiguous neighborhood shops, you can build Regex patterns that combine the Modifiers (Table 5) with the Products (Table 4) or Formats (Table 2).

For example, to catch a generic local market named "Green Farm" or "Fresh Agora":
`Regex(".*(fresh|fresco|farm|farma|green|bio)\\s+(market|agora|grocery|frouta|kreas).*")` -> **Score: 0.85**

Would you like me to map out the **Shopping & Retail** category next (clothes, electronics, home goods), or do you want to dive into **Transport**?

This is a massive and diverse category. "Shopping & Retail" is usually the catch-all for anything that isn't food, transport, or bills. Because it covers everything from a new iPhone to a pair of socks or a bottle of aspirin, we need to break it down into specialized retail sub-sectors.

Since your `GreeklishNormalizer` handles the alphabet conversion, I have focused on the normalized Latin roots of both English and Greek retail terms.

Here is the deep-dive semantic dictionary for **Shopping & Retail**, structured for your `SemanticKeywordMatcher`.

### 1. Technology & Electronics

Tech purchases are usually high-value, so catching them accurately is vital for a user's budget insights.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `kotsovolos`, `plaisio`, `public`, `germanos`, `istorm` | 0.98 | The undisputed tech and entertainment giants in Greece. |
| `skroutz`, `shopflix` | 0.98 | Major local e-commerce aggregators (almost exclusively retail/shopping). |
| `ilektronika`, `electronics`, `tech`, `computer` | 0.95 | Strong standalone category indicators. |
| `kiniti`, `mobile`, `telecom`, `repair`, `irepair` | 0.90 | Mobile phones and device repair shops. |
| `gadget`, `pc`, `mac`, `audio`, `sound` | 0.80 | Product indicators. "Sound" could be a music venue, but combined with "shop" it's retail. |

### 2. Clothing, Shoes & Accessories (Fast Fashion to Local Boutiques)

The fast-fashion giants are easy, but local boutiques often use trendy, ambiguous English words.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `zara`, `h&m`, `bershka`, `pull&bear`, `stradivarius`, `oysho` | 0.98 | Inditex group and major fast fashion. Guaranteed clothing hits. |
| `attica`, `notos`, `mango`, `bsb`, `toi&moi`, `celestino` | 0.98 | Premium department stores and massive Greek clothing brands. |
| `rouxa`, `clothing`, `apparel`, `menswear`, `womenswear` | 0.95 | Direct clothing indicators. |
| `papoutsia`, `shoes`, `sneakers`, `footwear` | 0.95 | Direct shoe indicators. |
| `kosmimata`, `jewelry`, `jewellery`, `watch`, `rolex` | 0.95 | Accessories and high-end retail. |
| `boutique`, `fashion`, `style`, `wear`, `collection` | 0.85 | Common naming conventions for local, independent clothing stores. |
| `optika`, `optics`, `glasses`, `sunglasses`, `eyewear` | 0.95 | Opticians (usually classed under shopping or a health subcategory). |

### 3. Health, Beauty & Personal Care

In Greece, pharmacies (*farmakeia*) function heavily as retail stores for cosmetics and skincare, not just medicine.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `hondos`, `hondos center`, `sephora`, `dust&cream`, `mac` | 0.98 | The major beauty and cosmetics retail anchors. |
| `farmakeio`, `pharmacy`, `apotheke` | 0.95 | Primary indicators for pharmacies. |
| `kallyntika`, `cosmetics`, `beauty`, `makeup` | 0.95 | Standalone beauty retail indicators. |
| `kommotirio`, `hairsalon`, `barber`, `nails`, `spa` | 0.90 | Personal care services. Often grouped under a "Shopping & Services" or "Personal Care" category in expense trackers. |
| `aroma`, `parfum`, `perfume` | 0.90 | Fragrance shops. |

### 4. Home, DIY, Books & Hobbies

Everything from building a house to buying a board game.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `jumbo`, `ikea`, `leroy merlin`, `praktiker`, `jysk` | 0.98 | The titans of home goods, toys, and DIY. |
| `moustakas`, `max stores`, `prenatal`, `mothercare` | 0.98 | Major toy and baby retail chains. |
| `epipila`, `furniture`, `stromata`, `mattress` | 0.95 | Direct home goods indicators. |
| `xromata`, `ergaleia`, `tools`, `hardware`, `diy` | 0.95 | Hardware and DIY stores. |
| `vivliopoleio`, `bookstore`, `books`, `vivlia` | 0.95 | Bookstores and stationery. |
| `paixnidia`, `toys`, `hobby`, `crafts` | 0.90 | Hobby and toy shops. |
| `athlitika`, `sports`, `intersport`, `zakret`, `cosmossport` | 0.95 | Sporting goods chains and generic indicators. |

### 5. Generic Retail Modifiers (Context Builders)

These are the extremely common words that simply mean "a place of business." In your 5-layer system, these carry very low weight on their own, but are crucial for Regex combinations.

| Semantic Keyword (Normalized) | Weight | Rationale / Notes |
| --- | --- | --- |
| `shop`, `store`, `stores`, `retail` | 0.40 | The most basic retail descriptors. Could be a coffee *shop*, but usually points to retail. |
| `center`, `mall`, `plaza`, `outlet` | 0.40 | Retail locations. Usually stripped by your `MerchantCanonicalizer`, but good to catch if they slip through. |
| `emporio`, `trade`, `trading`, `import` | 0.30 | Business-to-business or wholesale descriptors that often end up on retail POS machines. |
| `eshop`, `e-shop`, `online` | 0.50 | E-commerce indicators. Highly likely to be Shopping. |
| `bazaar`, `stock`, `clearance` | 0.40 | Discount retail concepts. |

### Building Regex Patterns for Retail

Retail names love to combine a vibe word with a format word. You can use your multi-word pattern matcher to catch independent stores:

* **Catching independent online stores:** `Regex(".*(eshop|online|shop)\\s+(fashion|tech|home|beauty).*")` -> **Score: 0.85**
* **Catching local clothing boutiques:**
`Regex(".*(boutique|collection|wear|style).*")` -> **Score: 0.80** (This is a solid middle-ground score that lets the system auto-categorize but might flag it for the user if they want to get more specific).

Would you like to move on to **Transport & Auto** (fuel, transit, parking, mechanics), or would you prefer to tackle **Bills & Utilities** next?