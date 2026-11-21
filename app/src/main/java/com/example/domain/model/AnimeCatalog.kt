package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AnimeCategory(
    val id: String,
    val title: String,
    val query: String,
    val genreTag: String,
    val accentHex: Long,
)

@Immutable
data class AnimeSeriesPreview(
    val categoryId: String,
    val thumbnailUrls: List<String> = emptyList(),
    val wallpapers: List<AppWallpaper> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
) {
    val remainingCount: Int
        get() = (totalCount - thumbnailUrls.size.coerceAtMost(4)).coerceAtLeast(0)

    fun formatRemainingBadge(): String? {
        val remaining = remainingCount
        if (remaining <= 0) return null
        return if (remaining < 1000) {
            "+$remaining"
        } else {
            val thousands = remaining / 1000
            val tenths = (remaining % 1000) / 100
            if (tenths == 0) "+${thousands}K" else "+${thousands}.${tenths}K"
        }
    }
}

@Immutable
data class TrendingAnimeTopic(
    val title: String,
    val query: String,
    val categories: String = "110",
    val purity: String = "100",
    val sorting: String? = null,
    val order: String? = null,
    val initialPage: Int = 1,
)

object AnimeCatalog {
    val animeSearchKeywords: List<String> = listOf(
        // 50 Original
        "One Piece", "Naruto", "Bleach", "Dragon Ball", "Attack on Titan", "Demon Slayer",
        "Jujutsu Kaisen", "My Hero Academia", "Hunter x Hunter", "Fullmetal Alchemist",
        "Death Note", "Sword Art Online", "Tokyo Ghoul", "One Punch Man", "Fairy Tail",
        "Black Clover", "Gintama", "JoJo's Bizarre Adventure", "Steins;Gate", "Code Geass",
        "Neon Genesis Evangelion", "Cowboy Bebop", "Spy x Family", "Chainsaw Man", "Mob Psycho 100",
        "Haikyuu", "Kuroko no Basket", "Tokyo Revengers", "Dr. Stone", "Re:Zero",
        "Konosuba", "No Game No Life", "Your Name", "Weathering with You", "Doraemon",
        "Pokemon", "Detective Conan", "Sailor Moon", "Inuyasha", "Fate/Stay Night",
        "Gurren Lagann", "Psycho-Pass", "Akame ga Kill", "Seven Deadly Sins", "Vinland Saga",
        "Violet Evergarden", "Frieren", "Solo Leveling", "Cyberpunk Edgerunners", "Oshi no Ko",

        // 150 Curated Additions
        "Kaguya-sama: Love is War", "Blue Lock", "Mushoku Tensei", "That Time I Got Reincarnated as a Slime",
        "Overlord", "The Rising of the Shield Hero", "Hell's Paradise", "Kaiju No. 8",
        "The Apothecary Diaries", "Mashle", "Shangri-La Frontier", "Bocchi the Rock!",
        "Lycoris Recoil", "My Dress-Up Darling", "Horimiya", "Fruits Basket",
        "Toradora!", "Your Lie in April", "A Silent Voice", "I Want to Eat Your Pancreas",
        "86 EIGHTY-SIX", "Darling in the Franxx", "Parasyte: The Maxim", "Erased",
        "The Promised Neverland", "Made in Abyss", "Zom 100: Bucket List of the Dead", "Wind Breaker",
        "Delicious in Dungeon", "Blue Box", "Summertime Render", "Heavenly Delusion",
        "Pluto", "Trigun", "Rurouni Kenshin", "Yu Yu Hakusho",
        "Hellsing Ultimate", "Black Lagoon", "Monster", "Berserk",
        "Ghost in the Shell", "Akira", "Slam Dunk", "Blue Exorcist",
        "Soul Eater", "D.Gray-man", "Katekyo Hitman Reborn!", "Noragami",
        "Fire Force", "The Eminence in Shadow", "Goblin Slayer", "DanMachi",
        "Log Horizon", "Grimgar of Fantasy and Ash", "Cautious Hero", "K-On!",
        "Love Live!", "Rascal Does Not Dream of Bunny Girl Senpai", "The Quintessential Quintuplets", "Rent-a-Girlfriend",
        "Nisekoi", "Maid Sama!", "Komi Can't Communicate", "Oregairu",
        "Hyouka", "Anohana", "Clannad", "Angel Beats!",
        "Guilty Crown", "Mirai Nikki", "Elfen Lied", "Danganronpa",
        "Deadman Wonderland", "High School DxD", "Date A Live", "Witch Hat Atelier",
        "Mushishi", "Natsume's Book of Friends", "Spice and Wolf", "A Certain Magical Index",
        "A Certain Scientific Railgun", "Macross Frontier", "Mobile Suit Gundam: Iron-Blooded Orphans", "Mobile Suit Gundam: The Witch from Mercury",
        "Vivy: Fluorite Eye's Song", "Nier:Automata Ver1.1a", "Grand Blue", "Nichijou",
        "Daily Lives of High School Boys", "Great Teacher Onizuka", "Blue Period", "Hajime no Ippo",
        "Diamond no Ace", "Free!", "Yuri on Ice", "Yowamushi Pedal",
        "Chihayafuru", "Captain Tsubasa", "Inazuma Eleven", "Yu-Gi-Oh!",
        "Digimon Adventure", "Beyblade", "Shaman King", "Saint Seiya",
        "City Hunter", "Bakemonogatari", "Katanagatari", "Durarara!!",
        "Baccano!", "Bungo Stray Dogs", "Blood Blockade Battlefront", "Seraph of the End",
        "Terror in Resonance", "Devilman Crybaby", "Kakegurui", "Beastars",
        "Land of the Lustrous", "Dorohedoro", "Golden Kamuy", "Campfire Cooking in Another World",
        "Tsukimichi: Moonlit Fantasy", "Classroom of the Elite", "Tomodachi Game", "The Misfit of Demon King Academy",
        "The Irregular at Magic High School", "Daemons of the Shadow Realm (Yomi no Tsugai)", "Strike the Blood", "Tokyo Ravens",
        "K Project", "The God of High School", "Tower of God", "Noblesse",
        "Suzume", "The Garden of Words", "Howl's Moving Castle", "Spirited Away",
        "Princess Mononoke", "My Neighbor Totoro", "Kiki's Delivery Service", "Perfect Blue",
        "Paprika", "Redline", "Ninja Scroll", "Vampire Hunter D",
        "Afro Samurai", "Samurai Champloo", "Space Dandy", "FLCL",
        "Kill la Kill", "Little Witch Academia",
    )

