package com.yourname.expensetracker.data.provider

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory

object MerchantCategoryProvider {

    // Default Categories (ID mapping presumed or we look them up)
    // Actually we will map by Name to be safe, then Repository will resolve IDs.
    
    val categoryBlueprints = listOf(
        Category(name = "Groceries", icon = "🛒", color = "#4CAF50", isDefault = true),
        Category(name = "Transport", icon = "🚗", color = "#2196F3", isDefault = true),
        Category(name = "Food", icon = "🍽️", color = "#FF9800", isDefault = true), // Restaurants/Cafe
        Category(name = "Entertainment", icon = "🎬", color = "#9C27B0", isDefault = true),
        Category(name = "Shopping", icon = "🛍️", color = "#E91E63", isDefault = true),
        Category(name = "Health", icon = "💊", color = "#00BCD4", isDefault = true),
        Category(name = "Utilities", icon = "🏠", color = "#607D8B", isDefault = true),
        Category(name = "Subscriptions", icon = "📱", color = "#673AB7", isDefault = true),
        Category(name = "Travel", icon = "✈️", color = "#009688", isDefault = true), // Changed color slightly
        Category(name = "Electronics", icon = "💻", color = "#795548", isDefault = true),
        Category(name = "Education", icon = "📚", color = "#3F51B5", isDefault = true),
        Category(name = "Fitness", icon = "💪", color = "#8BC34A", isDefault = true),
        Category(name = "Beauty", icon = "💄", color = "#FF4081", isDefault = true),
        Category(name = "Pets", icon = "🐾", color = "#A1887F", isDefault = true),
        Category(name = "Home", icon = "🛋️", color = "#FF5722", isDefault = true), // New from list
        Category(name = "Kids", icon = "🧸", color = "#FFEB3B", isDefault = true), // New from list
        Category(name = "Gifts", icon = "🎁", color = "#F44336", isDefault = true), // New from list
        Category(name = "Banking", icon = "🏦", color = "#37474F", isDefault = true) // Fees etc
    )

