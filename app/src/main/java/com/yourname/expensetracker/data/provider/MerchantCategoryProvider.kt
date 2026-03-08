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
        Category(name = "Banking", icon = "🏦", color = "#37474F", isDefault = true), // Fees etc
        Category(name = "Legal & Gov", icon = "⚖️", color = "#9E9E9E", isDefault = true),
        Category(name = "Uncategorized", icon = "❓", color = "#BDBDBD", isDefault = true)
    )

    // Map of Merchant Name (or keyword) -> Category Name
    val merchantToCategoryMap = mapOf(
        // ═══════════════════════════════════════════════════════════════
        // 🛒 GROCERIES - Supermarkets, Bakeries, Butchers
        // ═══════════════════════════════════════════════════════════════
        
        // AB Vassilopoulos (all variations)
        "AB Βασιλόπουλος" to "Groceries", "AB Vasilopoulos" to "Groceries", 
        "AB BASILOPOULOS" to "Groceries", "AB SHOP" to "Groceries", 
        "A.B." to "Groceries", "ALFA BETA" to "Groceries",
        "AB FOOD MARKET" to "Groceries", "DELHAIZE" to "Groceries",
        "ΑΛΦΑ ΒΗΤΑ" to "Groceries", "TROFO" to "Groceries",
        
        // Sklavenitis
        "Σκλαβενίτης" to "Groceries", "Sklavenitis" to "Groceries", 
        "SKLAVENITIS" to "Groceries", "ELLINIKES YPERAGORES" to "Groceries",
        "ΣΚΛΑΒΕΝΙΤΗΣ" to "Groceries", "I & S SKLAVENITIS" to "Groceries",
        
        // Lidl
        "Lidl" to "Groceries", "LIDL HELLAS" to "Groceries", 
        "LIDL ELLAS" to "Groceries", "LIDL STIFTUNG" to "Groceries",
        
        // My Market
        "My Market" to "Groceries", "MY MARKET" to "Groceries", 
        "MYMARKET" to "Groceries", "METRO AEBE" to "Groceries",
        "METRO MY MARKET" to "Groceries",
        
        // Masoutis
        "Μασούτης" to "Groceries", "Masoutis" to "Groceries", 
        "MASOUTIS" to "Groceries", "MASOYTHS" to "Groceries",
        "DIAMANTIS MASOUTIS" to "Groceries",
        
        // Other Greek Supermarkets
        "Γαλαξίας" to "Groceries", "Galaxias" to "Groceries", 
        "GALAXIAS" to "Groceries", "PENTE SA" to "Groceries",
        "Κρητικός" to "Groceries", "Kritikos" to "Groceries", 
        "KRITIKOS" to "Groceries", "ANEDIK KRITIKOS" to "Groceries",
        "Bazaar" to "Groceries", "BAZAAR" to "Groceries", 
        "BAZAAR SM" to "Groceries",
        "Market In" to "Groceries", "MARKET IN" to "Groceries", 
        "MARKETIN" to "Groceries", "VEROUKAS" to "Groceries",
        "The Mart" to "Groceries", "THE MART" to "Groceries", 
        "THEMART" to "Groceries", "MAKRO" to "Groceries",
        "METRO CASH" to "Groceries",
        
        // European Chains
        "Aldi" to "Groceries", "ALDI SUD" to "Groceries", 
        "ALDI NORD" to "Groceries",
        "Kaufland" to "Groceries", "KAUFLAND" to "Groceries",
        "Carrefour" to "Groceries", "CARREFOUR" to "Groceries",
        "CARREFOUR EXPRESS" to "Groceries", "CARREFOUR CITY" to "Groceries",
        "Penny Market" to "Groceries", "PENNY" to "Groceries",
        "Tesco" to "Groceries", "TESCO" to "Groceries",
        "Sainsbury" to "Groceries", "SAINSBURYS" to "Groceries",
        "Waitrose" to "Groceries", "WAITROSE" to "Groceries",
        "Marks Spencer Food" to "Groceries", "M&S FOOD" to "Groceries",
        "Migros" to "Groceries", "MIGROS" to "Groceries",
        "Coop" to "Groceries", "COOP" to "Groceries",
        "Spar" to "Groceries", "SPAR" to "Groceries",
        "Rewe" to "Groceries", "REWE" to "Groceries",
        "Edeka" to "Groceries", "EDEKA" to "Groceries",
        
        // Regional Greek Supermarkets
        "PLUS Super Discount" to "Groceries", "PLUS SUPERMARKET" to "Groceries",
        "Χαλκιαδάκης" to "Groceries", "Chalkiadakis" to "Groceries", 
        "HALKIADAKIS" to "Groceries", "XALKIADAKIS" to "Groceries",
        "OK! Anytime" to "Groceries", "OK MARKET" to "Groceries", 
        "OK ANYTIME MARKETS" to "Groceries",
        "Σάββας" to "Groceries", "Savvas" to "Groceries", 
        "SAVVAS CASH" to "Groceries",
        "3Α" to "Groceries", "3A" to "Groceries", "ΤΡΙΑ ΑΛΦΑ" to "Groceries",
        "Discount Markt" to "Groceries", "DISCOUNT MARKT" to "Groceries",
        "Arvanitidis" to "Groceries", "ΑΡΒΑΝΙΤΙΔΗΣ" to "Groceries",
        "Atlantic" to "Groceries", "ATLANTIC" to "Groceries",
        "Synka" to "Groceries", "SYNKA" to "Groceries", "ΣΥΝΚΑ" to "Groceries",
        "Xynos" to "Groceries", "ΞΥΝΟΣ" to "Groceries",
        "Ena Cash" to "Groceries", "ENA CASH CARRY" to "Groceries",
        "Smile Markets" to "Groceries", "SMILE MARKETS" to "Groceries",
        "Karamolegos" to "Groceries", "ΚΑΡΑΜΟΛΕΓΚΟΣ" to "Groceries",
        
        // Bio/Organic Stores
        "Bio Agora" to "Groceries", "BIO AGORA" to "Groceries",
        "Ελαία" to "Groceries", "Elaia" to "Groceries",
        "Avocado" to "Groceries", "AVOCADO STORES" to "Groceries",
        "Green Family" to "Groceries", "GREEN FAMILY" to "Groceries",
        "Organic" to "Groceries", "ORGANIC SHOP" to "Groceries",
        "Bio" to "Groceries", "BIOLOGIKA" to "Groceries",
        "Herbs" to "Groceries", "HERBS STORE" to "Groceries",
        
        // Convenience & Local
        "Mini Market" to "Groceries", "Minimarket" to "Groceries", 
        "Μινι Μαρκετ" to "Groceries",
        "Kiosk" to "Groceries", "Periptero" to "Groceries", 
        "Περίπτερο" to "Groceries", "PERIPTERO" to "Groceries",
        "Psilika" to "Groceries", "Ψιλικα" to "Groceries",
        "Pantopoleio" to "Groceries", "Παντοπωλείο" to "Groceries",
        "Grocery" to "Groceries", "GROCERY STORE" to "Groceries",
        "Bakaliko" to "Groceries", "Μπακάλικο" to "Groceries",
        "Express Market" to "Groceries", "EXPRESS" to "Groceries",
        
        // Bakeries
        "Bakery" to "Groceries", "Baker" to "Groceries", 
        "Φούρνος" to "Groceries", "Fournos" to "Groceries", 
        "Artopoiio" to "Groceries", "Αρτοποιείο" to "Groceries",
        "ARTOS" to "Groceries", "Bread" to "Groceries",
        "Veneti" to "Groceries", "ΒΕΝΕΤΗ" to "Groceries", "VENETIS" to "Groceries",
        "Terkenlis" to "Groceries", "ΤΕΡΚΕΝΛΗΣ" to "Groceries",
        "Asimakopoulou" to "Groceries", "ΑΣΗΜΑΚΟΠΟΥΛΟΥ" to "Groceries",
        "Konstantinidis" to "Groceries", "ΚΩΝΣΤΑΝΤΙΝΙΔΗΣ" to "Groceries",
        "Chatzis" to "Groceries", "ΧΑΤΖΗΣ" to "Groceries",
        "Blé" to "Groceries", "BLE" to "Groceries",
        "Pain Quotidien" to "Groceries", "PAIN QUOTIDIEN" to "Groceries",
        
        // Butcher/Meat
        "Butcher" to "Groceries", "Kreopoleio" to "Groceries", 
        "Κρεοπωλείο" to "Groceries", "KREAS" to "Groceries",
        "Meat" to "Groceries", "MEAT SHOP" to "Groceries",
        "Chiros" to "Groceries", "Pork Shop" to "Groceries",
        "Salami" to "Groceries", "Allantika" to "Groceries",
        "ΑΛΛΑΝΤΙΚΑ" to "Groceries",
        
        // Fish
        "Fish Shop" to "Groceries", "Ixthiopolio" to "Groceries", 
        "Ιχθυοπωλείο" to "Groceries", "ΨΑΡΑΓΟΡΑ" to "Groceries",
        "Fish Market" to "Groceries", "Psaradiko" to "Groceries",
        "Seafood" to "Groceries",
        
        // Produce
        "Greengrocer" to "Groceries", "Manaviko" to "Groceries", 
        "Μανάβικο" to "Groceries", "ΛΑΪΚΗ" to "Groceries",
        "Laiki Agora" to "Groceries", "Farmers Market" to "Groceries",
        "ΑΓΟΡΑ" to "Groceries", "Varvakios" to "Groceries",
        "ΒΑΡΒΑΚΕΙΟΣ" to "Groceries",
        
        // Specialty
        "Cheese Shop" to "Groceries", "Tyrokomeio" to "Groceries",
        "Delicatessen" to "Groceries", "Deli" to "Groceries",
        "Kafekopeio" to "Groceries", "ΚΑΦΕΚΟΠΤΕΙΟ" to "Groceries",
        "Wine Shop" to "Groceries", "Kava" to "Groceries", "ΚΑΒΑ" to "Groceries",
        "Cellar" to "Groceries",

        // ═══════════════════════════════════════════════════════════════
        // 🚗 TRANSPORT - FUEL & MOBILITY
        // ═══════════════════════════════════════════════════════════════
        
        // Fuel Stations
        "Shell" to "Transport", "SHELL HELLAS" to "Transport", 
        "SEHL" to "Transport", "CORAL AE" to "Transport",
        "BP" to "Transport", "BP HELLAS" to "Transport", 
        "BRITISH PETROLEUM" to "Transport",
        "EKO" to "Transport", "EKO ABEE" to "Transport", 
        "EKO KALYPSO" to "Transport", "HELLENIC PETROLEUM" to "Transport",
        "ELPE" to "Transport", "ΕΛΠΕ" to "Transport",
        "Aegean" to "Transport", "Aegean Oil" to "Transport", 
        "AEGEAN OIL" to "Transport",
        "Avin" to "Transport", "AVIN OIL" to "Transport", 
        "MOTOR OIL" to "Transport",
        "Ελίν" to "Transport", "Elin" to "Transport", 
        "ELIN OIL" to "Transport", "ELINOIL" to "Transport",
        "Revoil" to "Transport", "REVOIL" to "Transport", 
        "Jet Oil" to "Transport", "JETOIL" to "Transport",
        "Cyclon" to "Transport", "CYCLON" to "Transport", 
        "Coral Gas" to "Transport", "CORAL GAS" to "Transport",
        "Eteka" to "Transport", "ETEKA" to "Transport",
        "Mamidoil" to "Transport", "MAMIDOIL" to "Transport",
        "Silk Oil" to "Transport", "SILK OIL" to "Transport",
        "Naoumidis" to "Transport", "ΝΑΟΥΜΙΔΗΣ" to "Transport",
        
        // International Fuel
        "Total" to "Transport", "TOTAL ENERGIES" to "Transport",
        "Esso" to "Transport", "ESSO" to "Transport",
        "Texaco" to "Transport", "TEXACO" to "Transport",
        "Q8" to "Transport", "KUWAIT PETROLEUM" to "Transport",
        "Cepsa" to "Transport", "CEPSA" to "Transport",
        "Repsol" to "Transport", "REPSOL" to "Transport",
        "OMV" to "Transport",
        "MOL" to "Transport",
        
        // Generic Fuel
        "Gas Station" to "Transport", "Fuel Station" to "Transport", 
        "Πρατήριο" to "Transport", "Benzinadiko" to "Transport",
        "Βενζινάδικο" to "Transport", "PRATIRIO" to "Transport",
        "Petrol" to "Transport", "PETROL" to "Transport",
        "Diesel Fuel" to "Transport", "DIESEL FUEL" to "Transport",
        "LPG" to "Transport", "AUTOGAS" to "Transport",
        "Charging Station" to "Transport", "EV CHARGE" to "Transport",
        
        // Ride Hailing
        "Uber" to "Transport", "UBER TRIP" to "Transport", 
        "UBER BV" to "Transport", "UBER PAYMENTS" to "Transport",
        "Beat" to "Transport", "BEAT APP" to "Transport", 
        "BEAT RIDE" to "Transport",
        "FREE NOW" to "Transport", "NOOW" to "Transport", 
        "FREENOW" to "Transport", "MYTAXI" to "Transport",
        "Bolt" to "Transport", "BOLT EU" to "Transport",
        "BOLT OPERATIONS" to "Transport",
        "Lyft" to "Transport", "LYFT" to "Transport",
        "Didi" to "Transport", "DIDI" to "Transport",
        
        // Taxis
        "Taxi" to "Transport", "Ταξί" to "Transport", 
        "Cab" to "Transport", "ΤΑΞΙ" to "Transport",
        "Taxiplon" to "Transport", "TAXIPLON" to "Transport",
        "Radio Taxi" to "Transport", "RADIO TAXI" to "Transport",
        "TAXI ATHINON" to "Transport", "ΡΑΔΙΟΤΑΞΙ" to "Transport",
        
        // Public Transport Athens
        "OASA" to "Transport", "ΟΑΣΑ" to "Transport", 
        "ATH.ENA TICKET" to "Transport", "ATHENA CARD" to "Transport",
        "STASY" to "Transport", "ΣΤΑΣΥ" to "Transport", 
        "URBAN RAIL" to "Transport",
        "Metro Athens" to "Transport", "ΜΕΤΡΟ" to "Transport",
        "ATTIKO METRO" to "Transport",
        "Tram" to "Transport", "Τραμ" to "Transport", "ΤΡΑΜ" to "Transport",
        "ISAP" to "Transport", "ΗΣΑΠ" to "Transport", 
        "Ηλεκτρικός" to "Transport", "HLEKTRIKOS" to "Transport",
        "Proastiakos" to "Transport", "ΠΡΟΑΣΤΙΑΚΟΣ" to "Transport",
        
        // Public Transport Thessaloniki
        "OASTH" to "Transport", "ΟΑΣΘ" to "Transport",
        "THESSALONIKI METRO" to "Transport",
        
        // Buses
        "KTEL" to "Transport", "ΚΤΕΛ" to "Transport", 
        "KTEL ATTIKIS" to "Transport", "KTEL MACEDONIA" to "Transport",
        "KTEL PELOPONNISOU" to "Transport", "KTEL KRITIS" to "Transport",
        "KTEL THESSALONIKH" to "Transport", "KTEL LARISAS" to "Transport",
        "KTEL PATRON" to "Transport", "KTEL VOLOU" to "Transport",
        "KTEL EVIA" to "Transport", "KTEL IRAKLEIOU" to "Transport",
        "KTEL CHANION" to "Transport", "KTEL RODOU" to "Transport",
        "Flixbus" to "Transport", "FLIXBUS" to "Transport",
        
        // Trains
        "Hellenic Train" to "Transport", "TRAINOSE" to "Transport", 
        "ΤΡΕΝΟΣΕ" to "Transport", "OSE" to "Transport", "ΟΣΕ" to "Transport",
        "HELLENICTRAIN" to "Transport", "ΕΛΛΗΝΙΚΟΣ" to "Transport",
        "Eurostar" to "Transport", "EUROSTAR" to "Transport",
        "Thalys" to "Transport", "TGV" to "Transport",
        "Deutsche Bahn" to "Transport", "DB" to "Transport",
        "OBB" to "Transport", "SNCF" to "Transport",
        "Trenitalia" to "Transport", "ITALO" to "Transport",
        
        // Parking
        "Parking" to "Transport", "Parkin" to "Transport", 
        "Parkingmycity" to "Transport", "Cityzen" to "Transport", 
        "Polis Park" to "Transport",
        "Athens Parking" to "Transport", "APCOA" to "Transport",
        "Q-Park" to "Transport", "INTERPARKING" to "Transport",
        "SABA" to "Transport", "ΣΤΑΘΜΕΥΣΗ" to "Transport",
        "Valet" to "Transport", "VALET PARKING" to "Transport",
        
        // Tolls
        "E-pass" to "Transport", "EPASS" to "Transport", 
        "Attiki Odos" to "Transport", "ATTIKI ODOS" to "Transport",
        "ATTIKES DIADROMES" to "Transport",
        "Nea Odos" to "Transport", "NEA ODOS" to "Transport",
        "Olympia Odos" to "Transport", "OLYMPIA ODOS" to "Transport",
        "Egnatia Odos" to "Transport", "EGNATIA ODOS" to "Transport",
        "Moreas" to "Transport", "MOREAS" to "Transport", 
        "Kentriki Odos" to "Transport", "KENTRIKI ODOS" to "Transport",
        "Gefyra" to "Transport", "GEFYRA" to "Transport", 
        "Rio Antirio" to "Transport", "RIO ANTIRIO" to "Transport",
        "DIODIA" to "Transport", "Διόδια" to "Transport",
        "Aktor" to "Transport", "AKTOR CONCESSIONS" to "Transport",
        "AUTOKINITODROMO" to "Transport",
        
        // Micromobility
        "Lime" to "Transport", "LIME SCOOTER" to "Transport",
        "Tier" to "Transport", "TIER MOBILITY" to "Transport",
        "Bird" to "Transport", "BIRD SCOOTER" to "Transport",
        "Voi" to "Transport", "VOI" to "Transport",
        "Dott" to "Transport", "DOTT" to "Transport",
        "Spin" to "Transport", "SPIN" to "Transport",
        "Bike" to "Transport", "BIKE RENTAL" to "Transport",
        "E-scooter" to "Transport", "ESCOOTER" to "Transport",
        
        // ═══════════════════════════════════════════════════════════════
        // ✈️ TRAVEL - Airlines, Hotels, Ferries
        // ═══════════════════════════════════════════════════════════════
        
        // Greek Airlines
        "Aegean Airlines" to "Travel", "AEGEAN AIR" to "Travel", 
        "AEGEAN AIRLINES" to "Travel", "A3" to "Travel",
        "Olympic Air" to "Travel", "OLYMPIC AIR" to "Travel", 
        "OLYMPIC AIRLINES" to "Travel",
        "Sky Express" to "Travel", "SKY EXPRESS" to "Travel",
        "SKYEXPRESS" to "Travel",
        
        // European Low Cost
        "Ryanair" to "Travel", "RYANAIR" to "Travel", 
        "RYANAIR DAC" to "Travel", "FR" to "Travel",
        "EasyJet" to "Travel", "EASYJET" to "Travel", 
        "U2" to "Travel",
        "Wizz Air" to "Travel", "WIZZAIR" to "Travel", 
        "W6" to "Travel",
        "Volotea" to "Travel", "VOLOTEA" to "Travel",
        "Vueling" to "Travel", "VUELING" to "Travel",
        "Transavia" to "Travel", "TRANSAVIA" to "Travel",
        "Norwegian" to "Travel", "NORWEGIAN AIR" to "Travel",
        "Eurowings" to "Travel", "EUROWINGS" to "Travel",
        "Lauda" to "Travel", "LAUDA EUROPE" to "Travel",
        "Buzz" to "Travel", "BUZZ POLAND" to "Travel",
        "Malta Air" to "Travel", "MALTA AIR" to "Travel",
        
        // Major Airlines
        "Lufthansa" to "Travel", "LUFTHANSA" to "Travel", "LH" to "Travel",
        "Swiss Air" to "Travel", "SWISS" to "Travel", "LX" to "Travel",
        "Austrian" to "Travel", "AUSTRIAN AIRLINES" to "Travel",
        "British Airways" to "Travel", "BRITISH AIRWAYS" to "Travel", "BA" to "Travel",
        "Air France" to "Travel", "AIRFRANCE" to "Travel", "AF" to "Travel",
        "KLM" to "Travel", "KLM ROYAL" to "Travel",
        "Iberia" to "Travel", "IBERIA" to "Travel",
        "TAP" to "Travel", "TAP PORTUGAL" to "Travel",
        "Alitalia" to "Travel", "ITA AIRWAYS" to "Travel",
        "SAS" to "Travel", "SCANDINAVIAN" to "Travel",
        "Finnair" to "Travel", "FINNAIR" to "Travel",
        "LOT" to "Travel", "LOT POLISH" to "Travel",
        "Czech Airlines" to "Travel", "CSA" to "Travel",
        "Croatia Airlines" to "Travel", "CROATIA AIR" to "Travel",
        "Turkish Airlines" to "Travel", "TURKISH" to "Travel", "TK" to "Travel",
        "Emirates" to "Travel", "EMIRATES" to "Travel", "EK" to "Travel",
        "Qatar Airways" to "Travel", "QATAR" to "Travel", "QR" to "Travel",
        "Etihad" to "Travel", "ETIHAD" to "Travel",
        "Singapore Airlines" to "Travel", "SINGAPORE AIR" to "Travel",
        "Cathay Pacific" to "Travel", "CATHAY" to "Travel",
        "United" to "Travel", "UNITED AIRLINES" to "Travel",
        "Delta" to "Travel", "DELTA AIRLINES" to "Travel",
        "American Airlines" to "Travel", "AMERICAN AIR" to "Travel",
        
        // Ferries - Greece
        "Blue Star" to "Travel", "BLUE STAR FERRIES" to "Travel",
        "BLUESTAR" to "Travel", "ATTICA GROUP" to "Travel",
        "ANEK" to "Travel", "ANEK LINES" to "Travel",
        "Minoan" to "Travel", "MINOAN LINES" to "Travel",
        "Hellenic Seaways" to "Travel", "HSW" to "Travel",
        "HELLENIC SEAWAYS" to "Travel",
        "Seajets" to "Travel", "SEAJETS" to "Travel", "SEA JETS" to "Travel",
        "Golden Star" to "Travel", "GOLDEN STAR FERRIES" to "Travel",
        "Fast Ferries" to "Travel", "FAST FERRIES" to "Travel",
        "Superfast" to "Travel", "SUPERFAST FERRIES" to "Travel",
        "Aegean Speed Lines" to "Travel", "AEGEAN SPEED" to "Travel",
        "Zante Ferries" to "Travel", "ZANTE FERRIES" to "Travel",
        "Levante Ferries" to "Travel", "LEVANTE" to "Travel",
        "Saronic Ferries" to "Travel", "SARONIC" to "Travel",
        "Anes Ferries" to "Travel", "ANES" to "Travel",
        "NEL Lines" to "Travel", "NEL" to "Travel",
        "Sky Island Ferries" to "Travel", "SKYISLAND" to "Travel",
        "Dodekanisos Seaways" to "Travel", "DODEKANISOS" to "Travel",
        "Small Cyclades" to "Travel", "EXPRESS SKOPELITIS" to "Travel",
        "Triton" to "Travel", "TRITON FERRIES" to "Travel",
        "Ferry" to "Travel", "FERRY TICKET" to "Travel",
        "ΠΛΟΙΟ" to "Travel", "ΑΚΤΟΠΛΟΙΚΑ" to "Travel",
        
        // International Ferries
        "Grimaldi" to "Travel", "GRIMALDI LINES" to "Travel",
        "Grandi Navi" to "Travel", "GNV" to "Travel",
        "Moby" to "Travel", "MOBY LINES" to "Travel",
        "Tirrenia" to "Travel", "TIRRENIA" to "Travel",
        "Jadrolinija" to "Travel", "JADROLINIJA" to "Travel",
        "Corsica Ferries" to "Travel", "CORSICA" to "Travel",
        "Brittany Ferries" to "Travel", "BRITTANY" to "Travel",
        "P&O Ferries" to "Travel", "P&O" to "Travel",
        "DFDS" to "Travel", "DFDS SEAWAYS" to "Travel",
        "Stena Line" to "Travel", "STENA" to "Travel",
        "Viking Line" to "Travel", "VIKING LINE" to "Travel",
        "Tallink" to "Travel", "TALLINK SILJA" to "Travel",
        
        // Car Rental
        "Hertz" to "Travel", "HERTZ" to "Travel", "HERTZ HELLAS" to "Travel",
        "Avis" to "Travel", "AVIS" to "Travel", "AVIS RENT" to "Travel",
        "Europcar" to "Travel", "EUROPCAR" to "Travel",
        "Enterprise" to "Travel", "ENTERPRISE" to "Travel",
        "Budget" to "Travel", "BUDGET" to "Travel",
        "Sixt" to "Travel", "SIXT" to "Travel", "SIXT RENT" to "Travel",
        "National" to "Travel", "NATIONAL CAR" to "Travel",
        "Alamo" to "Travel", "ALAMO" to "Travel",
        "Thrifty" to "Travel", "THRIFTY" to "Travel",
        "Dollar" to "Travel", "DOLLAR RENT" to "Travel",
        "Green Motion" to "Travel", "GREEN MOTION" to "Travel",
        "Goldcar" to "Travel", "GOLDCAR" to "Travel",
        "Firefly" to "Travel", "FIREFLY CAR" to "Travel",
        "Maggiore" to "Travel", "MAGGIORE RENT" to "Travel",
        "Autohellas" to "Travel", "AUTOHELLAS" to "Travel",
        "Avance" to "Travel", "AVANCE RENT" to "Travel",
        "Car Rental" to "Travel", "RENT A CAR" to "Travel",
        "ΕΝΟΙΚΙΑΣΗ" to "Travel",
        
        // Hotels & Accommodation
        "Booking.com" to "Travel", "BOOKING" to "Travel", 
        "BOOKING.COM" to "Travel", "BOOKINGCOM" to "Travel",
        "Airbnb" to "Travel", "AIRBNB" to "Travel", 
        "AIR BNB" to "Travel",
        "Hotels.com" to "Travel", "HOTELS.COM" to "Travel",
        "Expedia" to "Travel", "EXPEDIA" to "Travel",
        "Trivago" to "Travel", "TRIVAGO" to "Travel",
        "Agoda" to "Travel", "AGODA" to "Travel",
        "Trip.com" to "Travel", "TRIP.COM" to "Travel", 
        "CTRIP" to "Travel",
        "Hostelworld" to "Travel", "HOSTELWORLD" to "Travel",
        "Vrbo" to "Travel", "VRBO" to "Travel", 
        "HOMEAWAY" to "Travel",
        "TripAdvisor" to "Travel", "TRIPADVISOR" to "Travel",
        "Kayak Travel" to "Travel", "KAYAK" to "Travel",
        "Skyscanner" to "Travel", "SKYSCANNER" to "Travel",
        "Google Flights" to "Travel", "GOOGLE FLIGHTS" to "Travel",
        "Momondo" to "Travel", "MOMONDO" to "Travel",
        "Kiwi" to "Travel", "KIWI.COM" to "Travel",
        "Lastminute" to "Travel", "LASTMINUTE" to "Travel",
        "Opodo" to "Travel", "OPODO" to "Travel",
        "eDreams" to "Travel", "EDREAMS" to "Travel",
        "Gotogate" to "Travel", "GOTOGATE" to "Travel",
        "Hotel" to "Travel", "HOTEL" to "Travel", 
        "ΞΕΝΟΔΟΧΕΙΟ" to "Travel", "Xenodocheio" to "Travel",
        "Hostel" to "Travel", "HOSTEL" to "Travel",
        "Resort" to "Travel", "RESORT" to "Travel",
        "Pension" to "Travel", "PENSION" to "Travel",
        "Motel" to "Travel", "MOTEL" to "Travel",
        
        // Greek Hotel Chains
        "Grecotel" to "Travel", "GRECOTEL" to "Travel",
        "Mitsis" to "Travel", "MITSIS HOTELS" to "Travel",
        "Aldemar" to "Travel", "ALDEMAR" to "Travel",
        "Porto Carras" to "Travel", "PORTO CARRAS" to "Travel",
        "Sani Resort" to "Travel", "SANI" to "Travel",
        "Ikos" to "Travel", "IKOS RESORTS" to "Travel",
        "Costa Navarino" to "Travel", "COSTA NAVARINO" to "Travel",
        "Divani" to "Travel", "DIVANI HOTELS" to "Travel",
        "Electra" to "Travel", "ELECTRA HOTELS" to "Travel",
        "Makedonia Palace" to "Travel", "MAKEDONIA PALACE" to "Travel",
        "Grande Bretagne" to "Travel", "GRANDE BRETAGNE" to "Travel",
        "King George" to "Travel", "KING GEORGE" to "Travel",
        "St George Lycabettus" to "Travel", "ST GEORGE" to "Travel",
        "Hilton Athens" to "Travel", "HILTON" to "Travel",
        "Marriott" to "Travel", "MARRIOTT" to "Travel",
        "Sofitel" to "Travel", "SOFITEL" to "Travel",
        "Intercontinental" to "Travel", "INTERCONTINENTAL" to "Travel",
        "Four Seasons" to "Travel", "FOUR SEASONS" to "Travel",
        "Radisson" to "Travel", "RADISSON BLU" to "Travel",
        "Wyndham" to "Travel", "WYNDHAM" to "Travel",
        "Novotel" to "Travel", "NOVOTEL" to "Travel",
        "Ibis" to "Travel", "IBIS" to "Travel",
        "Accor" to "Travel", "ACCOR" to "Travel",
        "Best Western" to "Travel", "BEST WESTERN" to "Travel",
        "Holiday Inn" to "Travel", "HOLIDAY INN" to "Travel",
        "Crowne Plaza" to "Travel", "CROWNE PLAZA" to "Travel",
        
        // Tour Operators
        "TUI" to "Travel", "TUI HELLAS" to "Travel",
        "Thomas Cook" to "Travel", "THOMAS COOK" to "Travel",
        "Mouzenidis" to "Travel", "MOUZENIDIS TRAVEL" to "Travel",
        "Zorpidis" to "Travel", "ZORPIDIS TRAVEL" to "Travel",
        "Amphitrion" to "Travel", "AMPHITRION" to "Travel",
        "Travelplanet24" to "Travel", "TRAVELPLANET" to "Travel",
        "Pamediakopes" to "Travel", "PAME DIAKOPES" to "Travel",
        "Discover Greece" to "Travel", "DISCOVER" to "Travel",
        "Aegean Holidays" to "Travel", "AEGEAN HOLIDAYS" to "Travel",
        "Travel Agency" to "Travel", "TRAVEL AGENCY" to "Travel",
        "ΤΑΞΙΔΙΩΤΙΚΟ" to "Travel", "Tour" to "Travel",
        
        // Activities & Experiences
        "GetYourGuide" to "Travel", "GETYOURGUIDE" to "Travel",
        "Viator" to "Travel", "VIATOR" to "Travel",
        "Klook" to "Travel", "KLOOK" to "Travel",
        "Musement" to "Travel", "MUSEMENT" to "Travel",
        "Tiqets" to "Travel", "TIQETS" to "Travel",
        "Civitatis" to "Travel", "CIVITATIS" to "Travel",
        "Headout" to "Travel", "HEADOUT" to "Travel",

        // ═══════════════════════════════════════════════════════════════
        // 🍽️ FOOD & RESTAURANTS
        // ═══════════════════════════════════════════════════════════════
        
        // Coffee Chains - Greek
        "Gregorys" to "Food", "GREGORYS" to "Food", 
        "GRIGORIS" to "Food", "Γρηγόρης" to "Food", 
        "MΙΚΡΟΓΕΥΜΑΤΑ" to "Food", "ΓΡΗΓΟΡΗΣ" to "Food",
        "Everest" to "Food", "EVEREST" to "Food",
        "Mikel" to "Food", "MIKEL" to "Food", 
        "MIKEL COFFEE" to "Food",
        "Coffee Island" to "Food", "COFFEE ISLAND" to "Food", 
        "KAFEKOPTEIO" to "Food",
        "Coffee Lab" to "Food", "COFFEE LAB" to "Food",
        "Flocafe" to "Food", "FLOCAFE" to "Food",
        "Coffee Berry" to "Food", "COFFEE BERRY" to "Food",
        "Bruno" to "Food", "BRUNO COFFEE" to "Food", 
        "Cultivos" to "Food", "CULTIVOS" to "Food",
        "Taf" to "Food", "TAF COFFEE" to "Food",
        "Holy Spirit" to "Food", "HOLY SPIRIT" to "Food",
        "Brew Lab" to "Food", "BREW LAB" to "Food",
        "Mokka" to "Food", "MOKKA COFFEE" to "Food",
        "The Underdog" to "Food", "UNDERDOG" to "Food",
        "Little Tree" to "Food", "LITTLE TREE" to "Food",
        "Seven Grams" to "Food", "SEVEN GRAMS" to "Food",
        "Mind the Cup" to "Food", "MIND THE CUP" to "Food",
        
        // Coffee Chains - International
        "Starbucks" to "Food", "STARBUCKS" to "Food", 
        "STARBUCKS COFFEE" to "Food",
        "Costa Coffee" to "Food", "COSTA COFFEE" to "Food",
        "McCafe" to "Food", "MCCAFE" to "Food",
        "Caffè Nero" to "Food", "CAFFE NERO" to "Food",
        "Pret" to "Food", "PRET A MANGER" to "Food",
        "Dunkin" to "Food", "DUNKIN DONUTS" to "Food",
        "Tim Hortons" to "Food", "TIM HORTONS" to "Food",
        "Gloria Jeans" to "Food", "GLORIA JEANS" to "Food",
        "Lavazza" to "Food", "LAVAZZA" to "Food",
        "Illy" to "Food", "ILLY CAFFE" to "Food",
        "Segafredo" to "Food", "SEGAFREDO" to "Food",
        
        // Fast Food - Global
        "McDonalds" to "Food", "MCDONALDS" to "Food", 
        "MCD" to "Food", "MC DONALDS" to "Food",
        "Burger King" to "Food", "BURGER KING" to "Food", "BK" to "Food",
        "KFC" to "Food", "KENTUCKY FRIED CHICKEN" to "Food",
        "Subway" to "Food", "SUBWAY" to "Food",
        "Pizza Hut" to "Food", "PIZZA HUT" to "Food",
        "Dominos" to "Food", "DOMINOS" to "Food", 
        "DOMINO'S" to "Food", "DOMINOS PIZZA" to "Food",
        "Papa Johns" to "Food", "PAPA JOHNS" to "Food",
        "Wendys" to "Food", "WENDYS" to "Food",
        "Taco Bell" to "Food", "TACO BELL" to "Food",
        "Chick-fil-A" to "Food", "CHICK FIL A" to "Food",
        "Five Guys" to "Food", "FIVE GUYS" to "Food",
        "Shake Shack" to "Food", "SHAKE SHACK" to "Food",
        "Popeyes" to "Food", "POPEYES" to "Food",
        "Chipotle" to "Food", "CHIPOTLE" to "Food",
        
        // Fast Food - Greek
        "Goodys" to "Food", "Goody's" to "Food", 
        "GOODYS" to "Food", "GOODY'S BURGER HOUSE" to "Food",
        "Pizza Fan" to "Food", "PIZZA FAN" to "Food",
        "Roma Pizza" to "Food", "ROMA PIZZA" to "Food",
        "L'Artigiano" to "Food", "LARTIGIANO" to "Food",
        "Palmie Bistro" to "Food", "PALMIE" to "Food",
        "Bufala Gelato" to "Food", "BUFALA" to "Food",
        
        // Casual Dining
        "TGI Fridays" to "Food", "TGI FRIDAYS" to "Food", 
        "FRIDAYS" to "Food",
        "Hard Rock" to "Food", "HARD ROCK CAFE" to "Food",
        "Wagamama" to "Food", "WAGAMAMA" to "Food", 
        "Noodle Bar" to "Food", "NOODLE BAR" to "Food",
        "Applebees" to "Food", "APPLEBEES" to "Food",
        "Chillis" to "Food", "CHILIS" to "Food",
        "Olive Garden" to "Food", "OLIVE GARDEN" to "Food",
        "PF Changs" to "Food", "PF CHANGS" to "Food",
        "Vapiano" to "Food", "VAPIANO" to "Food",
        "Bills" to "Food", "BILLS" to "Food",
        "The Breakfast Club" to "Food", "BREAKFAST CLUB" to "Food",
        
        // Greek Food Categories
        "Souvlaki" to "Food", "Σουβλάκι" to "Food", 
        "ΣΟΥΒΛΑΚΙ" to "Food", "SOUVLATZIDIKO" to "Food",
        "Psistaria" to "Food", "Ψησταριά" to "Food", 
        "ΨΗΣΤΑΡΙΑ" to "Food",
        "Grill" to "Food", "GRILL" to "Food", "ΣΧΑΡΑΣ" to "Food",
        "Gyros" to "Food", "ΓΥΡΟΣ" to "Food",
        "Kebab" to "Food", "KEBAB" to "Food",
        "Taverna" to "Food", "Ταβέρνα" to "Food", 
        "ΤΑΒΕΡΝΑ" to "Food",
        "Mezedopolio" to "Food", "Μεζεδοπωλείο" to "Food",
        "Ouzeri" to "Food", "Ουζερί" to "Food",
        "Tsipouradiko" to "Food", "Τσιπουράδικο" to "Food",
        "Psarotaverna" to "Food", "Ψαροταβέρνα" to "Food",
        "Estiatorio" to "Food", "Εστιατόριο" to "Food",
        "Restaurant" to "Food", "RESTAURANT" to "Food",
        
        // Cafes & Bars
        "Cafe" to "Food", "Καφέ" to "Food", "ΚΑΦΕ" to "Food",
        "Kafeneio" to "Food", "Καφενείο" to "Food",
        "Bar" to "Food", "BAR" to "Food", "ΜΠΑΡ" to "Food",
        "Club" to "Food", "CLUB" to "Food", "ΚΛΑΜΠ" to "Food",
        "Pub" to "Food", "PUB" to "Food",
        "Lounge" to "Food", "LOUNGE" to "Food",
        "Bistro" to "Food", "BISTRO" to "Food",
        "Brasserie" to "Food", "BRASSERIE" to "Food",
        "Cocktail" to "Food", "COCKTAIL BAR" to "Food",
        "Wine Bar" to "Food", "WINE BAR" to "Food",
        
        // Food Delivery Apps
        "efood" to "Food", "E-FOOD" to "Food", 
        "EFOOD" to "Food", "ONLINE DELIVERY" to "Food",
        "E FOOD SA" to "Food", "EFOOD GR" to "Food",
        "Wolt" to "Food", "WOLT" to "Food", 
        "WOLT GREECE" to "Food", "WOLT ENTERPRISES" to "Food",
        "Box" to "Food", "BOX DELIVERY" to "Food", 
        "BOX NOW" to "Food",
        "Uber Eats" to "Food", "UBER EATS" to "Food", 
        "UBEREATS" to "Food",
        "Glovo" to "Food", "GLOVO" to "Food", 
        "GLOVOAPP" to "Food",
        "Just Eat" to "Food", "JUST EAT" to "Food",
        "Deliveroo" to "Food", "DELIVEROO" to "Food",
        "Doordash" to "Food", "DOORDASH" to "Food",
        "Getir" to "Food", "GETIR" to "Food",
        "Gorillas" to "Food", "GORILLAS" to "Food",
        "Flink" to "Food", "FLINK" to "Food",
        "Delivery" to "Food", "DELIVERY" to "Food",
        "Take away" to "Food", "TAKEAWAY" to "Food",
        
        // Ice Cream & Desserts
        "Haagen Dazs" to "Food", "HAAGEN DAZS" to "Food",
        "Ben Jerry" to "Food", "BEN JERRYS" to "Food",
        "Baskin Robbins" to "Food", "BASKIN ROBBINS" to "Food",
        "Gelato" to "Food", "GELATO" to "Food",
        "Pagoto" to "Food", "Παγωτό" to "Food",
        "Dodoni" to "Food", "ΔΩΔΩΝΗ" to "Food",
        "Kayak Ice Cream" to "Food", "KAYAK ICECREAM" to "Food",
        "Cremeria" to "Food", "CREMERIA" to "Food",
        "Patisserie" to "Food", "PATISSERIE" to "Food",
        "Zacharoplasteio" to "Food", "Ζαχαροπλαστείο" to "Food",
        "Sweets" to "Food", "ΓΛΥΚΑ" to "Food",
        "Crepe" to "Food", "CREPE" to "Food",
        "Waffle" to "Food", "WAFFLE" to "Food",
        "Churros" to "Food", "CHURROS" to "Food",
        "Donuts" to "Food", "DONUT" to "Food",

        // ═══════════════════════════════════════════════════════════════
        // 🛍️ SHOPPING
        // ═══════════════════════════════════════════════════════════════
        
        // Inditex Group (Zara parent)
        "Zara" to "Shopping", "ZARA" to "Shopping", 
        "ZARA HELLAS" to "Shopping", "ITX HELLAS" to "Shopping",
        "Pull&Bear" to "Shopping", "PULL AND BEAR" to "Shopping", 
        "PULL&BEAR" to "Shopping",
        "Bershka" to "Shopping", "BERSHKA" to "Shopping", 
        "Stradivarius" to "Shopping", "STRADIVARIUS" to "Shopping",
        "Massimo Dutti" to "Shopping", "MASSIMO DUTTI" to "Shopping",
        "Oysho" to "Shopping", "OYSHO" to "Shopping",
        "Zara Home" to "Shopping", "ZARA HOME" to "Shopping",
        "Uterque" to "Shopping", "UTERQUE" to "Shopping",
        
        // H&M Group
        "H&M" to "Shopping", "H & M" to "Shopping", 
        "H AND M" to "Shopping", "HENNES" to "Shopping",
        "COS" to "Shopping", "COS STORES" to "Shopping",
        "& Other Stories" to "Shopping", "OTHER STORIES" to "Shopping",
        "Arket" to "Shopping", "ARKET" to "Shopping",
        "Weekday" to "Shopping", "WEEKDAY" to "Shopping",
        "Monki" to "Shopping", "MONKI" to "Shopping",
        
        // Calzedonia Group
        "Intimissimi" to "Shopping", "INTIMISSIMI" to "Shopping",
        "Calzedonia" to "Shopping", "CALZEDONIA" to "Shopping",
        "Tezenis" to "Shopping", "TEZENIS" to "Shopping",
        "Falconeri" to "Shopping", "FALCONERI" to "Shopping",
        
        // Fashion - International
        "Mango" to "Shopping", "MANGO" to "Shopping", "MNG" to "Shopping",
        "Uniqlo" to "Shopping", "UNIQLO" to "Shopping",
        "Gap" to "Shopping", "GAP" to "Shopping",
        "Old Navy" to "Shopping", "OLD NAVY" to "Shopping",
        "Banana Republic" to "Shopping", "BANANA REPUBLIC" to "Shopping",
        "Primark" to "Shopping", "PRIMARK" to "Shopping",
        "C&A" to "Shopping", "C AND A" to "Shopping",
        "New Yorker" to "Shopping", "NEW YORKER" to "Shopping",
        "Reserved" to "Shopping", "RESERVED" to "Shopping",
        "Sinsay" to "Shopping", "SINSAY" to "Shopping",
        "House" to "Shopping", "HOUSE BRAND" to "Shopping",
        "Cropp" to "Shopping", "CROPP" to "Shopping",
        "Mohito" to "Shopping", "MOHITO" to "Shopping",
        "Forever 21" to "Shopping", "FOREVER21" to "Shopping",
        "Topshop" to "Shopping", "TOPSHOP" to "Shopping",
        "River Island" to "Shopping", "RIVER ISLAND" to "Shopping",
        "Asos" to "Shopping", "ASOS" to "Shopping",
        "Boohoo" to "Shopping", "BOOHOO" to "Shopping",
        "Shein" to "Shopping", "SHEIN" to "Shopping",
        "Temu" to "Shopping", "TEMU" to "Shopping",
        "Wish" to "Shopping", "WISH COM" to "Shopping",
        
        // Premium Fashion
        "Tommy Hilfiger" to "Shopping", "TOMMY HILFIGER" to "Shopping",
        "Calvin Klein" to "Shopping", "CALVIN KLEIN" to "Shopping",
        "Ralph Lauren" to "Shopping", "POLO RALPH" to "Shopping",
        "Lacoste" to "Shopping", "LACOSTE" to "Shopping",
        "Hugo Boss" to "Shopping", "HUGO BOSS" to "Shopping",
        "Gant" to "Shopping", "GANT" to "Shopping",
        "Armani" to "Shopping", "ARMANI EXCHANGE" to "Shopping",
        "Michael Kors" to "Shopping", "MICHAEL KORS" to "Shopping",
        "Coach" to "Shopping", "COACH" to "Shopping",
        "Kate Spade" to "Shopping", "KATE SPADE" to "Shopping",
        "Guess" to "Shopping", "GUESS" to "Shopping",
        "Diesel" to "Shopping", "DIESEL" to "Shopping",
        "Replay" to "Shopping", "REPLAY" to "Shopping",
        "Levis" to "Shopping", "LEVIS" to "Shopping", "LEVI STRAUSS" to "Shopping",
        "Wrangler" to "Shopping", "WRANGLER" to "Shopping",
        "Lee" to "Shopping", "LEE JEANS" to "Shopping",
        
        // Sports Brands
        "Nike" to "Shopping", "NIKE" to "Shopping", 
        "NIKE RETAIL" to "Shopping", "NIKE STORE" to "Shopping",
        "Adidas" to "Shopping", "ADIDAS" to "Shopping",
        "Puma" to "Shopping", "PUMA" to "Shopping", 
        "Reebok" to "Shopping", "REEBOK" to "Shopping",
        "Under Armour" to "Shopping", "UNDER ARMOUR" to "Shopping",
        "New Balance" to "Shopping", "NEW BALANCE" to "Shopping",
        "Asics" to "Shopping", "ASICS" to "Shopping",
        "Converse" to "Shopping", "CONVERSE" to "Shopping",
        "Vans" to "Shopping", "VANS" to "Shopping",
        "Fila" to "Shopping", "FILA" to "Shopping",
        "Champion" to "Shopping", "CHAMPION" to "Shopping",
        "Skechers" to "Shopping", "SKECHERS" to "Shopping",
        "Timberland" to "Shopping", "TIMBERLAND" to "Shopping",
        "Columbia" to "Shopping", "COLUMBIA SPORTSWEAR" to "Shopping",
        "North Face" to "Shopping", "THE NORTH FACE" to "Shopping",
        "Patagonia" to "Shopping", "PATAGONIA" to "Shopping",
        "Helly Hansen" to "Shopping", "HELLY HANSEN" to "Shopping",
        "Jack Wolfskin" to "Shopping", "JACK WOLFSKIN" to "Shopping",
        "Salomon" to "Shopping", "SALOMON" to "Shopping",
        "Arc'teryx" to "Shopping", "ARCTERYX" to "Shopping",
        
        // Sports Retailers
        "Intersport" to "Shopping", "INTERSPORT" to "Shopping",
        "Cosmos Sport" to "Shopping", "COSMOS SPORT" to "Shopping",
        "Zakcret" to "Shopping", "ZAKCRET" to "Shopping", 
        "Sports Factory" to "Shopping", "SPORTS FACTORY" to "Shopping",
        "Foot Locker" to "Shopping", "FOOTLOCKER" to "Shopping",
        "JD Sports" to "Shopping", "JD SPORTS" to "Shopping",
        "Snipes" to "Shopping", "SNIPES" to "Shopping",
        "Sportsdirect" to "Shopping", "SPORTSDIRECT" to "Shopping",
        "Decathlon" to "Shopping", "DECATHLON" to "Shopping",
        "Athletes Foot" to "Shopping", "ATHLETES FOOT" to "Shopping",
        "Stadium" to "Shopping", "STADIUM" to "Shopping",
        "XXL Sport" to "Shopping", "XXL SPORT" to "Shopping",
        
        // Department Stores
        "Attica" to "Shopping", "ATTICA" to "Shopping", 
        "ATTICA DEPT" to "Shopping", "ATTICA GOLDEN HALL" to "Shopping",
        "Notos" to "Shopping", "NOTOS GALLERIES" to "Shopping",
        "Fokas" to "Shopping", "FOKAS" to "Shopping",
        "Galeries Lafayette" to "Shopping", "GALERIES LAFAYETTE" to "Shopping",
        "Harrods" to "Shopping", "HARRODS" to "Shopping",
        "Selfridges" to "Shopping", "SELFRIDGES" to "Shopping",
        "Harvey Nichols" to "Shopping", "HARVEY NICHOLS" to "Shopping",
        "El Corte Ingles" to "Shopping", "EL CORTE INGLES" to "Shopping",
        "Printemps" to "Shopping", "PRINTEMPS" to "Shopping",
        "KaDeWe" to "Shopping", "KADEWE" to "Shopping",
        "Breuninger" to "Shopping", "BREUNINGER" to "Shopping",
        "Nordstrom" to "Shopping", "NORDSTROM" to "Shopping",
        "Bloomingdales" to "Shopping", "BLOOMINGDALES" to "Shopping",
        "Macys" to "Shopping", "MACYS" to "Shopping",
        
        // Outlets
        "Factory Outlet" to "Shopping", "FACTORY OUTLET" to "Shopping",
        "McArthurGlen" to "Shopping", "DESIGNER OUTLET" to "Shopping",
        "MCARTHURGLEN ATHENS" to "Shopping",
        "Outlet" to "Shopping", "OUTLET STORE" to "Shopping",
        "Smart Park" to "Shopping", "SMART PARK" to "Shopping",
        "The Mall Athens" to "Shopping", "THE MALL" to "Shopping",
        "Golden Hall" to "Shopping", "GOLDEN HALL" to "Shopping",
        "Athens Metro Mall" to "Shopping", "METRO MALL" to "Shopping",
        "Mediterranean Cosmos" to "Shopping", "MED COSMOS" to "Shopping",
        
        // Shoes
        "Kalogirou" to "Shopping", "KALOGIROU" to "Shopping",
        "Tsakiris Mallas" to "Shopping", "TSAKIRIS MALLAS" to "Shopping",
        "Mourtzi" to "Shopping", "MOURTZI" to "Shopping",
        "Migato" to "Shopping", "MIGATO" to "Shopping",
        "Seven" to "Shopping", "SEVEN SHOES" to "Shopping",
        "Topshoes" to "Shopping", "TOPSHOES" to "Shopping",
        "Shoe Cult" to "Shopping", "SHOE CULT" to "Shopping",
        "Sante" to "Shopping", "SANTE" to "Shopping",
        "Bozikis" to "Shopping", "BOZIKIS" to "Shopping",
        "Clarks" to "Shopping", "CLARKS" to "Shopping",
        "Ecco" to "Shopping", "ECCO" to "Shopping",
        "Geox" to "Shopping", "GEOX" to "Shopping",
        "Camper" to "Shopping", "CAMPER" to "Shopping",
        "Birkenstock" to "Shopping", "BIRKENSTOCK" to "Shopping",
        "Crocs" to "Shopping", "CROCS" to "Shopping",
        "Dr Martens" to "Shopping", "DR MARTENS" to "Shopping",
        "UGG" to "Shopping",
        "Stuart Weitzman" to "Shopping", "STUART WEITZMAN" to "Shopping",
        "Jimmy Choo" to "Shopping", "JIMMY CHOO" to "Shopping",
        
        // Jewelry & Accessories
        "Pandora" to "Shopping", "PANDORA" to "Shopping",
        "Swarovski" to "Shopping", "SWAROVSKI" to "Shopping",
        "Tous" to "Shopping", "TOUS" to "Shopping",
        "Folli Follie" to "Shopping", "FOLLI FOLLIE" to "Shopping",
        "Links of London" to "Shopping", "LINKS OF LONDON" to "Shopping",
        "Thomas Sabo" to "Shopping", "THOMAS SABO" to "Shopping",
        "Trollbeads" to "Shopping", "TROLLBEADS" to "Shopping",
        "Alex and Ani" to "Shopping", "ALEX AND ANI" to "Shopping",
        "Nomination" to "Shopping", "NOMINATION" to "Shopping",
        "Accessorize" to "Shopping", "ACCESSORIZE" to "Shopping",
        "Bijou Brigitte" to "Shopping", "BIJOU BRIGITTE" to "Shopping",
        "Oriflame" to "Shopping", "ORIFLAME" to "Shopping",
        "Rolex" to "Shopping", "ROLEX" to "Shopping",
        "Omega" to "Shopping", "OMEGA WATCHES" to "Shopping",
        "Tag Heuer" to "Shopping", "TAG HEUER" to "Shopping",
        "Longines" to "Shopping", "LONGINES" to "Shopping",
        "Tissot" to "Shopping", "TISSOT" to "Shopping",
        "Casio" to "Shopping", "CASIO" to "Shopping",
        "Swatch" to "Shopping", "SWATCH" to "Shopping",
        "Fossil" to "Shopping", "FOSSIL" to "Shopping",
        "Daniel Wellington" to "Shopping", "DANIEL WELLINGTON" to "Shopping",
        "Jewelry" to "Shopping", "JEWELRY" to "Shopping",
        "Κοσμήματα" to "Shopping", "KOSMIMATA" to "Shopping",
        "Watch" to "Shopping", "WATCH STORE" to "Shopping",

        // ═══════════════════════════════════════════════════════════════
        // 💻 ELECTRONICS & TECH
        // ═══════════════════════════════════════════════════════════════
        
        // Greek E-commerce & Retail
        "Skroutz" to "Electronics", "SKROUTZ" to "Electronics", 
        "SKROUTZ.GR" to "Electronics", "PAYMENTS SKROUTZ" to "Electronics",
        "SKROUTZ MARKETPLACE" to "Electronics",
        "Public" to "Electronics", "PUBLIC" to "Electronics", 
        "PUBLIC RETAIL" to "Electronics", "PUBLIC.GR" to "Electronics",
        "Plaisio" to "Electronics", "PLAISIO" to "Electronics", 
        "PLAISIO COMPUTERS" to "Electronics",
        "Kotsovolos" to "Electronics", "KOTSOVOLOS" to "Electronics", 
        "DIXONS" to "Electronics", "SOUTH EAST EUROPE" to "Electronics",
        "Media Markt" to "Electronics", "MEDIA MARKT" to "Electronics",
        "Germanos" to "Electronics", "GERMANOS" to "Electronics", 
        "COSMOTE E-VALUE" to "Electronics",
        "E-shop" to "Electronics", "E-SHOP" to "Electronics", 
        "E-SHOP.GR" to "Electronics",
        "You.gr" to "Electronics", "YOU.GR" to "Electronics",
        "BestPrice" to "Electronics", "BESTPRICE" to "Electronics",
        "Electronet" to "Electronics", "ELECTRONET" to "Electronics",
        "Mediamarkt" to "Electronics", "MEDIAMARKT" to "Electronics",
        "Kaizer" to "Electronics", "KAIZER" to "Electronics",
        "Info Quest" to "Electronics", "INFOQUEST" to "Electronics",
        "Multirama" to "Electronics", "MULTIRAMA" to "Electronics",
        
        // Global E-commerce
        "Amazon" to "Electronics", "AMAZON" to "Electronics", 
        "AMZN" to "Electronics", "AMAZON.DE" to "Electronics",
        "AMAZON.CO.UK" to "Electronics", "AMAZON.ES" to "Electronics",
        "AMAZON.FR" to "Electronics", "AMAZON.IT" to "Electronics",
        "AMAZON.COM" to "Electronics", "AWS" to "Electronics",
        "AMAZON PRIME" to "Subscriptions",
        "Ebay" to "Electronics", "EBAY" to "Electronics", 
        "PAYPAL EBAY" to "Electronics",
        "AliExpress" to "Electronics", "ALIEXPRESS" to "Electronics",
        "ALIBABA" to "Electronics",
        "Banggood" to "Electronics", "BANGGOOD" to "Electronics",
        "Gearbest" to "Electronics", "GEARBEST" to "Electronics",
        "DHgate" to "Electronics", "DHGATE" to "Electronics",
        "JD.com" to "Electronics", "JD COM" to "Electronics",
        "Newegg" to "Electronics", "NEWEGG" to "Electronics",
        "CDW" to "Electronics",
        
        // Apple
        "Apple" to "Electronics", "APPLE STORE" to "Electronics", 
        "APPLE.COM" to "Electronics", "APPLE INC" to "Electronics",
        "Apple Store" to "Electronics", "APPLE RETAIL" to "Electronics",
        "iTunes" to "Subscriptions", "ITUNES" to "Subscriptions",
        "App Store" to "Subscriptions", "APPLE.COM/BILL" to "Subscriptions",
        
        // Other Brands
        "Samsung" to "Electronics", "SAMSUNG" to "Electronics",
        "SAMSUNG ELECTRONICS" to "Electronics",
        "Xiaomi" to "Electronics", "MI STORE" to "Electronics", 
        "XIAOMI" to "Electronics",
        "Huawei" to "Electronics", "HUAWEI" to "Electronics",
        "OnePlus" to "Electronics", "ONEPLUS" to "Electronics",
        "Oppo" to "Electronics", "OPPO" to "Electronics",
        "Vivo" to "Electronics", "VIVO" to "Electronics",
        "Realme" to "Electronics", "REALME" to "Electronics",
        "Google Store" to "Electronics", "GOOGLE STORE" to "Electronics",
        "Google" to "Subscriptions", "GOOGLE" to "Subscriptions",
        "Microsoft" to "Electronics", "MICROSOFT STORE" to "Electronics",
        "Sony" to "Electronics", "SONY" to "Electronics", 
        "SONY CENTER" to "Electronics",
        "LG" to "Electronics", "LG ELECTRONICS" to "Electronics",
        "Philips" to "Electronics", "PHILIPS" to "Electronics",
        "Panasonic" to "Electronics", "PANASONIC" to "Electronics",
        "Bose" to "Electronics", "BOSE" to "Electronics",
        "Bang Olufsen" to "Electronics", "BANG OLUFSEN" to "Electronics",
        "Harman Kardon" to "Electronics", "HARMAN" to "Electronics",
        "JBL" to "Electronics",
        "Beats" to "Electronics", "BEATS" to "Electronics",
        "Sennheiser" to "Electronics", "SENNHEISER" to "Electronics",
        "DJI" to "Electronics", "DJI STORE" to "Electronics",
        "GoPro" to "Electronics", "GOPRO" to "Electronics",
        "Canon" to "Electronics", "CANON" to "Electronics",
        "Nikon" to "Electronics", "NIKON" to "Electronics",
        "Sony Alpha" to "Electronics", "SONY ALPHA" to "Electronics",
        "Fujifilm" to "Electronics", "FUJIFILM" to "Electronics",
        "Olympus" to "Electronics", "OLYMPUS" to "Electronics",
        "Nintendo Switch" to "Electronics", "NINTENDO ESHOP" to "Electronics",
        "PlayStation Console" to "Electronics", "PLAYSTATION STORE" to "Electronics",
        "Xbox Console" to "Electronics", "XBOX STORE" to "Electronics",
        
        // Telecom Shops (use specific shop variants; bare names go to Utilities for bills)
        "Cosmote Shop" to "Electronics", "COSMOTE SHOP" to "Electronics",
        "Vodafone Shop" to "Electronics", "VODAFONE SHOP" to "Electronics",
        "Wind Shop" to "Electronics", "WIND SHOP" to "Electronics",
        "Nova Shop" to "Electronics", "NOVA SHOP" to "Electronics",
        "Phone" to "Electronics", "PHONE STORE" to "Electronics",
        "Mobile" to "Electronics", "MOBILE SHOP" to "Electronics",
        "Service Mobile" to "Electronics", "REPAIR SHOP" to "Electronics",
        "iRepair" to "Electronics", "IREPAIR" to "Electronics",

        // ═══════════════════════════════════════════════════════════════
        // 📺 SUBSCRIPTIONS - Streaming, Cloud, Software, AI
        // ═══════════════════════════════════════════════════════════════
        
        // Video Streaming
        "Netflix" to "Subscriptions", "NETFLIX" to "Subscriptions", 
        "NETFLIX.COM" to "Subscriptions",
        "Disney+" to "Subscriptions", "DISNEY PLUS" to "Subscriptions", 
        "DISNEY+" to "Subscriptions", "DISNEYPLUS" to "Subscriptions",
        "HBO" to "Subscriptions", "HBO MAX" to "Subscriptions", 
        "WARNER BROS" to "Subscriptions",
        "Hulu" to "Subscriptions", "HULU" to "Subscriptions",
        "Amazon Prime" to "Subscriptions", "PRIME VIDEO" to "Subscriptions", 
        "AMAZONPRIME" to "Subscriptions",
        "Apple TV" to "Subscriptions", "APPLE TV+" to "Subscriptions",
        "Paramount+" to "Subscriptions", "PARAMOUNT" to "Subscriptions",
        "Peacock" to "Subscriptions", "PEACOCK TV" to "Subscriptions",
        "Discovery+" to "Subscriptions", "DISCOVERY PLUS" to "Subscriptions",
        "Rakuten TV" to "Subscriptions", "RAKUTEN" to "Subscriptions",
        "Mubi" to "Subscriptions", "MUBI" to "Subscriptions",
        
        // Greek TV & Streaming
        "Cosmote TV" to "Subscriptions", "COSMOTE TV" to "Subscriptions",
        "Nova TV" to "Subscriptions", "NOVA TV" to "Subscriptions", 
        "Eon" to "Subscriptions", "EON TV" to "Subscriptions",
        "Vodafone TV" to "Subscriptions", "VODAFONE TV" to "Subscriptions",
        "Ertflix" to "Subscriptions", "ERTFLIX" to "Subscriptions",
        "Ant1+" to "Subscriptions", "ANT1 PLUS" to "Subscriptions",
        "Cinobo" to "Subscriptions", "CINOBO" to "Subscriptions",
        
        // Music Streaming
        "Spotify" to "Subscriptions", "SPOTIFY" to "Subscriptions", 
        "SPOTIFY LUXEMBOURG" to "Subscriptions",
        "Apple Music" to "Subscriptions",
        "Youtube Music" to "Subscriptions", "YOUTUBE PREMIUM" to "Subscriptions",
        "Deezer" to "Subscriptions", "DEEZER" to "Subscriptions",
        "Tidal" to "Subscriptions", "TIDAL" to "Subscriptions",
        "Soundcloud" to "Subscriptions", "SOUNDCLOUD" to "Subscriptions",
        "Qobuz" to "Subscriptions", "QOBUZ" to "Subscriptions",
        
        // Cloud & Storage
        "Google One" to "Subscriptions", "GOOGLE ONE" to "Subscriptions", 
        "GOOGLE STORAGE" to "Subscriptions", "GOOGLE CLOUD" to "Subscriptions",
        "iCloud" to "Subscriptions", "APPLE ICLOUD" to "Subscriptions",
        "Dropbox" to "Subscriptions", "DROPBOX" to "Subscriptions",
        "OneDrive" to "Subscriptions", "MICROSOFT STORAGE" to "Subscriptions",
        "Box.com" to "Subscriptions", "BOX.COM" to "Subscriptions",
        "Mega.nz" to "Subscriptions", "MEGA" to "Subscriptions",
        "Nextcloud" to "Subscriptions", "NEXTCLOUD" to "Subscriptions",
        
        // Productivity & Software
        "Microsoft 365" to "Subscriptions", "OFFICE 365" to "Subscriptions", 
        "MSFT" to "Subscriptions",
        "Adobe" to "Subscriptions", "ADOBE" to "Subscriptions", 
        "CREATIVE CLOUD" to "Subscriptions",
        "Canva" to "Subscriptions", "CANVA" to "Subscriptions",
        "Evernote" to "Subscriptions", "EVERNOTE" to "Subscriptions",
        "Notion" to "Subscriptions", "NOTION" to "Subscriptions",
        "Slack" to "Subscriptions", "SLACK" to "Subscriptions",
        "Zoom" to "Subscriptions", "ZOOM.US" to "Subscriptions",
        "Grammarly" to "Subscriptions", "GRAMMARLY" to "Subscriptions",
        "LinkedIn" to "Subscriptions", "LINKEDIN PREMIUM" to "Subscriptions",
        
        // VPN & Security
        "NordVPN" to "Subscriptions", "NORDVPN" to "Subscriptions",
        "ExpressVPN" to "Subscriptions", "EXPRESSVPN" to "Subscriptions",
        "Surfshark" to "Subscriptions", "SURFSHARK" to "Subscriptions",
        "CyberGhost" to "Subscriptions", "CYBERGHOST" to "Subscriptions",
        "Bitdefender" to "Subscriptions", "BITDEFENDER" to "Subscriptions",
        "Norton" to "Subscriptions", "NORTON" to "Subscriptions",
        "Avast" to "Subscriptions", "AVAST" to "Subscriptions",
        "Malwarebytes" to "Subscriptions", "MALWAREBYTES" to "Subscriptions",
        "1Password" to "Subscriptions", "1PASSWORD" to "Subscriptions",
        "LastPass" to "Subscriptions", "LASTPASS" to "Subscriptions",
        "Dashlane" to "Subscriptions", "DASHLANE" to "Subscriptions",
        
        // Gaming
        "Steam" to "Subscriptions", "STEAMGAMES" to "Subscriptions", 
        "VALVE" to "Subscriptions", "STEAM PURCHASE" to "Subscriptions",
        "Epic Games" to "Subscriptions", "EPIC GAMES" to "Subscriptions",
        "PlayStation" to "Subscriptions", "PLAYSTATION" to "Subscriptions", 
        "PSN" to "Subscriptions", "PS PLUS" to "Subscriptions", 
        "SONY NETWORK" to "Subscriptions",
        "Xbox" to "Subscriptions", "XBOX" to "Subscriptions", 
        "MICROSOFT XBOX" to "Subscriptions", "GAME PASS" to "Subscriptions",
        "Nintendo" to "Subscriptions", "NINTENDO ONLINE" to "Subscriptions",
        "EA Play" to "Subscriptions", "EA" to "Subscriptions",
        "Ubisoft+" to "Subscriptions", "UBISOFT" to "Subscriptions",
        "Blizzard" to "Subscriptions", "BATTLE.NET" to "Subscriptions",
        "Roblox" to "Subscriptions", "ROBLOX" to "Subscriptions",
        
        // Streaming & Social
        "Twitch" to "Subscriptions", "TWITCH" to "Subscriptions",
        "Discord" to "Subscriptions", "DISCORD" to "Subscriptions", 
        "NITRO" to "Subscriptions",
        "Patreon" to "Subscriptions", "PATREON" to "Subscriptions",
        "Substack" to "Subscriptions", "SUBSTACK" to "Subscriptions",
        "OnlyFans" to "Subscriptions", "ONLYFANS" to "Subscriptions",
        
        // AI & Dev Tools
        "ChatGPT" to "Subscriptions", "OPENAI" to "Subscriptions",
        "Claude" to "Subscriptions", "ANTHROPIC" to "Subscriptions",
        "Midjourney" to "Subscriptions", "MIDJOURNEY" to "Subscriptions",
        "GitHub" to "Subscriptions", "GITHUB" to "Subscriptions", 
        "COPILLOT" to "Subscriptions",
        "DigitalOcean" to "Subscriptions", "DIGITALOCEAN" to "Subscriptions",
        "Cloudflare" to "Subscriptions", "CLOUDFLARE" to "Subscriptions",
        "Heroku" to "Subscriptions", "HEROKU" to "Subscriptions",
        "Vercel" to "Subscriptions", "VERCEL" to "Subscriptions",
        
        // Education & Others
        "Duolingo" to "Subscriptions", "DUOLINGO" to "Subscriptions",
        "UDEMY" to "Education",
        "COURSERA" to "Education",
        "Masterclass" to "Subscriptions", "MASTERCLASS" to "Subscriptions",
        "Babbel" to "Subscriptions", "BABBEL" to "Subscriptions",
        "Fitness App" to "Subscriptions", "GYMSHARK" to "Subscriptions",
        "Strava" to "Subscriptions", "STRAVA" to "Subscriptions",
        "Tinder" to "Subscriptions", "TINDER" to "Subscriptions",
        "Bumble" to "Subscriptions", "BUMBLE" to "Subscriptions",

        // ═══════════════════════════════════════════════════════════════
        // 💡 UTILITIES - Bills, Services, Telecom
        // ═══════════════════════════════════════════════════════════════
        
        // Electricity
        "DEI" to "Utilities", "ΔΕΗ" to "Utilities", 
        "DIMOSIA EPICHEIRISI" to "Utilities", "DEH" to "Utilities",
        "Heron" to "Utilities", "IRON" to "Utilities", 
        "ΗΡΩΝ" to "Utilities", "HERON ENERGY" to "Utilities",
        "Protergia" to "Utilities", "PROTERGIA" to "Utilities", 
        "MYTILINEOS" to "Utilities",
        "Elpedison" to "Utilities", "ELPEDISON" to "Utilities",
        "Volton" to "Utilities", "VOLTON" to "Utilities",
        "NRG" to "Utilities", "NRG TRADING" to "Utilities",
        "Zenith" to "Utilities", "ZENITH" to "Utilities",
        "Watt+Volt" to "Utilities", "WATT AND VOLT" to "Utilities", 
        "WATT&VOLT" to "Utilities",
        "Fysiko Aerio" to "Utilities", "ΦΥΣΙΚΟ ΑΕΡΙΟ" to "Utilities",
        "Solar" to "Utilities", "SOLAR ENERGY" to "Utilities",
        
        // Water
        "EYDAP" to "Utilities", "ΕΥΔΑΠ" to "Utilities", 
        "NERO" to "Utilities", "WATER BILL" to "Utilities",
        "EYATH" to "Utilities", "ΕΥΑΘ" to "Utilities",
        
        // Gas & Heating
        "AERIO" to "Utilities", 
        "EPA" to "Utilities", "GAS BILL" to "Utilities",
        "Heating Oil" to "Utilities", "PETRELAIO" to "Utilities",
        
        // Telecom - Fixed & Mobile
        "Cosmote" to "Utilities", "COSMOTE" to "Utilities", 
        "OTE" to "Utilities", "ΟΤΕ" to "Utilities",
        "Vodafone" to "Utilities", "VODAFONE" to "Utilities", 
        "VODAFONE PANAFON" to "Utilities",
        "Wind" to "Utilities", "WIND" to "Utilities",
        "Nova" to "Utilities", "NOVA" to "Utilities", 
        "NOVA TELECOMB" to "Utilities",
        "Inalan" to "Utilities", "INALAN" to "Utilities",
        "Cyta" to "Utilities", "CYTA" to "Utilities",
        
        // Others
        "Koinoxrista" to "Utilities", "Κοινόχρηστα" to "Utilities", 
        "Polytechneio" to "Utilities", "SHARED EXPENSES" to "Utilities",
        "Cleaning Service" to "Utilities", "KATHARIOTHTA" to "Utilities",
        "Waste" to "Utilities", "DIMOS" to "Utilities",

        // ═══════════════════════════════════════════════════════════════
        // 🏥 HEALTH & FITNESS
        // ═══════════════════════════════════════════════════════════════
        
        // Pharmacies
        "Pharmacy" to "Health", "PHARMACY" to "Health", 
        "Φαρμακείο" to "Health", "Farmakeio" to "Health", 
        "DRUGSTORE" to "Health", "PHARME" to "Health",
        
        // Medical Services
        "Doctor" to "Health", "DOCTOR" to "Health", 
        "Γιατρός" to "Health", "Iatros" to "Health",
        "Dentist" to "Health", "Οδοντίατρος" to "Health", 
        "Odontiatros" to "Health",
        "Hospital" to "Health", "Nosokomeio" to "Health", 
        "Νοσοκομείο" to "Health",
        "Clinic" to "Health", "Κλινική" to "Health",
        "Diagnostic" to "Health", "ΔΙΑΓΝΩΣΤΙΚΟ" to "Health",
        
        // Centers & Platforms
        "Iatropolis" to "Health", "IATROPOLIS" to "Health",
        "Bioiatriki" to "Health", "BIOIATRIKI" to "Health",
        "Affidea" to "Health", "AFFIDEA" to "Health",
        "Euromedica" to "Health", "EUROMEDICA" to "Health",
        "Doctoranytime" to "Health", "DOCTORANYTIME" to "Health",
        
        // Specialists
        "Eye Clinic" to "Health", "Optical" to "Health", 
        "Οπτικά" to "Health", "Optika" to "Health",
        "Psychologist" to "Health", "Ψυχολόγος" to "Health",
        "Physiotherapy" to "Health", "Φυσικοθεραπεία" to "Health",
        
        // Fitness
        "Gym" to "Fitness", "GYM" to "Fitness", 
        "Gymnastirio" to "Fitness", "Γυμναστήριο" to "Fitness",
        "Yava" to "Fitness", "YAVA" to "Fitness",
        "Planet Fitness" to "Fitness", "Alterlife" to "Fitness", 
        "Holmes Place" to "Fitness",
        "Yoga" to "Fitness", "Pilates" to "Fitness", 
        "Crossfit" to "Fitness",
        "Sports Club" to "Fitness", "ΑΘΛΗΤΙΚΟΣ" to "Fitness",
        "Swimming" to "Fitness", "Κολυμβητήριο" to "Fitness",

        // ═══════════════════════════════════════════════════════════════
        // 🎬 ENTERTAINMENT
        // ═══════════════════════════════════════════════════════════════
        "Village Cinemas" to "Entertainment", "VILLAGE" to "Entertainment",
        "Odeon" to "Entertainment", "Ster Cinemas" to "Entertainment",
        "SNFCC" to "Entertainment", "STAVROS NIARCHOS" to "Entertainment",
        "Technopolis" to "Entertainment", "Ticketmaster" to "Entertainment",
        "Viva.gr" to "Entertainment", "VIVA" to "Entertainment",
        "Eventbrite" to "Entertainment", "Allou" to "Entertainment",
        "Kidom" to "Entertainment", "Escape Room" to "Entertainment",
        "Bowling" to "Entertainment", "Billiards" to "Entertainment",
        "Arcade" to "Entertainment", "Museum" to "Entertainment",
        "Μουσείο" to "Entertainment", "Theater" to "Entertainment",
        "Θέατρο" to "Entertainment", "Concert" to "Entertainment",

        // ═══════════════════════════════════════════════════════════════
        // 🏠 HOME & SERVICES
        // ═══════════════════════════════════════════════════════════════
        "IKEA" to "Shopping", // IKEA is Shopping but also Home
        "Leroy Merlin" to "Shopping", "S.G.B. AE" to "Shopping",
        "Praktiker" to "Shopping", "JYSK" to "Home",
        "BricoMarche" to "Home", "Maisons du Monde" to "Home",
        "Media Strom" to "Home", "Coco-mat" to "Home",
        "Plumber" to "Home", "Υδραυλικός" to "Home",
        "Electrician" to "Home", "Ηλεκτρολόγος" to "Home",
        "Cleaner" to "Home", "Καθαρίστρια" to "Home",
        "Pest Control" to "Home", "Locksmith" to "Home",
        "Moving" to "Home", "Furniture" to "Home",

        // ═══════════════════════════════════════════════════════════════
        // 💄 BEAUTY & PERSONAL CARE
        // ═══════════════════════════════════════════════════════════════
        "Sephora" to "Shopping", "SEPHORA" to "Shopping",
        "Hondos Center" to "Shopping", "HONDOS" to "Shopping",
        "Gallerie de Beaute" to "Shopping", "MAC" to "Shopping",
        "Hair Salon" to "Beauty", "Barber" to "Beauty",
        "Nail Salon" to "Beauty", "Spa" to "Beauty",
        "Waxing" to "Beauty", "The Body Shop" to "Beauty",
        "L'Occitane" to "Beauty", "Kiehl's" to "Beauty",
        "Rituals" to "Beauty", "Lush" to "Beauty",
        "Yves Rocher" to "Beauty", "Apivita" to "Beauty",
        "Korres" to "Beauty",

        // ═══════════════════════════════════════════════════════════════
        // ⚖️ LEGAL & GOVERNMENT
        // ═══════════════════════════════════════════════════════════════
        "EFKA" to "Legal & Gov", "ΕΦΚΑ" to "Legal & Gov",
        "AADE" to "Legal & Gov", "ΑΑΔΕ" to "Legal & Gov",
        "KEA" to "Legal & Gov", "Notary" to "Legal & Gov",
        "Lawyer" to "Legal & Gov", "Δικηγόρος" to "Legal & Gov",
        "Accountant" to "Legal & Gov", "Λογιστής" to "Legal & Gov",
        "Translation" to "Legal & Gov", "Certificate" to "Legal & Gov",
        "Driving License" to "Legal & Gov", "Paravolo" to "Legal & Gov",
        "ΠΑΡΑΒΟΛΟ" to "Legal & Gov", "TAXISNET" to "Legal & Gov",

        // ═══════════════════════════════════════════════════════════════
        // 🐾 PETS
        // ═══════════════════════════════════════════════════════════════
        "Pet City" to "Pets", "PET CITY" to "Pets",
        "Pet Shop" to "Pets", "Pet" to "Pets",
        "Vet" to "Pets", "Ktiniatros" to "Pets",
        "Animal" to "Pets", "Zooplus" to "Pets",
        "Grooming" to "Pets",

        // ═══════════════════════════════════════════════════════════════
        // 🎓 EDUCATION & BOOKS
        // ═══════════════════════════════════════════════════════════════
        "Udemy" to "Education", "Coursera" to "Education",
        "Book" to "Education", "Bookstore" to "Education",
        "Vivlio" to "Education", "Βιβλιοπωλείο" to "Education",
        "Ianos" to "Education", "Politeia" to "Education",
        "Evripidis" to "Education", // Note: "Public" is already mapped to Electronics above
        "School" to "Education", "University" to "Education",
        "Tuition" to "Education", "Didaktra" to "Education",

        // ═══════════════════════════════════════════════════════════════
        // 🏦 BANKING & FEES
        // ═══════════════════════════════════════════════════════════════
        "Revolut" to "Banking", "REVOLUT" to "Banking",
        "PayPal" to "Banking", "PAYPAL" to "Banking",
        "Curve" to "Banking", "Wise" to "Banking",
        "Alpha Bank" to "Banking", "Eurobank" to "Banking",
        "Piraeus" to "Banking", "Ethniki" to "Banking",
        "Commission" to "Banking", "Fee" to "Banking",
        "Interest" to "Banking",

        // ═══════════════════════════════════════════════════════════════
        // 🧸 KIDS & BABY
        // ═══════════════════════════════════════════════════════════════
        "Jumbo" to "Shopping", "Moustakas" to "Shopping",
        "DPAM" to "Kids", "Orchestra" to "Kids",
        "Lego Store" to "Kids", "LEGO" to "Kids",
        "Disney Store" to "Kids", "Hamleys" to "Kids",
        "Smyths" to "Kids", "Mothercare" to "Kids",
        "Baby" to "Kids", "Toys" to "Shopping"
    )
    
    // Additional mapping for normalized uppercase keys to capture variations
    fun getExpandedMap(): Map<String, String> {
        return merchantToCategoryMap.mapKeys { it.key.uppercase() }
    }
}