    private val paletteAccents = listOf(
        0xFFFF2E54,
        0xFF8C52FF,
        0xFF00E5FF,
        0xFFFF7A00,
        0xFF00D2A0,
        0xFFE040FB,
        0xFFFFB800,
        0xFF29B6F6,
        0xFFFF4081,
        0xFF651FFF,
    )

    private fun cleanSearchQuery(title: String): String = when (title) {
        "Demon Slayer" -> "Kimetsu no Yaiba"
        "Your Name" -> "Kimi no Na wa"
        "Weathering with You" -> "Tenki no Ko"
        "That Time I Got Reincarnated as a Slime" -> "Tensei Shitara Slime"
        "Rascal Does Not Dream of Bunny Girl Senpai" -> "Bunny Girl Senpai"
        "Mobile Suit Gundam: Iron-Blooded Orphans" -> "Gundam Iron Blooded Orphans"
        "Mobile Suit Gundam: The Witch from Mercury" -> "Gundam Witch from Mercury"
        "The Apothecary Diaries" -> "Kusuriya no Hitorigoto"
        "The Rising of the Shield Hero" -> "Tate no Yuusha"
        "Delicious in Dungeon" -> "Dungeon Meshi"
        else -> title.replace(":", "").replace("!", "")
    }

    private fun resolveTag(index: Int): String = when {
        index < 50 -> "Top Classic & Iconic"
        index in 50..69 -> "Trending & Romance"
        index in 70..89 -> "Sci-Fi & Action"
        index in 90..109 -> "Dark Fantasy & Seinen"
        index in 110..129 -> "School & Slice of Life"
        index in 130..149 -> "Sports & Music"
        index in 150..169 -> "Supernatural & Mystery"
        index in 170..181 -> "Isekai & Academy"
        else -> "Anime Film & Cult Classic"
    }

