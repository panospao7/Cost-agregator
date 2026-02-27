package com.yourname.expensetracker.domain.categorization

data class KeywordCategory(
    val categoryName: String,
    val keywords: Map<String, Double>
)

object CategoryKeywords {
    
    val KEYWORDS = listOf(
        
        // ═══════════════════════════════════════════════════════════════
        // 🍔 FOOD & DINING
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Food", mapOf(
            // Delivery Apps (highest confidence)
            "efood" to 0.98, "wolt" to 0.98, "box" to 0.98, "foodbag" to 0.98,
            "the chefz" to 0.95, "fagi" to 0.95, "deliveroo" to 0.95,
            "glovo" to 0.95, "uber eats" to 0.95, "just eat" to 0.95,
            
            // Coffee Chains
            "mikel" to 0.98, "coffee island" to 0.98, "coffee berry" to 0.98,
            "coffee lab" to 0.98, "everest" to 0.98, "gregorys" to 0.98,
            "grigoris" to 0.98, "bruno" to 0.98, "starbucks" to 0.98,
            "costa coffee" to 0.98, "il toto" to 0.95, "taf coffee" to 0.95,
            "redd coffee" to 0.95,
            
            // Fast Food & Pizza
            "goodys" to 0.98, "mcdonalds" to 0.98, "kfc" to 0.98,
            "burger king" to 0.98, "pizza fan" to 0.98, "roma pizza" to 0.98,
            "l'artigiano" to 0.98, "dominos" to 0.98, "pizza hut" to 0.98,
            "pita pan" to 0.95, "savvikos" to 0.95, "thess bao" to 0.95,
            "tarantino" to 0.95, "pizza poselli" to 0.95, "aladin foods" to 0.95,
            "mailos" to 0.95,
            
            // Traditional Greek
            "souvlaki" to 0.95, "gyros" to 0.95, "psistaria" to 0.95,
            "kebab" to 0.95, "taverna" to 0.95, "estiatorio" to 0.95,
            "mageirio" to 0.95, "mezedopoleio" to 0.95, "ouzeri" to 0.95,
            "tsipouradiko" to 0.95, "kafenio" to 0.90, "kafeteria" to 0.90,
            "kantina" to 0.90,
            
            // Semantic Keywords (food-related)
            "espresso" to 0.95, "cappuccino" to 0.95, "freddo" to 0.95,
            "latte" to 0.95, "mocha" to 0.95,
            "barista" to 0.85, "roaster" to 0.85, "roasters" to 0.85,
            "brew" to 0.85, "brewing" to 0.85,
            "bean" to 0.75, "blend" to 0.75, "grind" to 0.75, "drip" to 0.75,
            "juice" to 0.90, "smoothie" to 0.90, "tea" to 0.90, "matcha" to 0.90,
            
            // Bakery & Sweets
            "pastry" to 0.95, "waffle" to 0.95, "pancake" to 0.95,
            "churros" to 0.95, "donut" to 0.95, "donuts" to 0.95,
            "bake" to 0.90, "baker" to 0.90, "bakes" to 0.90, "bakery" to 0.90,
            "zaxaroplasteio" to 0.95, "fournos" to 0.95, "terkenlis" to 0.98,
            "choureal" to 0.98, "ble" to 0.98,
            "bougatsa" to 0.95, "krepari" to 0.95, "crepe" to 0.95,
            "koulouri" to 0.95, "gelato" to 0.95, "ice cream" to 0.95, "pagoto" to 0.95,
            
            // Generic Food Terms
            "pizza" to 0.95, "coffee" to 0.95, "cafe" to 0.95, "restaurant" to 0.95,
            "burger" to 0.95, "sushi" to 0.95, "steak" to 0.95,
            "bistro" to 0.80, "grill" to 0.80, "pub" to 0.85, "bar" to 0.85,
            "diner" to 0.85, "trattoria" to 0.95, "osteria" to 0.95,
            "brasserie" to 0.95, "cantina" to 0.95,
            
            // Ambiguous (need context)
            "roasters" to 0.70, "kitchen" to 0.65, "deli" to 0.80,
            "house" to 0.40, "corner" to 0.40, "room" to 0.40,
            "lovers" to 0.30, "makers" to 0.30, "addicts" to 0.30,
            "project" to 0.30, "lab" to 0.30, "factory" to 0.30,
            "spot" to 0.40, "hub" to 0.40, "yard" to 0.40, "garden" to 0.40,
            "art" to 0.20, "boutique" to 0.20, "concept" to 0.20, "studio" to 0.20,
            "street" to 0.30, "urban" to 0.30, "local" to 0.30, "daily" to 0.30
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 🛒 GROCERIES
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Groceries", mapOf(
            // Major Chains (highest confidence)
            "sklavenitis" to 0.98, "ab" to 0.98, "vassilopoulos" to 0.98,
            "lidl" to 0.98, "masoutis" to 0.98, "mymarket" to 0.98,
            "galaxias" to 0.98, "kritikos" to 0.98, "market in" to 0.98,
            "bazaar" to 0.95, "discount markt" to 0.95, "spar" to 0.95,
            "carrefour" to 0.95, "kaufland" to 0.95, "aldi" to 0.95,
            "tesco" to 0.95, "metro" to 0.95,
            
            // Generic Store Types
            "supermarket" to 0.95, "minimarket" to 0.95, "mini market" to 0.95,
            "grocery" to 0.95, "groceries" to 0.95, "pantopolio" to 0.95,
            "kava" to 0.90, "cava" to 0.90, "liquor" to 0.90,
            
            // Specialty Shops
            "deli" to 0.85, "delicatessen" to 0.85,
            "kreopolio" to 0.95, "butcher" to 0.95, "kreatagora" to 0.95,
            "manaviko" to 0.95, "greengrocer" to 0.95, "froutagora" to 0.95,
            "ichthuopolio" to 0.95, "psaradiko" to 0.95, "fishmarket" to 0.95,
            "tirokomika" to 0.95, "galaktokomika" to 0.95, "dairy" to 0.95,
            "ksiroi karpoi" to 0.90, "nuts" to 0.90, "kafekopteio" to 0.90,
            
            // Products (lower confidence alone)
            "kreas" to 0.80, "meat" to 0.80, "beef" to 0.80, "pork" to 0.80,
            "chicken" to 0.80, "frouta" to 0.80, "lachanika" to 0.80,
            "fruit" to 0.80, "veg" to 0.80, "vegetables" to 0.80,
            "tiri" to 0.80, "cheese" to 0.80, "feta" to 0.80, "milk" to 0.80,
            "pota" to 0.75, "drinks" to 0.75, "wine" to 0.75, "beer" to 0.75,
            
            // Modifiers
            "fresh" to 0.50, "fresco" to 0.50, "fresko" to 0.50,
            "farm" to 0.50, "farma" to 0.50, "agro" to 0.50, "agrotika" to 0.50,
            "bio" to 0.50, "organic" to 0.50, "vegan" to 0.50, "green" to 0.50,
            "traditional" to 0.70, "paradosiaka" to 0.70, "topika" to 0.70
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 🚗 TRANSPORT
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Transport", mapOf(
            // Fuel Stations
            "shell" to 0.75, "bp" to 0.75, "eko" to 0.95, "avin" to 0.95,
            "revoil" to 0.95, "aegean oil" to 0.95, "coral" to 0.95,
            "esso" to 0.90, "total" to 0.90,
            "gas" to 0.95, "fuel" to 0.95, "petrol" to 0.95,
            "gas station" to 0.95, "fuel station" to 0.95,
            
            // Transit & Taxi
            "uber" to 0.95, "taxi" to 0.90, "beat" to 0.95, "freenow" to 0.95,
            "oasa" to 0.95, "oasth" to 0.95, "trainose" to 0.95,
            "hellenic train" to 0.95, "ktel" to 0.95,
            
            // Airlines
            "aegean" to 0.95, "ryanair" to 0.95, "sky express" to 0.95,
            "olympic" to 0.95, "wizz air" to 0.95,
            
            // Parking & Tolls
            "parking" to 0.90, "tolls" to 0.90, "attiki odos" to 0.95,
            
            // Car Rental
            "hertz" to 0.95, "avis" to 0.95, "europcar" to 0.95,
            "enterprise" to 0.95, "budget" to 0.95, "sixt" to 0.95
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 🛍️ SHOPPING
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Shopping", mapOf(
            // Electronics
            "kotsovolos" to 0.98, "plaisio" to 0.98, "public" to 0.98,
            "germanos" to 0.98, "istorm" to 0.98,
            "skroutz" to 0.98, "shopflix" to 0.98,
            "ilektronika" to 0.95, "electronics" to 0.95, "tech" to 0.95,
            "computer" to 0.95, "mobile" to 0.90, "repair" to 0.90,
            
            // Fashion
            "zara" to 0.98, "h&m" to 0.98, "bershka" to 0.98,
            "pull&bear" to 0.98, "stradivarius" to 0.98, "oysho" to 0.98,
            "attica" to 0.98, "notos" to 0.98, "mango" to 0.98,
            "bsb" to 0.98, "toi&moi" to 0.98, "celestino" to 0.98,
            "clothing" to 0.95, "apparel" to 0.95, "shoes" to 0.95,
            "sneakers" to 0.95, "footwear" to 0.95,
            
            // Beauty & Pharmacy
            "hondos" to 0.98, "sephora" to 0.98, "mac" to 0.95,
            "farmakeio" to 0.95, "pharmacy" to 0.95, "apotheke" to 0.95,
            "cosmetics" to 0.95, "beauty" to 0.95, "makeup" to 0.95,
            
            // Home & DIY
            "jumbo" to 0.98, "ikea" to 0.98, "leroy merlin" to 0.98,
            "praktiker" to 0.98, "jysk" to 0.98,
            "moustakas" to 0.98, "prenatal" to 0.98, "mothercare" to 0.98,
            "furniture" to 0.95, "stromata" to 0.95, "mattress" to 0.95,
            "tools" to 0.95, "hardware" to 0.95, "diy" to 0.95,
            "xromata" to 0.95, "ergaleia" to 0.95,
            
            // Books & Hobbies
            "vivliopoleio" to 0.95, "bookstore" to 0.95, "books" to 0.95,
            "paixnidia" to 0.90, "toys" to 0.90, "hobby" to 0.90,
            "sports" to 0.95, "intersport" to 0.95, "zakret" to 0.95,
            
            // Accessories
            "jewelry" to 0.95, "jewellery" to 0.95, "watch" to 0.95,
            "rolex" to 0.95, "optika" to 0.95, "optics" to 0.95,
            
            // Generic (low confidence)
            "shop" to 0.40, "store" to 0.40, "retail" to 0.40,
            "center" to 0.40, "mall" to 0.40, "outlet" to 0.40,
            "emporio" to 0.30, "eshop" to 0.50, "online" to 0.50
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 💡 BILLS & UTILITIES
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Utilities", mapOf(
            // Power
            "dei" to 0.98, "heron" to 0.98, "protergia" to 0.98,
            "nrg" to 0.98, "elpedison" to 0.98, "volton" to 0.98,
            
            // Telecom
            "cosmote" to 0.98, "vodafone" to 0.98, "nova" to 0.98,
            "wind" to 0.95,
            
            // Water
            "eydap" to 0.98, "eyath" to 0.98,
            
            // Generic
            "telecom" to 0.85, "internet" to 0.85, "mobile" to 0.85,
            "energy" to 0.70, "power" to 0.70, "water" to 0.70
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 🎬 ENTERTAINMENT
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Entertainment", mapOf(
            "cinema" to 0.95, "theater" to 0.95, "theatre" to 0.95,
            "θέατρο" to 0.95, "κινηματογράφος" to 0.95,
            "netflix" to 0.98, "spotify" to 0.98, "youtube" to 0.95,
            "hbo" to 0.98, "disney" to 0.98, "prime video" to 0.98,
            "concert" to 0.95, "festival" to 0.95, "museum" to 0.95,
            "game" to 0.80, "gaming" to 0.80, "playstation" to 0.95,
            "xbox" to 0.95, "steam" to 0.95
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 💊 HEALTH
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Health", mapOf(
            "hospital" to 0.95, "ιατρείο" to 0.95, "clinic" to 0.95,
            "doctor" to 0.95, "γιατρός" to 0.95, "physician" to 0.95,
            "dentist" to 0.95, "οδοντίατρος" to 0.95, "dentista" to 0.95,
            "pharmacy" to 0.95, "farmakeio" to 0.95,
            "vet" to 0.95, "veterinary" to 0.95, "κτηνίατρος" to 0.95,
            "gym" to 0.90, "fitness" to 0.90, "γυμναστήριο" to 0.90,
            "yoga" to 0.90, "pilates" to 0.90
        )),
        
        // ═══════════════════════════════════════════════════════════════
        // 📱 SUBSCRIPTIONS
        // ═══════════════════════════════════════════════════════════════
        KeywordCategory("Subscriptions", mapOf(
            "netflix" to 0.98, "spotify" to 0.98, "apple music" to 0.98,
            "youtube premium" to 0.98, "hbo max" to 0.98, "disney+" to 0.98,
            "amazon prime" to 0.98, "prime video" to 0.98,
            "microsoft" to 0.90, "adobe" to 0.95, "office 365" to 0.95,
            "dropbox" to 0.95, "google one" to 0.95,
            "iphone" to 0.70, "android" to 0.60, "mobile" to 0.60,
            "subscription" to 0.85
        ))
    )
    
    fun getKeywordsForCategory(categoryName: String): Map<String, Double> {
        return KEYWORDS.find { it.categoryName == categoryName }?.keywords ?: emptyMap()
    }
    
    fun getAllKeywords(): Map<String, Map<String, Double>> {
        return KEYWORDS.associate { it.categoryName to it.keywords }
    }
    
    fun getCategories(): List<String> {
        return KEYWORDS.map { it.categoryName }
    }
}