    // Map of Merchant Name (or keyword) -> Category Name
    val merchantToCategoryMap = mapOf(
        // --- GROCERIES (23+) ---
        "AB Βασιλόπουλος" to "Groceries", "AB Vasilopoulos" to "Groceries", "AB BASILOPOULOS" to "Groceries", "AB SHOP" to "Groceries", "A.B." to "Groceries", "ALFA BETA" to "Groceries",
        "Σκλαβενίτης" to "Groceries", "Sklavenitis" to "Groceries", "SKLAVENITIS" to "Groceries", "ELLINIKES YPERAGORES" to "Groceries",
        "Lidl" to "Groceries", "LIDL HELLAS" to "Groceries", "LIDL ELLAS" to "Groceries",
        "My Market" to "Groceries", "MY MARKET" to "Groceries", "MYMARKET" to "Groceries",
        "Μασούτης" to "Groceries", "Masoutis" to "Groceries", "MASOUTIS" to "Groceries", "MASOYTHS" to "Groceries",
        "Γαλαξίας" to "Groceries", "Galaxias" to "Groceries", "GALAXIAS" to "Groceries", "PENTE SA" to "Groceries",
        "Κρητικός" to "Groceries", "Kritikos" to "Groceries", "KRITIKOS" to "Groceries", "ANEDIK KRITIKOS" to "Groceries",
        "Bazaar" to "Groceries", "BAZAAR" to "Groceries", "BAZAAR SM" to "Groceries",
        "Market In" to "Groceries", "MARKET IN" to "Groceries", "MARKETIN" to "Groceries",
        "The Mart" to "Groceries", "THE MART" to "Groceries", "THEMART" to "Groceries", "MAKRO" to "Groceries",
        "Aldi" to "Groceries", "Kaufland" to "Groceries", "Carrefour" to "Groceries", "Penny Market" to "Groceries",
        "PLUS Super Discount" to "Groceries", "PLUS SUPERMARKET" to "Groceries",
        "Χαλκιαδάκης" to "Groceries", "Chalkiadakis" to "Groceries", "HALKIADAKIS" to "Groceries",
        "OK! Anytime" to "Groceries", "OK MARKET" to "Groceries", "OK ANYTIME MARKETS" to "Groceries",
        "Σάββας" to "Groceries", "Savvas" to "Groceries", "3Α" to "Groceries", "3A" to "Groceries", "Discount Markt" to "Groceries",
        "Mini Market" to "Groceries", "Minimarket" to "Groceries", "Μινι Μαρκετ" to "Groceries",
        "Kiosk" to "Groceries", "Periptero" to "Groceries", "Περίπτερο" to "Groceries", "Psilika" to "Groceries", "Ψιλικα" to "Groceries",
        "Bakery" to "Groceries", "Baker" to "Groceries", "Φούρνος" to "Groceries", "Fournos" to "Groceries", "Artopoiio" to "Groceries", "Αρτοποιείο" to "Groceries",
        "Butcher" to "Groceries", "Kreopoleio" to "Groceries", "Κρεοπωλείο" to "Groceries",
        "Fish Shop" to "Groceries", "Ixthiopolio" to "Groceries", "Ιχθυοπωλείο" to "Groceries",
        "Greengrocer" to "Groceries", "Manaviko" to "Groceries", "Μανάβικο" to "Groceries",

        // --- TRANSPORT (48+) ---
        "Shell" to "Transport", "SHELL HELLAS" to "Transport", "SEHL" to "Transport", "CORAL AE" to "Transport",
        "BP" to "Transport", "BP HELLAS" to "Transport", "BRITISH PETROLEUM" to "Transport",
        "EKO" to "Transport", "EKO ABEE" to "Transport", "EKO KALYPSO" to "Transport",
        "Aegean" to "Transport", "Aegean Oil" to "Transport", "AEGEAN OIL" to "Transport",
        "Avin" to "Transport", "AVIN OIL" to "Transport", "MOTOR OIL" to "Transport",
        "Ελίν" to "Transport", "Elin" to "Transport", "ELIN OIL" to "Transport", "ELINOIL" to "Transport",
        "Revoil" to "Transport", "REVOIL" to "Transport", "Jet Oil" to "Transport", "JETOIL" to "Transport",
        "Cyclon" to "Transport", "CYCLON" to "Transport", "Coral Gas" to "Transport", "CORAL GAS" to "Transport",
        "Gas Station" to "Transport", "Fuel Station" to "Transport", "Πρατήριο" to "Transport", "Benzinadiko" to "Transport",
        
        "Uber" to "Transport", "UBER TRIP" to "Transport", "UBER BV" to "Transport",
        "Beat" to "Transport", "BEAT APP" to "Transport", "FREE NOW" to "Transport", "NOOW" to "Transport", "FREENOW" to "Transport",
        "Bolt" to "Transport", "BOLT EU" to "Transport",
        "Taxi" to "Transport", "Ταξί" to "Transport", "Cab" to "Transport", "Taxiplon" to "Transport",
        
        "KTEL" to "Transport", "ΚΤΕΛ" to "Transport", "KTEL ATTIKIS" to "Transport", "KTEL MACEDONIA" to "Transport",
        "OASA" to "Transport", "ΟΑΣΑ" to "Transport", "ATH.ENA TICKET" to "Transport",
        "OASTH" to "Transport", "ΟΑΣΘ" to "Transport",
        "Metro Athens" to "Transport", "STASY" to "Transport", "ΣΤΑΣΥ" to "Transport", "URBAN RAIL" to "Transport",
        "Tram" to "Transport", "Τραμ" to "Transport",
        "Hellenic Train" to "Transport", "TRAINOSE" to "Transport", "ΤΡΕΝΟΣΕ" to "Transport", "OSE" to "Transport", "ΟΣΕ" to "Transport",
        "ISAP" to "Transport", "ΗΣΑΠ" to "Transport",
        
        "Aegean Airlines" to "Travel", "AEGEAN AIR" to "Travel", "OLYMPIC AIR" to "Travel", "OLYMPIC AIRLINES" to "Travel",
        "Ryanair" to "Travel", "RYANAIR" to "Travel", "EasyJet" to "Travel", "EASYJET" to "Travel",
        "Sky Express" to "Travel", "SKY EXPRESS" to "Travel", "Volotea" to "Travel", "Wizz Air" to "Travel",
        "Lufthansa" to "Travel", "Swiss Air" to "Travel", "British Airways" to "Travel", "Air France" to "Travel", "KLM" to "Travel",
        "Blue Star" to "Travel", "BLUE STAR FERRIES" to "Travel", "ANEK" to "Travel", "ANEK LINES" to "Travel",
        "Minoan" to "Travel", "MINOAN LINES" to "Travel", "Hellenic Seaways" to "Travel", "HSW" to "Travel", "Seajets" to "Travel", "SEAJETS" to "Travel",
        "Golden Star" to "Travel", "Fast Ferries" to "Travel", "Superfast" to "Travel", "Ferry" to "Travel", "Ticket" to "Travel",

        "Parking" to "Transport", "Parkin" to "Transport", "Parkingmycity" to "Transport", "Cityzen" to "Transport", "Polis Park" to "Transport",
        "E-pass" to "Transport", "EPASS" to "Transport", "Attiki Odos" to "Transport", "ATTIKI ODOS" to "Transport",
        "Nea Odos" to "Transport", "NEA ODOS" to "Transport", "Olympia Odos" to "Transport", "OLYMPIA ODOS" to "Transport",
        "Egnatia Odos" to "Transport", "EGNATIA ODOS" to "Transport", "Moreas" to "Transport", "MOREAS" to "Transport", "Kentriki Odos" to "Transport",
        "Teb" to "Transport", "GEFYRA" to "Transport", "Rio Antirio" to "Transport",
        
        "Hertz" to "Travel", "Avis" to "Travel", "Europcar" to "Travel", "Enterprise" to "Travel", "Budget" to "Travel", "Sixt" to "Travel",
        "Lime" to "Transport", "Tier" to "Transport", "Bird" to "Transport",

        // --- FOOD & RESTAURANTS (35+) ---
        "Starbucks" to "Food", "STARBUCKS" to "Food",
        "Gregorys" to "Food", "GREGORYS" to "Food", "GRIGORIS" to "Food", "Γρηγόρης" to "Food", "MΙΚΡΟΓΕΥΜΑΤΑ" to "Food",
        "Everest" to "Food", "EVEREST" to "Food",
        "Mikel" to "Food", "MIKEL" to "Food", "MIKEL COFFEE" to "Food",
        "Coffee Island" to "Food", "COFFEE ISLAND" to "Food", "KAFEKOPTEIO" to "Food",
        "Coffee Lab" to "Food", "COFFEE LAB" to "Food",
        "Flocafe" to "Food", "FLOCAFE" to "Food",
        "Costa Coffee" to "Food", "COSTA COFFEE" to "Food",
        "Coffee Berry" to "Food", "COFFEE BERRY" to "Food",
        "Bruno" to "Food", "BRUNO COFFEE" to "Food", "Cultivos" to "Food", "CULTIVOS" to "Food",
        "McDonalds" to "Food", "MCDONALDS" to "Food", "MCD" to "Food",
        "Goodys" to "Food", "Goody's" to "Food", "GOODYS" to "Food", "GOODY'S BURGER HOUSE" to "Food",
        "KFC" to "Food", "KENTUCKY FRIED CHICKEN" to "Food",
        "Pizza Hut" to "Food", "PIZZA HUT" to "Food",
        "Dominos" to "Food", "DOMINOS" to "Food", "DOMINO'S" to "Food",
        "Pizza Fan" to "Food", "PIZZA FAN" to "Food",
        "Roma Pizza" to "Food", "ROMA PIZZA" to "Food", "L'Artigiano" to "Food",
        "Burger King" to "Food", "BURGER KING" to "Food", "Subway" to "Food", "SUBWAY" to "Food",
        "TGI Fridays" to "Food", "TGI FRIDAYS" to "Food", "FRIDAYS" to "Food",
        "Wagamama" to "Food", "WAGAMAMA" to "Food", "Noodle Bar" to "Food", "NOODLE BAR" to "Food",
        "Hard Rock" to "Food", "HARD ROCK CAFE" to "Food",
        "efood" to "Food", "E-FOOD" to "Food", "EFOOD" to "Food", "ONLINE DELIVERY" to "Food",
        "Wolt" to "Food", "WOLT" to "Food", "WOLT GREECE" to "Food",
        "Box" to "Food", "BOX DELIVERY" to "Food", "BOX NOW" to "Food",
        "Uber Eats" to "Food", "UBER EATS" to "Food", "Glovo" to "Food", "GLOVO" to "Food",
        "Cafe" to "Food", "Καφέ" to "Food", "Kafeneio" to "Food", "Bar" to "Food", "Club" to "Food",
        "Restaurant" to "Food", "Εστιατόριο" to "Food", "Estiatorio" to "Food", "Taverna" to "Food", "Ταβέρνα" to "Food",
        "Souvlaki" to "Food", "Σουβλάκι" to "Food", "Psistaria" to "Food", "Grill" to "Food",
        "Take away" to "Food", "Delivery" to "Food",

        // --- SHOPPING (46+) ---
        "Zara" to "Shopping", "ZARA" to "Shopping", "ZARA HELLAS" to "Shopping", "ITX HELLAS" to "Shopping",
        "H&M" to "Shopping", "H & M" to "Shopping", "H AND M" to "Shopping", "HENNES" to "Shopping",
        "Pull&Bear" to "Shopping", "PULL AND BEAR" to "Shopping", "PULL&BEAR" to "Shopping",
        "Bershka" to "Shopping", "BERSHKA" to "Shopping", "Stradivarius" to "Shopping", "STRADIVARIUS" to "Shopping",
        "Massimo Dutti" to "Shopping", "MASSIMO DUTTI" to "Shopping", "Mango" to "Shopping", "MANGO" to "Shopping",
        "Oysho" to "Shopping", "OYSHO" to "Shopping", "Intimissimi" to "Shopping", "INTIMISSIMI" to "Shopping",
        "Calzedonia" to "Shopping", "CALZEDONIA" to "Shopping", "Tezenis" to "Shopping", "TEZENIS" to "Shopping",
        "Nike" to "Shopping", "NIKE" to "Shopping", "NIKE RETAIL" to "Shopping",
        "Adidas" to "Shopping", "ADIDAS" to "Shopping", "Puma" to "Shopping", "PUMA" to "Shopping", "Reebok" to "Shopping", "REEBOK" to "Shopping",
        "Under Armour" to "Shopping", "UNDER ARMOUR" to "Shopping", "New Balance" to "Shopping", "Asics" to "Shopping",
        "Intersport" to "Shopping", "INTERSPORT" to "Shopping", "Cosmos Sport" to "Shopping", "COSMOS SPORT" to "Shopping",
        "Zakcret" to "Shopping", "ZAKCRET" to "Shopping", "Sports Factory" to "Shopping", "Foot Locker" to "Shopping",
        "Attica" to "Shopping", "ATTICA" to "Shopping", "ATTICA DEPT" to "Shopping",
        "Factory Outlet" to "Shopping", "FACTORY OUTLET" to "Shopping", "McArthurGlen" to "Shopping", "DESIGNER OUTLET" to "Shopping",
        "Notos" to "Shopping", "NOTOS GALLERIES" to "Shopping", "Fokas" to "Shopping",
        "Decathlon" to "Shopping", "DECATHLON" to "Shopping",
        "IKEA" to "Shopping", "IKEA" to "Shopping", "HM HOUSEMARKET" to "Shopping",
        "Leroy Merlin" to "Shopping", "LEROY MERLIN" to "Shopping", "S.G.B. AE" to "Shopping",
        "Praktiker" to "Shopping", "PRAKTIKER" to "Shopping",
        "Jumbo" to "Shopping", "JUMBO" to "Shopping", "Moustakas" to "Shopping", "MOUSTAKAS" to "Shopping",
        "Flying Tiger" to "Shopping", "FLYING TIGER" to "Shopping", "TIGER" to "Shopping", "Zebra" to "Shopping",
        "Miniso" to "Shopping", "MINISO" to "Shopping",
        "Pandora" to "Shopping", "PANDORA" to "Shopping", "Swarovski" to "Shopping", "SWAROVSKI" to "Shopping",
        "Tous" to "Shopping", "TOUS" to "Shopping", "Folli Follie" to "Shopping", "FOLLI FOLLIE" to "Shopping",
        "Sephora" to "Shopping", "SEPHORA" to "Shopping", "MAC" to "Shopping", "MAC COSMETICS" to "Shopping",
        "Hondos Center" to "Shopping", "HONDOS" to "Shopping", "HC" to "Shopping", "HONDOS CENTER" to "Shopping",
        "Gallerie de Beaute" to "Shopping", "GALLERIE DE BEAUTE" to "Shopping",
        "Kalogirou" to "Shopping", "KALOGIROU" to "Shopping", "Tsakiris Mallas" to "Shopping", "Mourtzi" to "Shopping",

        // --- ELECTRONICS (29+) ---
        "Skroutz" to "Electronics", "SKROUTZ" to "Electronics", "SKROUTZ.GR" to "Electronics", "PAYMENTS SKROUTZ" to "Electronics",
        "Public" to "Electronics", "PUBLIC" to "Electronics", "PUBLIC RETAIL" to "Electronics", "PUBLIC.GR" to "Electronics",
        "Plaisio" to "Electronics", "PLAISIO" to "Electronics", "PLAISIO COMPUTERS" to "Electronics",
        "Kotsovolos" to "Electronics", "KOTSOVOLOS" to "Electronics", "DIXONS" to "Electronics", "SOUTH EAST EUROPE" to "Electronics",
        "Media Markt" to "Electronics", "MEDIA MARKT" to "Electronics",
        "Germanos" to "Electronics", "GERMANOS" to "Electronics", "COSMOTE E-VALUE" to "Electronics",
        "Apple" to "Electronics", "APPLE STORE" to "Electronics", "APPLE.COM" to "Electronics",
        "Samsung" to "Electronics", "SAMSUNG" to "Electronics", "Xiaomi" to "Electronics", "MI STORE" to "Electronics", "Huawei" to "Electronics",
        "Sony" to "Electronics", "SONY CENTER" to "Electronics", "Nintendo" to "Electronics",
        "Amazon" to "Electronics", "AMAZON" to "Electronics", "AMZN" to "Electronics", "AMAZON.DE" to "Electronics", "AMAZON.CO.UK" to "Electronics",
        "Ebay" to "Electronics", "EBAY" to "Electronics", "AliExpress" to "Electronics", "ALIEXPRESS" to "Electronics",
        "E-shop" to "Electronics", "E-SHOP" to "Electronics", "E-SHOP.GR" to "Electronics",
        "You.gr" to "Electronics", "YOU.GR" to "Electronics", "Info Quest" to "Electronics",
        "BestPrice" to "Electronics", "BESTPRICE" to "Electronics",
        "Kaizer" to "Electronics", "KAIZER" to "Electronics", "Phone" to "Electronics", "Mobile" to "Electronics", "Service Mobile" to "Electronics",

        // --- SUBSCRIPTIONS (48+) ---
        "Netflix" to "Subscriptions", "NETFLIX" to "Subscriptions", "NETFLIX.COM" to "Subscriptions",
        "Spotify" to "Subscriptions", "SPOTIFY" to "Subscriptions",
        "Disney+" to "Subscriptions", "DISNEY PLUS" to "Subscriptions", "DISNEY+" to "Subscriptions",
        "HBO" to "Subscriptions", "HBO MAX" to "Subscriptions", "Hulu" to "Subscriptions", "Prime Video" to "Subscriptions", "Amazon Prime" to "Subscriptions",
        "Apple TV" to "Subscriptions", "APPLE TV" to "Subscriptions", "Apple Music" to "Subscriptions", "APPLE MUSIC" to "Subscriptions", "iTunes" to "Subscriptions",
        "Youtube" to "Subscriptions", "YOUTUBE" to "Subscriptions", "YOUTUBE PREMIUM" to "Subscriptions", "GOOGLE YOUTUBE" to "Subscriptions",
        "Deezer" to "Subscriptions", "DEEZER" to "Subscriptions", "Tidal" to "Subscriptions",
        "Cosmote TV" to "Subscriptions", "COSMOTE TV" to "Subscriptions", "Nova" to "Subscriptions", "NOVA" to "Subscriptions", "Eon" to "Subscriptions", "EON TV" to "Subscriptions",
        "Ertflix" to "Subscriptions", "ERTFLIX" to "Subscriptions", "Ant1+" to "Subscriptions", "ANT1 PLUS" to "Subscriptions",
        "Google One" to "Subscriptions", "GOOGLE ONE" to "Subscriptions", "GOOGLE STORAGE" to "Subscriptions",
        "iCloud" to "Subscriptions", "ICLOUD" to "Subscriptions", "APPLE ICLOUD" to "Subscriptions",
        "Dropbox" to "Subscriptions", "DROPBOX" to "Subscriptions", "OneDrive" to "Subscriptions",
        "Microsoft 365" to "Subscriptions", "MICROSOFT" to "Subscriptions", "MSFT" to "Subscriptions", "OFFICE 365" to "Subscriptions",
        "Adobe" to "Subscriptions", "ADOBE" to "Subscriptions", "CREATIVE CLOUD" to "Subscriptions",
        "PlayStation" to "Subscriptions", "PLAYSTATION" to "Subscriptions", "PSN" to "Subscriptions", "PS PLUS" to "Subscriptions", "SONY NETWORK" to "Subscriptions",
        "Xbox" to "Subscriptions", "XBOX" to "Subscriptions", "MICROSOFT XBOX" to "Subscriptions", "GAME PASS" to "Subscriptions",
        "Steam" to "Subscriptions", "STEAMGAMES" to "Subscriptions", "VALVE" to "Subscriptions",
        "Epic Games" to "Subscriptions", "EPIC GAMES" to "Subscriptions", "Blizzard" to "Subscriptions",
        "Twitch" to "Subscriptions", "TWITCH" to "Subscriptions", "Discord" to "Subscriptions", "DISCORD" to "Subscriptions", "NITRO" to "Subscriptions",
        "ChatGPT" to "Subscriptions", "OPENAI" to "Subscriptions", "Claude" to "Subscriptions", "ANTHROPIC" to "Subscriptions", "Midjourney" to "Subscriptions",
        "Patreon" to "Subscriptions", "PATREON" to "Subscriptions", "Substack" to "Subscriptions",
        "Duolingo" to "Subscriptions", "DUOLINGO" to "Subscriptions",
        "NordVPN" to "Subscriptions", "ExpressVPN" to "Subscriptions", "Surfshark" to "Subscriptions",

        // --- UTILITIES (20+) ---
        "DEI" to "Utilities", "ΔΕΗ" to "Utilities", "DIMOSIA EPICHEIRISI" to "Utilities",
        "EYDAP" to "Utilities", "ΕΥΔΑΠ" to "Utilities", "NERO" to "Utilities",
        "EYATH" to "Utilities", "ΕΥΑΘ" to "Utilities",
        "Heron" to "Utilities", "IRON" to "Utilities", "ΗΡΩΝ" to "Utilities", "HERON ENERGY" to "Utilities",
        "Protergia" to "Utilities", "PROTERGIA" to "Utilities", "MYTILINEOS" to "Utilities",
        "Elpedison" to "Utilities", "ELPEDISON" to "Utilities",
        "Volton" to "Utilities", "VOLTON" to "Utilities",
        "NRG" to "Utilities", "NRG TRADING" to "Utilities",
        "Zenith" to "Utilities", "ZENITH" to "Utilities", "AERIO" to "Utilities", "EPA" to "Utilities",
        "Watt+Volt" to "Utilities", "WATT AND VOLT" to "Utilities", "WATT&VOLT" to "Utilities",
        "Fysiko Aerio" to "Utilities", "ΦΥΣΙΚΟ ΑΕΡΙΟ" to "Utilities",
        "Cosmote" to "Utilities", "COSMOTE" to "Utilities", "OTE" to "Utilities", "ΟΤΕ" to "Utilities",
        "Vodafone" to "Utilities", "VODAFONE" to "Utilities", "VODAFONE PANAFON" to "Utilities",
        "Wind" to "Utilities", "WIND" to "Utilities", "NOVA TELECOMB" to "Utilities", "NOVA" to "Utilities",
        "Inalan" to "Utilities", "INALAN" to "Utilities", "Cyta" to "Utilities",
        "Koinoxrista" to "Utilities", "Κοινόχρηστα" to "Utilities", "Polytechneio" to "Utilities",

        // --- HEALTH (24+) ---
        "Pharmacy" to "Health", "PHARMACY" to "Health", "Φαρμακείο" to "Health", "Farmakeio" to "Health", "DRUGSTORE" to "Health",
        "Doctor" to "Health", "DOCTOR" to "Health", "Γιατρός" to "Health", "Iatros" to "Health", "Clinic" to "Health", "Κλινική" to "Health",
        "Dentist" to "Health", "Οδοντίατρος" to "Health", "Odontiatros" to "Health",
        "Hospital" to "Health", "Nosokomeio" to "Health", "Νοσοκομείο" to "Health",
        "Iatropolis" to "Health", "IATROPOLIS" to "Health", "Bioiatriki" to "Health", "BIOIATRIKI" to "Health",
        "Affidea" to "Health", "AFFIDEA" to "Health", "Euromedica" to "Health", "EUROMEDICA" to "Health",
        "Hygeia" to "Health", "HYGEIA" to "Health", "MITERA" to "Health", "IASO" to "Health", "METROPOLITAN" to "Health",
        "Errikos Ntynan" to "Health", "MEDITERRANEO" to "Health",
        "Doctoranytime" to "Health", "DOCTORANYTIME" to "Health",
        "Gym" to "Fitness", "GYM" to "Fitness", "Gymnastirio" to "Fitness", "Γυμναστήριο" to "Fitness",
        "Yava" to "Fitness", "YAVA" to "Fitness", "Planet Fitness" to "Fitness", "Alterlife" to "Fitness", "Holmes Place" to "Fitness",

        // --- PETS ---
        "Pet City" to "Pets", "PET CITY" to "Pets", "PETCITY" to "Pets",
        "Pet Shop" to "Pets", "PET SHOP" to "Pets", "Pet" to "Pets",
        "Ktiniatros" to "Pets", "Κτηνίατρος" to "Pets", "Vet" to "Pets", "Animal" to "Pets",
        "Zooplus" to "Pets", "ZOOPLUS" to "Pets",

        // --- EDUCATION ---
        "Udemy" to "Education", "Coursera" to "Education", "E-learning" to "Education",
        "Book" to "Education", "Bookstore" to "Education", "Vivlio" to "Education", "Βιβλιοπωλείο" to "Education",
        "Ianos" to "Education", "IANOS" to "Education", "Politeia" to "Education", "Evripidis" to "Education", "Patakis" to "Education",
        "Didaktra" to "Education", "Tuition" to "Education", "School" to "Education", "University" to "Education", "College" to "Education",
        
        // --- BANKING/FEES ---
        "Revolut" to "Banking", "REVOLUT" to "Banking", "Alpha Bank" to "Banking", "Eurobank" to "Banking", "Piraeus" to "Banking", "Ethniki" to "Banking",
        "PayPal" to "Banking", "PAYPAL" to "Banking", "Curve" to "Banking", "Wise" to "Banking"
    )
    
    // Additional mapping for normalized uppercase keys to capture variations
    fun getExpandedMap(): Map<String, String> {
        return merchantToCategoryMap.mapKeys { it.key.uppercase() }
    }
}