    val allTwoHundred: List<AnimeCategory> = animeSearchKeywords.mapIndexed { index, title ->
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        AnimeCategory(
            id = "${index}_$slug",
            title = title,
            query = cleanSearchQuery(title),
            genreTag = resolveTag(index),
            accentHex = paletteAccents[index % paletteAccents.size],
        )
    }

    val trendingTopics: List<TrendingAnimeTopic> = listOf(
        TrendingAnimeTopic(
            title = "Frieren: Beyond Journey's End",
            query = "Frieren: Beyond Journey's End",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Jujutsu Kaisen",
            query = "Jujutsu Kaisen",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "JoJo's Bizarre Adventure",
            query = "JoJo's Bizarre Adventure",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 3,
        ),
        TrendingAnimeTopic(
            title = "Re:Zero − Starting Life in Another World",
            query = "Re:Zero",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 2,
        ),
        TrendingAnimeTopic(
            title = "Oshi no Ko",
            query = "Oshi no Ko",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Witch Hat Atelier",
            query = "Witch Hat Atelier",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Bleach: Thousand-Year Blood War",
            query = "Bleach: Thousand-Year Blood War",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "The Apothecary Diaries",
            query = "The Apothecary",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 2,
        ),
        TrendingAnimeTopic(
            title = "Daemons of the Shadow Realm (Yomi no Tsugai)",
            query = "Daemons of the Shadow Realm (Yomi no Tsugai)",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Solo Leveling",
            query = "Solo Leveling",
            categories = "110",
            purity = "100",
            sorting = "relevance",
            order = "desc",
            initialPage = 1,
        ),
    )

    /**
     * 12 primary wallpaper domains shown in HomeScreen category chips and SearchScreen Trending Categories grid.
     * Wallhaven categories bitmask: General (1xx) | Anime (x1x) | People (xx1).
     */
    val exploreCategories: List<TrendingAnimeTopic> = listOf(
        TrendingAnimeTopic(
            title = "Anime",
            query = "anime",
            categories = "010",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Games",
            query = "video games",
            categories = "110",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "City",
            query = "cityscape neon",
            categories = "110",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Cars",
            query = "sports car",
            categories = "100",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Nature",
            query = "nature landscape",
            categories = "100",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "TV & Movies",
            query = "movies cinema",
            categories = "101",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Cute",
            query = "cute kawaii",
            categories = "110",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Sci-Fi",
            query = "sci-fi futuristic",
            categories = "110",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Abstract",
            query = "abstract art",
            categories = "100",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Animals",
            query = "wildlife animals",
            categories = "100",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Comics",
            query = "comics superhero",
            categories = "110",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
        TrendingAnimeTopic(
            title = "Lifestyle",
            query = "aesthetic lifestyle",
            categories = "101",
            purity = "100",
            sorting = "toplist",
            order = "desc",
            initialPage = 1,
        ),
    )

    val trendingQueries: List<String> = trendingTopics.map { it.title }

    fun findTrendingTopic(queryOrTitle: String): TrendingAnimeTopic? {
        val normalized = queryOrTitle.trim()
        if (normalized.isEmpty()) return null
        return exploreCategories.firstOrNull { topic ->
            topic.title.equals(normalized, ignoreCase = true) ||
                    topic.query.equals(normalized, ignoreCase = true)
        } ?: trendingTopics.firstOrNull { topic ->
            topic.title.equals(normalized, ignoreCase = true) ||
                    topic.query.equals(normalized, ignoreCase = true)
        }
    }
}
