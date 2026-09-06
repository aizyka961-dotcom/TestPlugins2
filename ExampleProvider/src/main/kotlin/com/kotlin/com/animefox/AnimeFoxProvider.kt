package com.animefox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeFoxProvider : MainAPI() {
    override var mainUrl = "https://www.animefox.org"
    override var name = "AnimeFox"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "ru"
    override val hasMainPage = true

    // Главная страница (раздел Хентай)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/hentai/" else "$mainUrl/hentai/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).document

        val items = doc.select("div.shortstory, div.th-item, article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Новинки хентая", items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty()
        )
    }

    // Поиск
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&search_start=0&full_search=0&result_from=1&story=${query.encodeUri()}"
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).document

        return doc.select("div.shortstory, div.th-item, article").mapNotNull { it.toSearchResult() }
    }

    // Детальная страница тайтла
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Без названия"

        val poster = doc.selectFirst("link[rel=image_src]")?.attr("href")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".fullstory-poster img")?.attr("src")

        val description = doc.selectFirst(".fullstory-text")?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        // Парсим эпизоды: "1 серия", "2 серия" и т.д.
        // На AnimeFox обычно есть список серий внутри блока плеера
        val episodes = mutableListOf<Episode>()

        // Вариант A: серии в виде ссылок/кнопок внутри .player или .serial
        val episodeElements = doc.select(".player .serie-item, .player a[href*=#], .serial-tabs a, .episodes a")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, el ->
                val epName = el.text().trim() // "1 серия"
                val epData = el.attr("data-file") // иногда ссылка хранится тут
                    ?: el.attr("data-src")
                    ?: el.attr("href")
                    ?: "$url#${index + 1}"
                
                episodes.add(
                    Episode(
                        data = epData,
                        name = epName.ifEmpty { "Серия ${index + 1}" },
                        season = 1,
                        episode = index + 1
                    )
                )
            }
        } else {
            // Если серий нет (фильм из 1 части), создаём один эпизод
            episodes.add(
                Episode(
                    data = url,
                    name = "Полная серия",
                    season = 1,
                    episode = 1
                )
            )
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // Получение прямых ссылок на видео
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Если data уже прямая ссылка на mp4/m3u8
        if (data.endsWith(".mp4") || data.endsWith(".m3u8")) {
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value
                )
            )
            return true
        }

        // Иначе загружаем страницу и ищем видео
        val doc = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0")).document

        // 1. Ищем iframe в плеере
        val iframe = doc.selectFirst(".player iframe, #player iframe, .video-box iframe, iframe[src*=.php], iframe[src*=player], iframe[src*=video]")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            val absoluteIframe = if (iframe.startsWith("http")) iframe else "$mainUrl$iframe"
            // Пытаемся достать ссылку из iframe
            extractFromIframe(absoluteIframe, callback)
            return true
        }

        // 2. Ищем video/source теги
        doc.select("video source, video").forEach { video ->
            val src = video.attr("src").ifEmpty { video.attr("data-src") }
            if (!src.isNullOrBlank()) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = if (src.startsWith("http")) src else "$mainUrl$src",
                        referer = data,
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }
        }

        // 3. Ищем JS-переменные с файлом (типично для DLE)
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        
        // Регулярки для популярных плееров
        val regexList = listOf(
            """file["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex(),
            """url["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex(),
            """src["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex(),
            """var\s+pl\s*=\s*["']([^"']+)["']""".toRegex(),
            """data-file=["']([^"']+)["']""".toRegex()
        )

        for (regex in regexList) {
            regex.find(scripts)?.groupValues?.get(1)?.let { videoUrl ->
                val absoluteUrl = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl/$videoUrl"
                callback(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = absoluteUrl,
                        referer = data,
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }
        }

        return false
    }

    private suspend fun extractFromIframe(iframeUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(iframeUrl, headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to mainUrl)).document
            
            // Ищем video/source внутри iframe
            doc.select("video source, video").forEach { video ->
                val src = video.attr("src").ifEmpty { video.attr("data-src") }
                if (!src.isNullOrBlank()) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name (iframe)",
                            url = if (src.startsWith("http")) src else "$mainUrl$src",
                            referer = iframeUrl,
                            quality = Qualities.Unknown.value
                        )
                    )
                }
            }

            // Или ищем в скриптах iframe
            val scripts = doc.select("script").joinToString("\n") { it.data() }
            """file["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex()
                .find(scripts)?.groupValues?.get(1)?.let { videoUrl ->
                    callback(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl$videoUrl",
                            referer = iframeUrl,
                            quality = Qualities.Unknown.value
                        )
                    )
                }
        } catch (e: Exception) {
            logError(e)
        }
    }

    // Хелпер для парсинга карточки тайтла в поиске/на главной
    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = a.attr("href") ?: return null
        val title = a.attr("title").ifEmpty { a.text() }.ifEmpty {
            this.selectFirst("h2, h3, .title")?.text()
        } ?: return null

        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }
}
